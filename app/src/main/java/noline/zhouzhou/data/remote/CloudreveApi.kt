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
 * Cloudreve v4 客户端：邮箱密码换 token、三步分片上传、取永久直链。
 * 逐条对着 v4 源码核过（路由表、controllers、service/explorer、pkg/filemanager），
 * 错误码含义和博客里写的并不完全一样。
 */
enum class CloudreveIssue {
    BadInput, WrongPassword, Captcha, TwoFactor, NotLoggedIn, NoPermission, NotFound, EntityGone,
    SignExpired, SessionExpired, TooLarge, TypeNotAllowed, NoCapacity, IllegalName,
    FeatureOff, Network, SilentFailure, IoFailed, ChunkMismatch, Unknown
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

/**
 * 网盘里的一个节点。type 是 Cloudreve 的硬编码：1 目录、0 文件。
 * 时间戳是带时区的 RFC3339，且小数位长度不固定（.791 到 .937224028 都有），别当 epoch 秒解析。
 */
@Serializable
data class CloudreveFileNode(
    val type: Int = 0,
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val shared: Boolean = false
) {
    val isFolder: Boolean get() = type == 1

    /** 「我的文件」根目录显示名是空的，面包屑要自己补一个。 */
    val displayName: String get() = name.ifBlank { "我的文件" }
}

@Serializable
data class CloudrevePageInfo(
    @SerialName("page_size") val pageSize: Int = 0,
    /** 本站开了游标翻页（is_cursor=true），翻到底就没有这个字段。 */
    @SerialName("next_token") val nextToken: String = "",
    @SerialName("is_cursor") val isCursor: Boolean = false
)

@Serializable
data class CloudreveListing(
    val files: List<CloudreveFileNode> = emptyList(),
    val parent: CloudreveFileNode? = null,
    val pagination: CloudrevePageInfo? = null
)

/**
 * `GET /user/capacity`：Cloudreve 自己记账的容量。
 * 注意这跟存储端（OneDrive/本地盘）的真实剩余空间是两回事 —— 本站实测 Cloudreve 显示还剩 4.9 TB，
 * OneDrive 其实早就写满了。所以这块只能用来展示「已用多少」，不能拿来预警上传会不会失败。
 */
@Serializable
data class CloudreveCapacity(
    val total: Long = 0,
    val used: Long = 0
) {
    /** total 不是正数就没有上限可言（无限配额、老版本、接口没给），别算出「剩 -1 B」。 */
    val hasQuota: Boolean get() = total > 0

    /** 已用量最多显示到 100%，配额本身可能被写超。 */
    val usedFraction: Float
        get() = if (hasQuota) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * 一条分享链接。列表接口和详情接口给的不是同一套字段：刚建的分享没有 downloaded 计数，
 * 老分享又缺 remain_downloads，所以全部留默认值。
 * @param sourceType Cloudreve 的文件类型枚举，0 文件、1 目录。
 */
@Serializable
data class CloudreveShare(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val size: Long = 0,
    val visited: Int = 0,
    val downloaded: Int = 0,
    @SerialName("remain_downloads") val remainDownloads: Int = 0,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("password_protected") val passwordProtected: Boolean = false,
    val password: String = "",
    val expires: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val expired: Boolean = false,
    @SerialName("source_type") val sourceType: Int = 0
) {
    val isFolder: Boolean get() = sourceType == 1

    /** 私密分享的链接本身就带着密码段，复制出去对方直接能进。 */
    val hasPassword: Boolean get() = passwordProtected || password.isNotBlank()
}

@Serializable
data class CloudreveSharePage(
    val shares: List<CloudreveShare> = emptyList(),
    val pagination: CloudrevePageInfo? = null
)

@Serializable
data class CloudrevePolicyHint(
    val type: String = "",
    val name: String = ""
)

/**
 * `PUT /file/upload` 返回的上传会话。
 * @param chunkSize 0 表示不分片，整文件一次发完；
 * @param uploadUrls 非空就是「直传存储端」策略（OneDrive 等），地址是预签名的，不能带 Authorization。
 */
@Serializable
data class CloudreveUploadTicket(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("chunk_size") val chunkSize: Long = 0,
    @SerialName("upload_urls") val uploadUrls: List<String> = emptyList(),
    @SerialName("callback_secret") val callbackSecret: String = "",
    @SerialName("storage_policy") val storagePolicy: CloudrevePolicyHint? = null
) {
    val directToStorage: Boolean get() = uploadUrls.isNotEmpty()
}

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

/** 读文件的一段：start 是文件内偏移，length 是本片长度。 */
typealias CloudreveChunkReader = suspend (start: Long, length: Int) -> ByteArray

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
            // 主机的分片上传口是 POST + 裸字节，别只给 PUT 留 bytes 通道。
            "POST" -> builder.post(
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

@Serializable
private data class UploadSessionBody(
    val uri: String,
    val size: Long,
    @SerialName("mime_type") val mimeType: String
)

@Serializable
private data class CancelUploadBody(val id: String, val uri: String)

@Serializable
private data class CreateBody(val uri: String, val type: String)

@Serializable
private data class RenameBody(val uri: String, @SerialName("new_name") val newName: String)

@Serializable
private data class MoveBody(val uris: List<String>, val dst: String)

@Serializable
private data class ShareBody(
    val uri: String,
    @SerialName("is_private") val isPrivate: Boolean = false,
    val password: String = "",
    val downloads: Int = 0,
    val expire: Int = 0
)


class CloudreveApi(private val transport: CloudreveTransport = OkHttpCloudreveTransport()) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        // 网盘字段会老老实实回 null（空目录的 size、翻到底时的 next_token），别让它炸掉整个解析。
        coerceInputValues = true
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
     * v4 没有「一发入魂」的上传口子（`PUT /file/content` 是在线编辑改内容，不是上传）。
     * 正规三步：建会话 → 按会话给的 chunk_size 分片送 → 直传策略再回主机报一声传完了，
     * 占位文件才会转正。
     *
     * @param read 按「文件内偏移 + 本片长度」取字节，让调用方可以从文件描述符里一段段读，
     *   不必把整个文件先搬进内存。
     * @param onProgress 每送完一片回调一次已送字节数。
     */
    suspend fun uploadFile(
        site: String,
        token: String,
        uri: String,
        size: Long,
        mimeType: String,
        read: CloudreveChunkReader,
        onProgress: (Long) -> Unit = {}
    ) {
        val total = size
        val ticket = createUploadSession(site, token, uri, total, mimeType)
        if (ticket.sessionId.isBlank()) {
            throw CloudreveException(0, CloudreveIssue.Unknown, "Cloudreve 没有返回上传会话")
        }

        try {
            val chunkSize = if (ticket.chunkSize <= 0) total else ticket.chunkSize
            var index = 0
            var sent = 0L
            while (sent < total) {
                val length = minOf(chunkSize, total - sent).toInt()
                putChunk(site, token, ticket, read(sent, length), index, total)
                sent += length
                index += 1
                onProgress(sent)
            }
            if (ticket.directToStorage) notifyUploaded(site, token, ticket)
        } catch (e: Exception) {
            // 中途断了就把会话连同占位文件一起撤掉，别在网盘里留下一堆 0 字节的残骸。
            runCatching { cancelUploadSession(site, token, ticket.sessionId, uri) }
            throw e
        }
    }

    /** 编辑器压完图本来就是一把字节，走同一个分片循环。返回类型必须写死，否则和上面那个重载互相解析不出来。 */
    suspend fun uploadFile(
        site: String,
        token: String,
        uri: String,
        bytes: ByteArray,
        mimeType: String
    ): Unit = uploadFile(
        site = site,
        token = token,
        uri = uri,
        size = bytes.size.toLong(),
        mimeType = mimeType,
        read = { start, length -> bytes.copyOfRange(start.toInt(), (start + length).toInt()) }
    )

    private suspend fun createUploadSession(
        site: String, token: String, uri: String, size: Long, mimeType: String
    ): CloudreveUploadTicket {
        val query = "?uri=${encode(uri)}"
        val body = json.encodeToString(UploadSessionBody(uri, size, mimeType))
        return decode(
            call("PUT", apiUrl(site, "file/upload") + query, token, body = body),
            CloudreveUploadTicket.serializer()
        )
    }

    private suspend fun putChunk(
        site: String, token: String, ticket: CloudreveUploadTicket, chunk: ByteArray, index: Int, total: Long
    ) {
        if (!ticket.directToStorage) {
            // 本机/中转策略：分片交给主机，Content-Length 必须正好等于这一片的长度。
            call("POST", apiUrl(site, "file/upload/${ticket.sessionId}/$index"), token, bytes = chunk)
            return
        }

        // 直传策略：地址是存储端签好的，带上 Authorization 反而会被拒。
        val start = index.toLong() * ticket.chunkSize
        val url = ticket.uploadUrls.getOrElse(index) { ticket.uploadUrls.first() }
        val raw = transport.execute(
            CloudreveRequest(
                url = url,
                method = "PUT",
                headers = mapOf("Content-Range" to "bytes $start-${start + chunk.size - 1}/$total"),
                bytes = chunk
            )
        )
        if (raw.status !in 200..299) {
            throw CloudreveException(
                raw.status,
                CloudreveIssue.NoPermission,
                "直传到 ${ticket.storagePolicy?.type ?: "存储端"} 失败（HTTP ${raw.status}），云端授权过期或存储端已经写满"
            )
        }
    }

    /** 直传完了要通知主机收尾；OneDrive 是 POST，S3/COS 那类是 GET，本站只有 OneDrive。 */
    private suspend fun notifyUploaded(site: String, token: String, ticket: CloudreveUploadTicket) {
        val type = ticket.storagePolicy?.type.orEmpty()
        if (type.isNotBlank() && type != "onedrive") {
            throw CloudreveException(0, CloudreveIssue.FeatureOff, "App 还不支持直传到 $type 存储策略")
        }
        call("POST", apiUrl(site, "callback/onedrive/${ticket.sessionId}/${ticket.callbackSecret}"), token)
    }

    private suspend fun cancelUploadSession(site: String, token: String, sessionId: String, uri: String) {
        call(
            "DELETE",
            apiUrl(site, "file/upload"),
            token,
            body = json.encodeToString(CancelUploadBody(sessionId, uri))
        )
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

    /**
     * 列出一个目录。本站是游标翻页：上一页带回 next_token，下一页原样传回去，
     * 翻到底时 pagination 里根本没有 next_token 字段。目录恒定排在文件前面，不用自己排。
     */
    suspend fun list(
        site: String,
        token: String,
        uri: String,
        pageSize: Int = DefaultPageSize,
        nextToken: String? = null
    ): CloudreveListing {
        val query = buildList {
            add("uri=${encode(uri)}")
            add("page=1")
            add("page_size=$pageSize")
            add("order_by=$OrderBy")
            add("order_direction=$OrderDirection")
            if (!nextToken.isNullOrBlank()) add("next_page_token=${encode(nextToken)}")
        }.joinToString("&")
        return decode(
            call("GET", apiUrl(site, "file") + "?$query", token),
            CloudreveListing.serializer()
        )
    }

    /**
     * Cloudreve 记账的已用/配额。4.14.1 上只有 `user/capacity` 这条路（`user`、`user/quota` 都是 404）。
     */
    suspend fun capacity(site: String, token: String): CloudreveCapacity =
        decode(call("GET", apiUrl(site, "user/capacity"), token), CloudreveCapacity.serializer())

    /** 建目录时 Cloudreve 会顺手把父级中间目录一起建出来。 */
    suspend fun createFolder(site: String, token: String, uri: String) {
        call("POST", apiUrl(site, "file/create"), token, body = json.encodeToString(CreateBody(uri, "folder")))
    }

    suspend fun rename(site: String, token: String, uri: String, newName: String) {
        call("POST", apiUrl(site, "file/rename"), token, body = json.encodeToString(RenameBody(uri, newName)))
    }

    /** dst 是目标目录的 path；copy=true 才是复制，这里只做移动。 */
    suspend fun move(site: String, token: String, uris: List<String>, dst: String) {
        call("POST", apiUrl(site, "file/move"), token, body = json.encodeToString(MoveBody(uris, dst)))
    }

    /**
     * 只丢进回收站。真·彻底删除要 unlink/skip_soft_delete，普通账号会被 403「advance delete permission is required」拦下，
     * 所以别给 App 加这个口子。
     */
    suspend fun softDelete(site: String, token: String, uris: List<String>) {
        call("DELETE", apiUrl(site, "file"), token, body = json.encodeToString(SourceBody(uris)))
    }

    /**
     * 取一次性的签名下载地址（`POST /file/url`）。OneDrive 这类策略直接给存储端的预签名地址，
     * 默认一小时过期，所以只拿来立刻下载，别存进文章 —— 贴图要用 [directLink]。
     * 目录不走这个口（会回 40077 Entity not exist）。
     */
    suspend fun downloadUrl(site: String, token: String, uri: String): String {
        val data = call("POST", apiUrl(site, "file/url"), token, body = json.encodeToString(SourceBody(listOf(uri))))
        val first = data?.jsonObject?.get("urls")?.jsonArray?.firstOrNull()?.jsonObject
        val url = first?.get("url")?.jsonPrimitive?.content.orEmpty()
        if (url.isBlank()) throw CloudreveException(0, CloudreveIssue.Unknown, "Cloudreve 没有返回下载地址")
        return url
    }

    /** 列出自己的分享。page_size 有下限（给 5 会直接 40001 PageSize too short），翻页沿用目录那套游标。 */
    suspend fun listShares(site: String, token: String, nextToken: String? = null): CloudreveSharePage {
        val query = buildList {
            add("page=1")
            add("page_size=$SharePageSize")
            add("order_by=created_at")
            add("order_direction=desc")
            if (!nextToken.isNullOrBlank()) add("next_page_token=${encode(nextToken)}")
        }.joinToString("&")
        return decode(
            call("GET", apiUrl(site, "share") + "?$query", token),
            CloudreveSharePage.serializer()
        )
    }

    /**
     * 建分享，返回拼好的完整链接：私密分享会把密码当路径段缀在后面（`/s/<id>/<密码>`），复制出去对方直接能进。
     * @param expireSeconds 多少秒后过期，0 表示不过期
     * @param remainDownloads 剩余可下载次数，0 表示不限
     */
    suspend fun createShare(
        site: String,
        token: String,
        uri: String,
        password: String,
        expireSeconds: Int,
        remainDownloads: Int
    ): String {
        val body = json.encodeToString(
            ShareBody(uri, password.isNotBlank(), password, remainDownloads, expireSeconds)
        )
        val data = call("PUT", apiUrl(site, "share"), token, body = body)
        val link = data?.jsonPrimitive?.content.orEmpty()
        if (link.isBlank()) throw CloudreveException(0, CloudreveIssue.Unknown, "Cloudreve 没有返回分享链接")
        return link
    }

    /** 撤销一条分享。id 是链接里 `/s/` 后面那一段，不含密码。 */
    suspend fun deleteShare(site: String, token: String, id: String) {
        call("DELETE", apiUrl(site, "share/$id"), token)
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
        -1 -> CloudreveIssue.SilentFailure
        203 -> CloudreveIssue.TwoFactor
        401 -> CloudreveIssue.NotLoggedIn
        403 -> CloudreveIssue.NoPermission
        404 -> CloudreveIssue.NotFound
        40001 -> CloudreveIssue.BadInput
        40005 -> CloudreveIssue.SignExpired
        40006 -> CloudreveIssue.TypeNotAllowed
        40007, 40008, 40017, 40018, 40040, 40082, 40088 -> CloudreveIssue.NoPermission
        40011, 40020, 40023 -> CloudreveIssue.SessionExpired
        40013 -> CloudreveIssue.ChunkMismatch
        40019 -> CloudreveIssue.FeatureOff
        40026, 40027 -> CloudreveIssue.Captcha
        40044, 40016 -> CloudreveIssue.NotFound
        40077, 40081 -> CloudreveIssue.EntityGone
        40049 -> CloudreveIssue.TooLarge
        40050 -> CloudreveIssue.TypeNotAllowed
        40051 -> CloudreveIssue.NoCapacity
        40052 -> CloudreveIssue.IllegalName
        40069, 40080 -> CloudreveIssue.WrongPassword
        40089 -> CloudreveIssue.SessionExpired
        50004 -> CloudreveIssue.IoFailed
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
        CloudreveIssue.EntityGone -> "网盘里留着这条记录，但存储端已经没有这个文件了，多半是当初上传没成功的空壳"
        CloudreveIssue.TooLarge -> "图片超过 Cloudreve 的单次上传上限"
        CloudreveIssue.NoCapacity -> "Cloudreve 剩余空间不够"
        CloudreveIssue.TypeNotAllowed -> "Cloudreve 的存储策略不允许这个文件类型"
        CloudreveIssue.IllegalName -> "文件名被 Cloudreve 的命名规则拦下"
        CloudreveIssue.FeatureOff -> "站点没有开启对应功能"
        // Cloudreve 把内部错误包成 code=-1、msg 为空，真实原因只在服务器日志里。
        // 本站实测过一次：OneDrive 存满，建上传会话就直接 -1，看着像 App 坏了。
        CloudreveIssue.SilentFailure ->
            "Cloudreve 直接拒绝了这次请求，也没说原因（错误码 -1）；先确认存储端还有空间、云端授权没过期"
        CloudreveIssue.IoFailed -> "Cloudreve 读写存储时失败，存储策略本身大概率不通"
        CloudreveIssue.ChunkMismatch -> "分片大小和会话声明的对不上，云端拒收"
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

        private const val DefaultPageSize = 100
        // 分享列表的下限比目录严：page_size 给 5 会被直接拒成 40001。
        private const val SharePageSize = 10
        private const val OrderBy = "name"
        private const val OrderDirection = "asc"

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
