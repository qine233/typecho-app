package com.typecho.app.data.remote

import com.typecho.app.data.model.HealthOverview
import com.typecho.app.data.model.PingInfo
import com.typecho.app.data.model.RecordInput
import com.typecho.app.data.model.RecordsPage
import com.typecho.app.data.model.SaveResult
import com.typecho.app.data.model.ThemePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 站点路径的三种写法，按可用性从高到低依次探测。 */
enum class UrlForm(val label: String) {
    Rewritten("伪静态 /api/…"),
    IndexPhp("index.php/api/…"),
    Query("查询串 ?noline_api=…")
}

/** 接口把 code 映射成人能看懂的处置建议，客户端不匹配 message 文案。 */
enum class NolineIssue { NoKey, WrongKey, SiteOff, ReadOnly, BadInput, BadPath, BadMethod, StoreUnwritable, Network, Unknown }

class NolineException(
    val code: Int,
    val issue: NolineIssue,
    override val message: String,
    val invalidDates: List<String> = emptyList()
) : Exception(message)

data class NolineRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class NolineRaw(val status: Int, val body: String)

/** HTTP 传输缝，单测里塞一个假的进来就能覆盖全部状态码。 */
fun interface NolineTransport {
    suspend fun execute(request: NolineRequest): NolineRaw
}

@Serializable
private data class NolineEnvelope(
    val code: Int = 0,
    val message: String? = null,
    val data: JsonElement? = null
)

sealed class ProbeResult {
    data class Ready(val form: UrlForm, val ping: PingInfo) : ProbeResult()
    data class Failed(val issue: NolineIssue, val message: String) : ProbeResult()
}

class OkHttpNolineTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : NolineTransport {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(request: NolineRequest): NolineRaw = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(jsonMediaType)
        when (request.method) {
            "POST" -> builder.post(body ?: "".toRequestBody(jsonMediaType))
            "DELETE" -> builder.delete(body)
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            NolineRaw(response.code, response.body?.string() ?: "")
        }
    }
}

class NolineApi(
    private val transport: NolineTransport = OkHttpNolineTransport()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ── 端点 ──

    suspend fun ping(siteUrl: String, form: UrlForm): PingInfo {
        val data = request(siteUrl, form, "ping", "GET", key = null)
        return decode(data, PingInfo.serializer())
    }

    suspend fun theme(siteUrl: String, form: UrlForm, key: String): ThemePayload {
        val data = request(siteUrl, form, "theme", "GET", key)
        return decode(data, ThemePayload.serializer())
    }

    suspend fun health(siteUrl: String, form: UrlForm, key: String): HealthOverview {
        val data = request(siteUrl, form, "health", "GET", key)
        return decode(data, HealthOverview.serializer())
    }

    suspend fun records(
        siteUrl: String, form: UrlForm, key: String,
        limit: Int = 0, from: String = "", to: String = ""
    ): RecordsPage {
        val query = buildMap {
            if (limit > 0) put("limit", limit.toString())
            if (from.isNotBlank()) put("from", from)
            if (to.isNotBlank()) put("to", to)
        }
        val data = request(siteUrl, form, "health/records", "GET", key, query)
        return decode(data, RecordsPage.serializer())
    }

    /** 批量写入。单条也走 records 数组，接口按天整体覆盖，重放安全。 */
    suspend fun saveRecords(
        siteUrl: String, form: UrlForm, key: String, records: List<RecordInput>
    ): SaveResult = withReadOnlyGuard(siteUrl, form, key) {
        val payload = json.encodeToString(MapSerializer, mapOf("records" to records))
        val data = request(siteUrl, form, "health/records", "POST", key, body = payload)
        decode(data, SaveResult.serializer())
    }

    /** @return false 表示那天本来就没有记录（404 在幂等语义上等于删除成功）。 */
    suspend fun deleteRecord(siteUrl: String, form: UrlForm, key: String, date: String): Boolean =
        withReadOnlyGuard(siteUrl, form, key) {
            try {
                val data = request(
                    siteUrl, form, "health/records", "DELETE", key, mapOf("date" to date)
                )
                data?.let { decode(it, com.typecho.app.data.model.DeleteResult.serializer()) }
                true
            } catch (e: NolineException) {
                if (e.code == 404) false else throw e
            }
        }

    // ── 连通性自检 ──

    /** 三种形态依次试 ping，返回第一个通的；不通时区分「接口没开」和「路径不对」。 */
    suspend fun probe(siteUrl: String): ProbeResult {
        var lastIssue = NolineIssue.Network
        var lastMessage = "连不上站点，检查网络和地址"
        for (form in UrlForm.entries) {
            try {
                return ProbeResult.Ready(form, ping(siteUrl, form))
            } catch (e: NolineException) {
                when (e.issue) {
                    NolineIssue.SiteOff -> return ProbeResult.Failed(e.issue, e.message)
                    NolineIssue.BadPath -> { lastIssue = e.issue; lastMessage = "站点上找不到健康接口，确认插件已启用" }
                    else -> { lastIssue = e.issue; lastMessage = e.message }
                }
            } catch (e: Exception) {
                lastIssue = NolineIssue.Network
                lastMessage = "连不上站点：${e.message}"
            }
        }
        return ProbeResult.Failed(lastIssue, lastMessage)
    }

    // ── 内部 ──

    /**
     * 403 有两个来源（密钥错 / 站点只读），接口不区分。
     * 补发一次 GET /api/health：GET 也 403 就是密钥错，GET 通则说明只是没开写入。
     */
    private suspend fun <T> withReadOnlyGuard(
        siteUrl: String, form: UrlForm, key: String, block: suspend () -> T
    ): T = try {
        block()
    } catch (e: NolineException) {
        if (e.code != 403) throw e
        val readWorks = try {
            health(siteUrl, form, key); true
        } catch (other: NolineException) {
            false
        }
        if (readWorks) throw NolineException(403, NolineIssue.ReadOnly, "站点是只读模式，后台没开「允许写入」")
        throw NolineException(403, NolineIssue.WrongKey, "密钥不正确")
    }

    private suspend fun request(
        siteUrl: String,
        form: UrlForm,
        endpoint: String,
        method: String,
        key: String?,
        query: Map<String, String> = emptyMap(),
        body: String? = null
    ): JsonElement? {
        if (key.isNullOrEmpty() && endpoint != "ping") {
            throw NolineException(401, NolineIssue.NoKey, "还没有配置接口密钥")
        }
        val url = buildUrl(siteUrl, form, endpoint, query)
        val headers = buildMap {
            put("Accept", "application/json")
            if (!key.isNullOrEmpty()) put("X-NoLine-Key", key)
        }

        var lastError: Exception? = null
        var attempt = 0
        while (attempt < MaxAttempts) {
            if (attempt > 0) delay(BackoffBaseMs shl (attempt - 1))
            attempt++
            val raw = try {
                transport.execute(NolineRequest(url, method, headers, body))
            } catch (e: Exception) {
                // 只有网络层失败才退避重放；业务错误码一律直接返回。
                lastError = e
                continue
            }
            return interpret(raw, endpoint)
        }
        throw NolineException(-1, NolineIssue.Network, "网络不通：${lastError?.message ?: "请求失败"}")
    }

    /**
     * 实测站点所有响应都是 HTTP 200，错误只体现在响应体的 code 上，
     * 所以状态码仅在 body 不是合法 JSON 时才拿来兜底判断。
     */
    private fun interpret(raw: NolineRaw, endpoint: String): JsonElement? {
        val envelope = runCatching {
            json.decodeFromString(NolineEnvelope.serializer(), raw.body)
        }.getOrNull()
        if (envelope == null) {
            val issue = when (raw.status) {
                404 -> NolineIssue.BadPath
                503 -> NolineIssue.SiteOff
                else -> NolineIssue.Unknown
            }
            throw NolineException(raw.status, issue, "站点返回的不是接口数据（$endpoint）")
        }
        if (envelope.code == 0) return envelope.data
        throw NolineException(
            code = envelope.code,
            issue = issueFor(envelope.code),
            message = envelope.message ?: "接口返回错误 ${envelope.code}",
            invalidDates = envelope.invalidDates()
        )
    }

    private fun issueFor(code: Int): NolineIssue = when (code) {
        400 -> NolineIssue.BadInput
        401 -> NolineIssue.NoKey
        403 -> NolineIssue.WrongKey
        404 -> NolineIssue.BadPath
        405 -> NolineIssue.BadMethod
        500 -> NolineIssue.StoreUnwritable
        503 -> NolineIssue.SiteOff
        else -> NolineIssue.Unknown
    }

    private fun NolineEnvelope.invalidDates(): List<String> {
        val data = this.data as? JsonObject ?: return emptyList()
        val invalid = data["invalid"] ?: return emptyList()
        return runCatching { invalid.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
    }

    private fun <T> decode(element: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): T {
        val payload = element ?: JsonObject(emptyMap())
        return json.decodeFromJsonElement(serializer, payload)
    }

    companion object {
        private const val MaxAttempts = 3
        private const val BackoffBaseMs = 400L
        private val MapSerializer = MapSerializer(
            String.serializer(),
            ListSerializer(RecordInput.serializer())
        )

        fun buildUrl(
            siteUrl: String, form: UrlForm, endpoint: String, query: Map<String, String> = emptyMap()
        ): String {
            val base = siteUrl.trim().trimEnd('/')
            val qs = query.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            return when (form) {
                UrlForm.Rewritten -> if (qs.isEmpty()) "$base/api/$endpoint" else "$base/api/$endpoint?$qs"
                UrlForm.IndexPhp -> if (qs.isEmpty()) "$base/index.php/api/$endpoint"
                                     else "$base/index.php/api/$endpoint?$qs"
                UrlForm.Query -> {
                    val head = "$base/?noline_api=${URLEncoder.encode(endpoint, "UTF-8")}"
                    if (qs.isEmpty()) head else "$head&$qs"
                }
            }
        }
    }
}
