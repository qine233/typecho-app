package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.BtPanelConfig
import noline.zhouzhou.data.model.BtRow
import noline.zhouzhou.data.model.BtTable
import noline.zhouzhou.data.model.btRows
import noline.zhouzhou.data.model.btText
import noline.zhouzhou.data.model.btTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * 宝塔面板的对外接口客户端。
 *
 * 与站点接口最大的三点不同（13.1.0 实测确认）：
 * 1. 鉴权不放在 header 也不放 cookie，而是每次请求现算一个签名塞进表单体里，签名只按**秒**生效；
 * 2. 所有请求都是 `POST application/x-www-form-urlencoded`，路径按模块分（`/system`、`/data`、`/crontab`）；
 *    没开安全入口时，地址填到端口就行，开了才需要带上入口码那段路径；
 * 3. 失败几乎都是 HTTP 200 + `{"status":false,"msg":…}`，而 `msg` 有时是字符串、有时是数组；
 *    鉴权/白名单没过时更狠，直接回一整页 HTML。
 */
enum class BtIssue {
    NoConfig, WrongKey, ClockSkew, IpBlocked, ApiOff, HtmlPage, Network, Unknown
}

/**
 * @param fromPanel true 表示 message 是面板自己写的那句话，不是我们替它编的。
 *   界面会把这句原样摊出来，所以必须分得清是谁说的。
 */
class BtException(
    val issue: BtIssue,
    override val message: String,
    val fromPanel: Boolean = false
) : Exception(message)

/** 我们手上没有可实测的面板，所以分诊出来的每一类都必须自带「下一步做什么」。 */
val BtIssue.advice: String
    get() = when (this) {
        BtIssue.NoConfig -> "在页面上方填面板地址（含安全入口）和接口密钥"
        BtIssue.WrongKey -> "面板「接口 → API 通信」里那串密钥重新复制一次，注意别带进空格"
        BtIssue.ClockSkew -> "面板所在机器的系统时间偏了，签名只对前后几分钟有效；同步一下服务器时间"
        BtIssue.IpBlocked -> "把手机当前出口 IP 加进面板接口设置里的 IP 白名单"
        BtIssue.ApiOff -> "面板「接口 → API 通信」的总开关还没打开"
        BtIssue.HtmlPage -> "地址要连安全入口一起填，形如 https://1.2.3.4:8888/入口码"
        BtIssue.Network -> "确认手机和面板在同一个能打通的网络里，端口也对"
        BtIssue.Unknown -> "把上面那句原文带到面板「日志 → 面板日志」里对照看"
    }

/** 服务操作的四种动作。面板那边叫 start/stop/restart/reload。 */
enum class BtServiceOp(val wire: String, val label: String) {
    Start("start", "启动"),
    Stop("stop", "停止"),
    Restart("restart", "重启"),
    Reload("reload", "重载")
}

internal enum class BtModule(val path: String) {
    System("system"),
    Data("data"),
    Crontab("crontab")
}

data class BtRequest(val url: String, val form: Map<String, String>)

data class BtRaw(val status: Int, val contentType: String, val body: String)

/**
 * 一次请求的两种产出：数据位（可能是对象/数组/没有）和给人看的那句 msg。
 *
 * `root` 是去掉鉴权信封之前的整个回包。`/data` 把总条数写在 `page` 那串分页 HTML 里，
 * 和 `data` 平级而不是在里面，只拿 `data` 就永远数不出总条数。
 */
data class BtReply(
    val data: JsonElement,
    val msg: String,
    val root: JsonElement = JsonNull
)

fun interface BtTransport {
    suspend fun execute(request: BtRequest): BtRaw
}

class OkHttpBtTransport(
    private val trustSelfSigned: Boolean = false
) : BtTransport {

    // 客户端按「是否信任自签」分别建，切开关时由仓库层重建，不要把信任范围带进别的请求。
    private val client: OkHttpClient by lazy {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        if (!trustSelfSigned) base else base.newBuilder().trustEverything().build()
    }

    private val formType = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    override suspend fun execute(request: BtRequest): BtRaw = withContext(Dispatchers.IO) {
        val encoded = request.form.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }.toRequestBody(formType)
        val builder = Request.Builder()
            .url(request.url)
            .header("Accept", "application/json")
            .post(encoded)
        try {
            client.newCall(builder.build()).execute().use { response ->
                val type = response.header("Content-Type")
                    ?: response.body?.contentType()?.toString()
                    ?: ""
                BtRaw(response.code, type, response.body?.string() ?: "")
            }
        } catch (e: UnknownHostException) {
            throw BtException(BtIssue.Network, "域名解析不了，检查地址是否写对")
        } catch (e: SocketTimeoutException) {
            throw BtException(BtIssue.Network, "连接超时，面板可能不在那个端口上")
        } catch (e: ConnectException) {
            throw BtException(BtIssue.Network, "连不上面板，检查地址、端口和安全入口")
        }
    }
}

/**
 * 面板基本都用自签证书，认不了就在握手层失败。
 * 打开这条等于放弃「对面是不是我的面板」这件事，只该在自己信任的网络里用。
 */
private fun OkHttpClient.Builder.trustEverything(): OkHttpClient.Builder {
    val trusting = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trusting), SecureRandom()) }
    val verifier = HostnameVerifier { _, _ -> true }
    return sslSocketFactory(context.socketFactory, trusting).hostnameVerifier(verifier)
}

class BtPanelApi(
    private val config: BtPanelConfig,
    private val transport: BtTransport = OkHttpBtTransport(config.trustSelfSigned),
    /** 签名按秒算，单测要能把时间钉死。 */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 只读 ──

    /**
     * 看板的三个来源在这里只回原始 JSON，不在这一层翻成人话：
     * 实测 13.1.0 的 GetSystemTotal 不给负载，负载只在 GetNetWork 里，
     * 内存/运行时长两边又各给一份，合成一个快照得两个回包一起看。
     */
    suspend fun systemTotal(): JsonElement = call(BtModule.System, "GetSystemTotal").data

    suspend fun diskInfo(): JsonElement = call(BtModule.System, "GetDiskInfo").data

    suspend fun networkInfo(): JsonElement = call(BtModule.System, "GetNetWork").data

    /** 站点、数据库、面板日志走同一个 `/data` 口，只有 table 不同。 */
    suspend fun table(table: BtTable, page: Int = 1, limit: Int = PageLimit, keyword: String = ""): TablePage {
        val reply = call(
            BtModule.Data, "getData",
            buildMap {
                put("table", table.wire)
                put("p", page.toString())
                put("limit", limit.toString())
                put("sort", "")
                put("filtered", if (keyword.isBlank()) "false" else "true")
                if (keyword.isNotBlank()) put("search.value", keyword)
            }
        )
        return TablePage(
            rows = btRows(table, reply.data),
            total = noline.zhouzhou.data.model.btTotal(reply.root)
        )
    }

    /** 计划任务表。这个口的返回值是裸数组，没有 status 信封。 */
    suspend fun crontab(): List<BtRow> =
        btRows(BtTable.Crontab, call(BtModule.Crontab, "GetCrontab").data)

    /** 任务日志是纯文本，面板塞在 msg 里给。 */
    suspend fun crontabLog(id: String): String {
        val reply = call(BtModule.Crontab, "GetLogs", mapOf("id" to id, "tail" to "off"))
        return reply.msg.ifBlank { btText(reply.data) }
    }

    // ── 低风险写 ──

    /**
     * 重载/重启某个服务。
     *
     * 服务名要填面板认识的那个大写首字母名字：nginx、mysql 直接就是，PHP 一般带版本号
     * （`php-fpm-82`、`php-74`）。名字不对时面板会原样回一句，界面上露出来即可。
     */
    suspend fun service(name: String, op: BtServiceOp): String {
        val reply = call(
            BtModule.System, "ServiceAdmin",
            mapOf("name" to name, "type" to op.wire)
        )
        return reply.msg.ifBlank { "${op.label}指令已经发给面板" }
    }

    /** 重载面板本身：不断服务重读配置，最重只到面板进程。 */
    suspend fun reloadPanel(): String {
        val reply = call(BtModule.System, "ReWeb")
        return reply.msg.ifBlank { "面板已重载" }
    }

    /** 让某个计划任务立刻跑一次。任务本身干什么完全由面板那边决定。 */
    suspend fun runCrontab(id: String): String {
        val reply = call(BtModule.Crontab, "set_execute_script", mapOf("id" to id))
        return reply.msg.ifBlank { "任务已交给面板执行" }
    }

    data class TablePage(val rows: List<BtRow>, val total: Int?)

    // ── 内部 ──

    private suspend fun call(
        module: BtModule,
        action: String,
        params: Map<String, String> = emptyMap()
    ): BtReply {
        val site = config.site.trim().trimEnd('/')
        val key = config.key.trim()
        if (site.isBlank() || key.isBlank()) {
            throw BtException(BtIssue.NoConfig, "还没有填面板地址和接口密钥")
        }
        val time = clock()
        val form = linkedMapOf(
            "request_time" to time.toString(),
            "request_token" to sign(key, time),
            "action" to action
        )
        form.putAll(params)
        return interpret(transport.execute(BtRequest(moduleUrl(site, module), form)), action)
    }

    /**
     * 面板给的 status=false 一律抛异常，把它的原文当 message 带出去 —— 报错文案各版本差得多，
     * 猜翻译只会让人查不到原因。实测 13.1.0：这类失败 HTTP 状态码仍是 200，只有 body 里的 status 分得出。
     */
    private fun interpret(raw: BtRaw, action: String): BtReply {
        val body = raw.body
        val isHtml = raw.contentType.contains("html", ignoreCase = true) ||
            body.trimStart().startsWith("<")
        if (isHtml) {
            throw BtException(BtIssue.HtmlPage, htmlHint(raw.status))
        }
        val element = runCatching { json.parseToJsonElement(body) }.getOrElse {
            throw BtException(BtIssue.Unknown, "宝塔回的不是 JSON（HTTP ${raw.status}）")
        }
        val obj = element as? JsonObject
        val status = obj?.takeIf { it.containsKey("status") && (it.containsKey("msg") || it.containsKey("data")) }
            ?.let { readStatus(it["status"]) }
        if (obj != null && status != null) {
            val msg = btMessage(obj["msg"] ?: obj["message"])
            if (!status) {
                throw BtException(
                    issueForMessage(msg),
                    msg.ifBlank { "接口拒绝了这次请求（$action）" },
                    fromPanel = msg.isNotBlank()
                )
            }
            return BtReply(obj["data"] ?: JsonObject(emptyMap()), msg, obj)
        }
        // GetNetWork 回裸对象、GetCrontab 回裸数组：没有信封就当数据本身。
        return BtReply(element, "", element)
    }

    private fun readStatus(element: JsonElement?): Boolean? = when (element) {
        is JsonPrimitive -> element.booleanOrNull
            ?: element.contentOrNull?.toDoubleOrNull()?.let { it != 0.0 }
        else -> null
    }

    /** msg 位既能装一句话，也能装一组校验错误（对象、数组混着来）。 */
    private fun btMessage(element: JsonElement?): String = when (element) {
        is JsonArray -> element.map(::looseText).filter { it.isNotBlank() }.joinToString("；")
        else -> looseText(element)
    }

    private fun looseText(element: JsonElement?): String = when (element) {
        is JsonObject -> element.values.map(::looseText).filter { it.isNotBlank() }.joinToString("、")
        is JsonArray -> element.map(::looseText).filter { it.isNotBlank() }.joinToString("、")
        else -> btText(element)
    }

    private fun htmlHint(status: Int): String = when (status) {
        // 实测：没开安全入口时，地址只填到端口就能通；开了就必须带上入口码那一段路径。
        404 -> "宝塔回了一个 404 网页。开了「安全入口」的话地址要把入口码一起填上，" +
            "形如 https://1.2.3.4:8888/入口码；没开就只填到端口即可"
        else -> "宝塔回的是一页网页，不是接口数据。多半是接口总开关没开、本机出口 IP 不在白名单里，" +
            "或者地址填的其实不是面板本体（面板「接口 → API 通信」里都能看）"
    }

    companion object {
        private const val PageLimit = 30

        /** `md5(秒级时间戳 + md5(密钥))`，两次 MD5、小写十六进制，顺序不能换。 */
        fun sign(key: String, timeSeconds: Long): String =
            md5Hex(timeSeconds.toString() + md5Hex(key))

        fun md5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal fun moduleUrl(site: String, module: BtModule): String =
            site.trim().trimEnd('/') + "/" + module.path

        /** 面板的报错没有码表，只能按文案分诊；分不出来就归 Unknown，原文照样带出去。 */
        internal fun issueForMessage(msg: String): BtIssue = when {
            msg.contains("IP") && (msg.contains("不允许") || msg.contains("白名单") || msg.contains("不支持")) ->
                BtIssue.IpBlocked
            listOf("token", "签名", "密钥", "认证", "授权").any { msg.contains(it, ignoreCase = true) } ->
                BtIssue.WrongKey
            listOf("超时", "时间戳", "已过期", "expire").any { msg.contains(it, ignoreCase = true) } ->
                BtIssue.ClockSkew
            listOf("未开", "关闭", "禁止使用", "不允许使用").any { msg.contains(it) } -> BtIssue.ApiOff
            else -> BtIssue.Unknown
        }
    }
}
