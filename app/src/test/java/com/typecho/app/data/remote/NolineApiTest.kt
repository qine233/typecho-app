package com.typecho.app.data.remote

import com.typecho.app.data.model.RecordInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 接口层单测。服务器实测所有响应都是 HTTP 200，错误只在 body 的 code 上，
 * 所以这些用例一律用 body 构造假响应（见 APP-API-SPEC §3 与实测差异）。
 */
class NolineApiTest {

    private class Fake(
        val flaky: Int = 0,
        val handler: (NolineRequest) -> NolineRaw
    ) : NolineTransport {
        val calls = mutableListOf<NolineRequest>()
        override suspend fun execute(request: NolineRequest): NolineRaw {
            calls += request
            if (calls.size <= flaky) throw IOException("connection reset")
            return handler(request)
        }
    }

    private fun ok(body: String) = NolineRaw(200, body)

    private val pingBody = """{"code":0,"data":{"name":"团子笔记","url":"https://x/","theme":"Noline","plugin":"1.0.0","time":"2026-10-12T05:17:26+08:00"}}"""

    /** 零记录时服务器那个 PHP 空关联数组给的是 `[]`，不是 `{}`（Plugin.php summary() 早退分支）。 */
    private val emptyOverview = """{"code":0,"data":{"profile":{"height":null,"gender":"male","target_weight":null,"ideal":null,"bmi_ranges":{"thin":18.5,"normal":24.0,"obese":28.0}},"count":0,"first":null,"last":null,"latest":null,"stats":null,"series":[],"goal":null,"changes":[]}}"""

    private val fullOverview = """{"code":0,"data":{"profile":{"height":175.0,"gender":"male","target_weight":65.0,"ideal":{"low":67.5,"high":72.5},"bmi_ranges":{"thin":18.5,"normal":24.0,"obese":28.0}},"count":2,"first":"2026-05-06","last":"2026-10-12","latest":{"date":"2026-10-12","weight":66.0,"delta":-1.0,"bmi":21.5,"category":"normal","fat":18.4,"note":"还差 1 公斤"},"stats":{"min":66.0,"max":74.0,"avg":69.6},"series":[{"date":"2026-05-06","weight":74.0,"delta":null,"bmi":24.2,"category":"over","fat":null,"note":"开始记录"}],"goal":{"target":65.0,"remaining":1.0,"done":false},"changes":{"d7":{"span":7,"from_date":"2026-10-05","delta":-1.0},"d30":{"span":30,"from_date":null,"delta":null}}}}"""

    @Test
    fun `builds all three url forms`() {
        assertEquals(
            "https://x.work/api/health/records?limit=100&from=2026-07-14",
            NolineApi.buildUrl(
                "https://x.work/", UrlForm.Rewritten, "health/records",
                mapOf("limit" to "100", "from" to "2026-07-14")
            )
        )
        assertEquals(
            "https://x.work/index.php/api/ping",
            NolineApi.buildUrl("https://x.work", UrlForm.IndexPhp, "ping")
        )
        assertEquals(
            "https://x.work/?noline_api=health%2Frecords&date=2026-10-12",
            NolineApi.buildUrl(
                "https://x.work", UrlForm.Query, "health/records", mapOf("date" to "2026-10-12")
            )
        )
    }

    @Test
    fun `probe records the first working form`() {
        val transport = Fake { req ->
            if (req.url.contains("/index.php/")) ok(pingBody)
            else NolineRaw(200, """{"code":404,"message":"接口不存在"}""")
        }
        val result = runBlocking { NolineApi(transport).probe("https://x.work") }
        assertTrue(result is ProbeResult.Ready)
        assertEquals(UrlForm.IndexPhp, (result as ProbeResult.Ready).form)
        assertEquals("1.0.0", result.ping.plugin)
    }

    @Test
    fun `probe separates site-off from missing endpoint`() {
        val off = runBlocking {
            NolineApi(Fake { ok("""{"code":503,"message":"接口未开启"}""") }).probe("https://x.work")
        }
        assertEquals(NolineIssue.SiteOff, (off as ProbeResult.Failed).issue)

        val missing = runBlocking {
            NolineApi(Fake { ok("""{"code":404,"message":"no such api"}""") }).probe("https://x.work")
        }
        assertEquals(NolineIssue.BadPath, (missing as ProbeResult.Failed).issue)
    }

    @Test
    fun `parses overview with all fields and tolerates the empty shape`() {
        val transport = Fake { ok(fullOverview) }
        val api = NolineApi(transport)
        val overview = runBlocking { api.health("https://x.work", UrlForm.Rewritten, "k") }
        assertEquals(2, overview.count)
        assertEquals("normal", overview.latest?.category)
        assertEquals("正常", overview.latest?.categoryLabel)
        assertEquals(-1.0, overview.changes["d7"]?.delta ?: 0.0, 0.001)
        assertFalse(overview.changes["d30"]?.fromDate?.isNotBlank() == true)
        assertEquals("还要减 1.0 kg", overview.goal?.remainingLabel)

        val empty = runBlocking {
            NolineApi(Fake { ok(emptyOverview) }).health("https://x.work", UrlForm.Rewritten, "k")
        }
        assertEquals(0, empty.count)
        assertEquals(null, empty.latest)
        assertEquals(null, empty.goal)
        assertEquals(0, empty.changes.size)
        assertEquals("", empty.series.firstOrNull()?.category ?: "")
    }

    @Test
    fun `missing key never hits the network`() {
        val transport = Fake { ok(fullOverview) }
        val error = runBlocking {
            try {
                NolineApi(transport).health("https://x.work", UrlForm.Rewritten, "")
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(NolineIssue.NoKey, error?.issue)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `401 maps to NoKey and 500 to unwritable store`() {
        assertIssue(401, """{"code":401,"message":"缺少密钥"}""", NolineIssue.NoKey)
        assertIssue(500, """{"code":500,"message":"数据目录不可写"}""", NolineIssue.StoreUnwritable)
        assertIssue(405, """{"code":405,"message":"方法不对"}""", NolineIssue.BadMethod)
    }

    private fun assertIssue(code: Int, body: String, expected: NolineIssue) {
        val error = runBlocking {
            try {
                NolineApi(Fake { ok(body) }).health("https://x.work", UrlForm.Rewritten, "k")
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals("code $code", expected, error?.issue)
        assertEquals(code, error?.code ?: 0)
    }

    @Test
    fun `403 on write is a key problem when read also fails`() {
        val transport = Fake { req ->
            if (req.method == "POST") ok("""{"code":403,"message":"密钥不正确"}""")
            else ok("""{"code":403,"message":"密钥不正确"}""")
        }
        val error = runBlocking {
            try {
                NolineApi(transport).saveRecords(
                    "https://x.work", UrlForm.Rewritten, "k", listOf(RecordInput("2026-10-12", 66.0))
                )
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(NolineIssue.WrongKey, error?.issue)
        // 一次写 + 一次判别用的读，不重试。
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `403 on write is read-only mode when read succeeds`() {
        val transport = Fake { req ->
            if (req.method == "POST") ok("""{"code":403,"message":"写入未开启"}""")
            else ok(fullOverview)
        }
        val error = runBlocking {
            try {
                NolineApi(transport).saveRecords(
                    "https://x.work", UrlForm.Rewritten, "k", listOf(RecordInput("2026-10-12", 66.0))
                )
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(NolineIssue.ReadOnly, error?.issue)
    }

    @Test
    fun `partial success keeps code 0 and reports invalid dates`() {
        val transport = Fake { ok("""{"code":0,"data":{"saved":1,"total":18,"invalid":["2026-02-30"]}}""") }
        val result = runBlocking {
            NolineApi(transport).saveRecords(
                "https://x.work", UrlForm.Rewritten, "k",
                listOf(RecordInput("2026-10-12", 66.0), RecordInput("2026-02-30", 66.0))
            )
        }
        assertEquals(1, result.saved)
        assertEquals(listOf("2026-02-30"), result.invalid)
    }

    @Test
    fun `400 on write surfaces invalid dates`() {
        val transport = Fake {
            ok("""{"code":400,"message":"没有可用记录","data":{"invalid":["2026-02-30",""]}}""")
        }
        val error = runBlocking {
            try {
                NolineApi(transport).saveRecords(
                    "https://x.work", UrlForm.Rewritten, "k", listOf(RecordInput("2026-02-30", 66.0))
                )
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(NolineIssue.BadInput, error?.issue)
        assertEquals(listOf("2026-02-30", ""), error?.invalidDates)
    }

    @Test
    fun `delete treats missing date as success and reports real delete`() {
        val gone = runBlocking {
            NolineApi(Fake { ok("""{"code":404,"message":"该日期没有记录"}""") })
                .deleteRecord("https://x.work", UrlForm.Rewritten, "k", "2026-10-12")
        }
        assertFalse(gone)

        val deleted = runBlocking {
            NolineApi(Fake { ok("""{"code":0,"data":{"deleted":"2026-10-12","total":17}}""") })
                .deleteRecord("https://x.work", UrlForm.Rewritten, "k", "2026-10-12")
        }
        assertTrue(deleted)
    }

    @Test
    fun `network failures back off at most three times`() {
        val transport = Fake(handler = { ok(fullOverview) }, flaky = 5)
        val error = runBlocking {
            try {
                NolineApi(transport).health("https://x.work", UrlForm.Rewritten, "k")
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(NolineIssue.Network, error?.issue)
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `recovers when a flaky link succeeds on retry`() {
        val transport = Fake(handler = { ok(fullOverview) }, flaky = 1)
        val overview = runBlocking {
            NolineApi(transport).health("https://x.work", UrlForm.Rewritten, "k")
        }
        assertEquals(2, overview.count)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `sends the key in a header only`() {
        val transport = Fake { ok(fullOverview) }
        runBlocking { NolineApi(transport).health("https://x.work", UrlForm.Rewritten, "secret-key") }
        val call = transport.calls.single()
        assertEquals("secret-key", call.headers["X-NoLine-Key"])
        assertFalse(call.url.contains("secret-key"))
    }

    @Test
    fun `non json body falls back to the http status`() {
        val error = runBlocking {
            try {
                NolineApi(Fake { NolineRaw(404, "<html>Not Found</html>") })
                    .health("https://x.work", UrlForm.Rewritten, "k")
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(NolineIssue.BadPath, error?.issue)
    }

    @Test
    fun `records endpoint passes limit and range`() {
        val transport = Fake { ok("""{"code":0,"data":{"total":1,"records":[]}}""") }
        runBlocking {
            NolineApi(transport).records(
                "https://x.work", UrlForm.Rewritten, "k", limit = 100,
                from = "2026-07-14", to = "2026-10-12"
            )
        }
        val url = transport.calls.single().url
        assertTrue(url.contains("limit=100"))
        assertTrue(url.contains("from=2026-07-14"))
        assertTrue(url.endsWith("to=2026-10-12"))
    }
}
