package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillUpdate
import noline.zhouzhou.data.model.RecordInput
import noline.zhouzhou.data.model.kindsMonthWithLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(listOf("2026-02-30", ""), error?.invalid)
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

    // ── 买菜记账 ──

    /** 一条流水都没有：六个区间全是显式 null，months 仍然补满 7 项。 */
    private val emptyBills = """{"code":0,"data":{"count":0,"currency":"CNY","first":null,"last":null,""" +
        """"kinds":[{"kind":"veg","label":"蔬菜"},{"kind":"fruit","label":"水果"},{"kind":"meat","label":"肉禽"},""" +
        """{"kind":"fish","label":"水产"},{"kind":"egg","label":"蛋奶"},{"kind":"grain","label":"米面粮油"},""" +
        """{"kind":"seasoning","label":"调料"},{"kind":"other","label":"其他"}],""" +
        """"totals":null,"week":null,"prev_week":null,"month":null,"prev_month":null,"year":null,""" +
        """"months":[{"month":"2026-04","amount":0.0,"count":0},{"month":"2026-05","amount":0.0,"count":0},""" +
        """{"month":"2026-06","amount":0.0,"count":0},{"month":"2026-07","amount":0.0,"count":0},""" +
        """{"month":"2026-08","amount":0.0,"count":0},{"month":"2026-09","amount":0.0,"count":0},""" +
        """{"month":"2026-10","amount":0.0,"count":0}],"kinds_month":[],"by_date":[],"recent":[],""" +
        """"span":{"today":"2026-10-14","weekday":3,"month_days":31,"month_day_of":14}}}"""

    /** 有数据：max 只在累计那一档出现，本周为空的那档给 null。 */
    private val fullBills = """{"code":0,"data":{"count":3,"currency":"CNY","first":"2026-10-12","last":"2026-10-14",""" +
        """"totals":{"start":"2026-10-12","end":"2026-10-14","amount":88.5,"count":3,"days":2,""" +
        """"avg_day":44.25,"avg_bill":29.5,"max":{"date":"2026-10-14","title":"三文鱼","amount":59.9}},""" +
        """"week":{"start":"2026-10-12","end":"2026-10-18","amount":88.5,"count":3,"days":2,""" +
        """"avg_day":44.25,"avg_bill":29.5,"max":null},"prev_week":null,""" +
        """"month":{"start":"2026-10-01","end":"2026-10-31","amount":88.5,"count":3,"days":2,""" +
        """"avg_day":44.25,"avg_bill":29.5,"max":null},"prev_month":null,"year":null,""" +
        """"months":[{"month":"2026-10","amount":88.5,"count":3}],"kinds_month":[{"kind":"fish","amount":59.9,"count":1,"share":0.677}],"by_date":[""" +
        """{"date":"2026-10-14","amount":69.9,"count":2,"items":[{"id":"20261014-002","date":"2026-10-14",""" +
        """"title":"三文鱼","amount":59.9,"kind":"fish","shop":"盒马","note":"","time":1760000000,"updated":0}]}],""" +
        """"recent":[{"id":"20261014-001","date":"2026-10-14","title":"黄瓜","amount":3.5,"kind":"veg","shop":"","note":"","time":1759999000,"updated":0}],""" +
        """"span":{"today":"2026-10-14","weekday":3,"month_days":31,"month_day_of":14}}}"""

    @Test
    fun `bills overview keeps null aggregates and the seven months`() {
        val overview = runBlocking {
            NolineApi(Fake { ok(emptyBills) }).bills("https://x.work", UrlForm.Rewritten, "k")
        }
        assertEquals(0, overview.count)
        assertNull(overview.totals)
        assertNull(overview.week)
        assertNull(overview.prevMonth)
        assertNull(overview.year)
        assertEquals(7, overview.months.size)
        assertEquals(8, overview.kinds.size)
        // 部分区间必须标注过了几天，否则「本周比上周省」会读成一个还没结束的星期。
        assertEquals("3/7 天", overview.span.weekFraction)
        assertEquals("14/31 天", overview.span.monthFraction)
    }

    @Test
    fun `bills overview parses aggregates max and by_date`() {
        val overview = runBlocking {
            NolineApi(Fake { ok(fullBills) }).bills("https://x.work", UrlForm.Rewritten, "k")
        }
        assertEquals(88.5, overview.totals?.amount ?: 0.0, 0.001)
        assertEquals("三文鱼", overview.totals?.max?.title)
        // avg_day 除以的是开了火的天数，不是日历天数
        assertEquals(2, overview.totals?.days)
        assertEquals(44.25, overview.totals?.avgDay ?: 0.0, 0.001)
        assertNull(overview.week?.max)
        assertNull(overview.prevWeek)
        // kinds 缺省时退回 App 内置的 8 项，选择器不会因为站点少给一项就空掉。
        assertEquals(8, overview.kinds.size)
        assertEquals("水产", overview.kindsMonthWithLabels.single().second)
        val row = overview.byDate.single().items.single()
        assertEquals("20261014-002", row.id)
        assertEquals(0L, row.updated)
        assertEquals(69.9, overview.byDate.single().amount, 0.001)
    }

    @Test
    fun `bill records only sends limit when the caller asks`() {
        val page = """{"code":0,"data":{"total":0,"amount":0.0,"records":[]}}"""
        val default = Fake { ok(page) }
        runBlocking { NolineApi(default).billRecords("https://x.work", UrlForm.Rewritten, "k") }
        assertFalse(default.calls.single().url.contains("limit="))

        val whole = Fake { ok(page) }
        runBlocking {
            NolineApi(whole).billRecords(
                "https://x.work", UrlForm.Rewritten, "k",
                limit = 0, from = "2026-10-01", to = "2026-10-31", kind = "meat"
            )
        }
        val url = whole.calls.single().url
        // limit=0 是「整段都别截」，缺省 50 只给区间尾部 50 条，算出来的合计会少。
        assertTrue(url.contains("limit=0"))
        assertTrue(url.contains("to=2026-10-31"))
        assertTrue(url.endsWith("kind=meat"))
    }

    @Test
    fun `saveBills sends one batch and gets ids back`() {
        val transport = Fake {
            ok("""{"code":0,"data":{"saved":2,"total":20,"ids":["20261014-001","20261014-002"],"invalid":[]}}""")
        }
        val result = runBlocking {
            NolineApi(transport).saveBills(
                "https://x.work", UrlForm.Rewritten, "k",
                listOf(
                    BillInput("2026-10-14", "黄瓜", 3.5, "veg", "盒马"),
                    BillInput("2026-10-14", "盐", 2.0)
                )
            )
        }
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.url.endsWith("/api/bills/records"))
        assertEquals(listOf("20261014-001", "20261014-002"), result.ids)
        assertTrue(call.body!!.contains("黄瓜"))
        // 没带的可选键整个不发，省得服务器把 null 当成清空。
        assertFalse(call.body!!.contains("\"note\""))
    }

    @Test
    fun `bills partial success reports rejected titles`() {
        val result = runBlocking {
            NolineApi(Fake {
                ok("""{"code":0,"data":{"saved":1,"total":20,"ids":["20261014-001"],"invalid":["这把葱有点老就算了不买了"]}}""")
            }).saveBills(
                "https://x.work", UrlForm.Rewritten, "k",
                listOf(BillInput("2026-10-14", "黄瓜", 3.5), BillInput("2026-10-14", "", 2.0))
            )
        }
        assertEquals(1, result.saved)
        // 记账 invalid 里是品名（截到 20 字），不是体重那边的日期。
        assertEquals(listOf("这把葱有点老就算了不买了"), result.invalid)
    }

    @Test
    fun `bills all-rejected batch is a 400 with titles`() {
        val error = runBillsError(
            """{"code":400,"message":"没有可用记录","data":{"saved":0,"total":19,"ids":[],"invalid":["",""]}}"""
        )
        assertEquals(NolineIssue.BadInput, error?.issue)
        assertEquals(listOf("", ""), error?.invalid)
    }

    @Test
    fun `bills write off reads as read only, wrong key reads as wrong key`() {
        val readOnly = runBillsWriteError(readonly = true)
        assertEquals(NolineIssue.ReadOnly, readOnly?.issue)

        val badKey = runBillsWriteError(readonly = false)
        assertEquals(NolineIssue.WrongKey, badKey?.issue)
    }

    @Test
    fun `updateBill puts sends the id and returns the whole record`() {
        val transport = Fake {
            ok("""{"code":0,"data":{"record":{"id":"20261014-002","date":"2026-10-13","title":"三文鱼",""" +
                """"amount":59.9,"kind":"fish","shop":"","note":"","time":1760000000,"updated":1760009000},"total":20}}""")
        }
        val record = runBlocking {
            NolineApi(transport).updateBill(
                "https://x.work", UrlForm.Rewritten, "k",
                BillUpdate("20261014-002", "2026-10-13", "三文鱼", 59.9, "fish", "", "")
            )
        }
        val call = transport.calls.single()
        assertEquals("PUT", call.method)
        assertTrue(call.body!!.contains("\"id\":\"20261014-002\""))
        // 改了日期，id 不变；time 还是当初录入那一刻。
        assertEquals("20261014-002", record?.id)
        assertEquals("2026-10-13", record?.date)
        assertEquals(1760000000L, record?.time)
        assertEquals(1760009000L, record?.updated)
    }

    @Test
    fun `updateBill on an unknown id is a 404`() {
        val transport = Fake { req ->
            if (req.method == "PUT") ok("""{"code":404,"message":"没有这条记录"}""")
            else ok(fullBills)
        }
        val error = runBlocking {
            try {
                NolineApi(transport).updateBill(
                    "https://x.work", UrlForm.Rewritten, "k",
                    BillUpdate("20260101-999", "2026-01-01", "葱", 1.0, "veg", "", "")
                )
                null
            } catch (e: NolineException) {
                e
            }
        }
        assertEquals(404, error?.code)
        assertEquals(NolineIssue.BadPath, error?.issue)
    }

    @Test
    fun `deleteBill sends the id in the query and swallows 404`() {
        val gone = runBlocking {
            NolineApi(Fake { ok("""{"code":404,"message":"没有这条记录"}""") })
                .deleteBill("https://x.work", UrlForm.Rewritten, "k", "20261014-002")
        }
        assertFalse(gone)

        val transport = Fake { ok("""{"code":0,"data":{"deleted":"20261014-002","total":19}}""") }
        val deleted = runBlocking {
            NolineApi(transport).deleteBill("https://x.work", UrlForm.Rewritten, "k", "20261014-002")
        }
        assertTrue(deleted)
        val call = transport.calls.single()
        assertEquals("DELETE", call.method)
        assertTrue(call.url.endsWith("/api/bills/records?id=20261014-002"))
    }

    private fun runBillsError(body: String): NolineException? = runBlocking {
        try {
            NolineApi(Fake { ok(body) }).saveBills(
                "https://x.work", UrlForm.Rewritten, "k", listOf(BillInput("2026-10-14", "", 0.0))
            )
            null
        } catch (e: NolineException) {
            e
        }
    }

    private fun runBillsWriteError(readonly: Boolean): NolineException? {
        val transport = Fake { req ->
            when {
                req.method == "POST" -> ok("""{"code":403,"message":"写入未开启"}""")
                readonly -> ok(fullBills)
                else -> ok("""{"code":403,"message":"密钥不正确"}""")
            }
        }
        return runBlocking {
            try {
                NolineApi(transport).saveBills(
                    "https://x.work", UrlForm.Rewritten, "k", listOf(BillInput("2026-10-14", "黄瓜", 3.5))
                )
                null
            } catch (e: NolineException) {
                e
            }
        }
    }
}
