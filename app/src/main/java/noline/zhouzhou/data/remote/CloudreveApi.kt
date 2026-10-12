package noline.zhouzhou.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Cloudreve v4 客户端：邮箱密码换 token、单请求上传、取永久直链。
 * 逐条对着 v4 源码核过（路由表、controllers、service/explorer、错误码常量表），
 * 错误码含义和博客里写的并不完全一样。
 */
enum class CloudreveIssue {
    BadInput, WrongPassword, Captcha, TwoFactor, NotLoggedIn, NoPermission, NotFound,
    SignExpired, SessionExpired, TooLarge, TypeNotAllowed, NoCapacity, IllegalName,
    FeatureOff, Network, Unknown
}

/** 这三种都靠「重新登录一次」自救，仓库层据此决定要不要重试。 */
val CloudreveIssue.isAuthFailure: Boolean
    get() = this == CloudreveIssue.NotLoggedIn ||
        this == CloudreveIssue.SessionExpired ||
        this == CloudreveIssue.SignExpired

class CloudreveException(
    val code: Int,
    val issue: CloudreveIssue,
    override val message: String
) : Exception(message)

@Serializable
data class CloudreveToken(
    @SerialName("access_token") val accessToken: String = "",
    /** RFC3339，默认只有一小时；到期就重登。 */
    @SerialName("access_expires") val accessExpires: String = ""
)

@Serializable
data class CloudreveFileNode(
    val id: String = "",
    val name: String = "",
    val path: String = ""
)

data class CloudreveRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bytes: ByteArray? = null
)

data class CloudreveRaw(val status: Int, val body: String)

/** HTTP 传输缝，单测塞假的进来就能覆盖全部错误码。 */
fun interface CloudreveTransport {
    suspend fun execute(request: CloudreveRequest): CloudreveRaw
}

class OkHttpCloudreveTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // 整张图一次写出去，别按 JSON 请求那么短的写超时。
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
) : CloudreveTransport {

    override suspend fun execute(request: CloudreveRequest): CloudreveRaw = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val jsonType = "application/json; charset=utf-8".toMediaType()
        when (request.method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(request.body?.toRequestBody(jsonType))
            "PUT" -> builder.put(
                request.bytes?.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    ?: request.body?.toRequestBody(jsonType)
                    ?: "".toRequestBody(jsonType)
            )
            else -> builder.post(request.body?.toRequestBody(jsonType) ?: "".toRequestBody(jsonType))
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                CloudreveRaw(response.code, response.body?.string() ?: "")
            }
        } catch (e: UnknownHostException) {
            throw CloudreveException(-1, CloudreveIssue.Network, "域名解析不了，检查地址是否写对")
        } catch (e: SocketTimeoutException) {
            throw CloudreveException(-1, CloudreveIssue.Network, "连接超时，稍后再试")
        } catch (e: ConnectException) {
            throw CloudreveException(-1, CloudreveIssue.Network, "连不上 Cloudreve 站点")
        }
    }
}

@Serializable
private data class LoginBody(val email: String, val password: String)

@Serializable
private data class SourceBody(val uris: List<String>)

class CloudreveApi(private val transport: CloudreveTransport = OkHttpCloudreveTransport()) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** v4 没有「个人 API Token」这回事，只能拿邮箱+密码换 1 小时的 access_token。 */
    suspend fun login(site: String, email: String, password: String): CloudreveToken {
        val data = call("POST", apiUrl(site, "session/token"), body = json.encodeToString(LoginBody(email, password)))
        // 登录返回 {user, token}，access_token 在 token 里。
        val tokenNode = (data as? JsonObject)?.get("token") ?: data
        val token = decode(tokenNode, CloudreveToken.serializer())
        if (token.accessToken.isBlank()) {
            throw CloudreveException(0, CloudreveIssue.Unknown, "Cloudreve 没有返回登录令牌")
        }
        return token
    }

    /**
     * 单请求上传：`PUT /api/v4/file/content?uri=…`，裸字节当请求体，上限是后台的「最大在线编辑大小」。
     * 路径上不存在的目录会被 Cloudreve 顺手建出来，不用先 mkdir。
     */
    suspend fun upload(site: String, token: String, uri: String, bytes: ByteArray): CloudreveFileNode {
        val target = "${apiUrl(site, "file/content")}?uri=${encode(uri)}"
        return decode(call("PUT", target, token, bytes = bytes), CloudreveFileNode.serializer())
    }

    /**
     * 永久直链 `/f/<id>/<name>`：游客可访问，服务端每次 302 到一个新鲜签名的地址，所以贴进文章不会失效。
     * 别用 `POST /api/v4/file/url` 的返回值贴文章 —— 那默认一小时过期，过期后还是 HTTP 200 + {"code":40005}，
     * 图片会在文章里无声消失。
     */
    suspend fun directLink(site: String, token: String, uri: String): String {
        val body = json.encodeToString(SourceBody(listOf(uri)))
        val data = call("PUT", apiUrl(site, "file/source"), token, body = body)
        val first = data?.jsonArray?.firstOrNull()?.jsonObject
        val link = first?.get("link")?.jsonPrimitive?.content.orEmpty()
        if (link.isBlank()) throw CloudreveException(0, CloudreveIssue.Unknown, "Cloudreve 没有返回直链")
        return link
    }

    // ── 内部 ──

    private suspend fun call(
        method: String,
        url: String,
        token: String? = null,
        body: String? = null,
        bytes: ByteArray? = null
    ): JsonElement? {
        val headers = buildMap {
            put("Accept", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val raw = transport.execute(CloudreveRequest(url, method, headers, body, bytes))
        return interpret(raw)
    }

    /**
     * Cloudreve 连鉴权失败都回 HTTP 200，成败只看响应体里的 code，
     * 所以状态码只在「返回的根本不是接口数据」时才拿来兜底。
     */
    private fun interpret(raw: CloudreveRaw): JsonElement? {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), raw.body) }.getOrNull()
        if (envelope == null) {
            val issue = when (raw.status) {
                404 -> CloudreveIssue.NotFound
                401, 403 -> CloudreveIssue.NotLoggedIn
                else -> CloudreveIssue.Unknown
            }
            throw CloudreveException(raw.status, issue, describe(issue, "HTTP ${raw.status}"))
        }
        if (envelope.code == 0) return envelope.data
        val issue = issueFor(envelope.code)
        throw CloudreveException(envelope.code, issue, describe(issue, envelope.text.orEmpty()))
    }

    private fun issueFor(code: Int): CloudreveIssue = when (code) {
        203 -> CloudreveIssue.TwoFactor
        401 -> CloudreveIssue.NotLoggedIn
        403 -> CloudreveIssue.NoPermission
        404 -> CloudreveIssue.NotFound
        40001 -> CloudreveIssue.BadInput
        40005 -> CloudreveIssue.SignExpired
        40006 -> CloudreveIssue.TypeNotAllowed
        40007, 40008, 40017, 40018, 40040, 40082, 40088 -> CloudreveIssue.NoPermission
        40011, 40020, 40023 -> CloudreveIssue.SessionExpired
        40019 -> CloudreveIssue.FeatureOff
        40026, 40027 -> CloudreveIssue.Captcha
        40044, 40016 -> CloudreveIssue.NotFound
        40049 -> CloudreveIssue.TooLarge
        40050 -> CloudreveIssue.TypeNotAllowed
        40051 -> CloudreveIssue.NoCapacity
        40052 -> CloudreveIssue.IllegalName
        40069, 40080 -> CloudreveIssue.WrongPassword
        40089 -> CloudreveIssue.SessionExpired
        else -> CloudreveIssue.Unknown
    }

    /** 错误码翻成人话；服务端原文只在没有对应说法时才露出来。 */
    private fun describe(issue: CloudreveIssue, raw: String): String = when (issue) {
        CloudreveIssue.WrongPassword -> "Cloudreve 邮箱或密码不对"
        CloudreveIssue.Captcha -> "站点开启了登录验证码，App 过不了；到 Cloudreve 后台关掉它"
        CloudreveIssue.TwoFactor -> "账号开启了二步验证，App 没法自动登录"
        CloudreveIssue.NotLoggedIn -> "Cloudreve 登录已过期"
        CloudreveIssue.SessionExpired -> "Cloudreve 登录状态失效，重新登录中…"
        CloudreveIssue.SignExpired -> "链接签名已过期"
        CloudreveIssue.NoPermission -> "这个 Cloudreve 账号没有上传或开外链的权限"
        CloudreveIssue.NotFound -> "站点上没有这个文件或接口，确认地址填的是 Cloudreve 本体"
        CloudreveIssue.TooLarge -> "图片超过 Cloudreve 的单次上传上限"
        CloudreveIssue.NoCapacity -> "Cloudreve 剩余空间不够"
        CloudreveIssue.TypeNotAllowed -> "Cloudreve 的存储策略不允许这个文件类型"
        CloudreveIssue.IllegalName -> "文件名被 Cloudreve 的命名规则拦下"
        CloudreveIssue.FeatureOff -> "站点没有开启对应功能"
        CloudreveIssue.BadInput -> "Cloudreve 说参数不对${raw.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
        CloudreveIssue.Network -> raw
        CloudreveIssue.Unknown -> "Cloudreve 出错${raw.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
    }

    @Serializable
    private data class Envelope(
        val code: Int = 0,
        val msg: String? = null,
        val message: String? = null,
        val data: JsonElement? = null
    ) {
        val text: String? get() = msg?.takeIf { it.isNotBlank() } ?: message
    }

    private fun <T> decode(element: JsonElement?, serializer: KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, element ?: JsonObject(emptyMap()))

    companion object {
        fun apiUrl(site: String, path: String): String =
            site.trim().trimEnd('/') + "/api/v4/" + path

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
