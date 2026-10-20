package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.BtPanelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网站模块的请求形状单测。这里的每一条都对应一次正式面板上的实测教训：
 * 键名拼错、大小写不对、多带一个参数，面板就给一句「指定参数无效!」或者空数组。
 */
class BtSiteApiTest {

    private class Fake(val reply: (BtRequest) -> BtRaw) : BtTransport {
        val calls = mutableListOf<BtRequest>()
        override suspend fun execute(request: BtRequest): BtRaw {
            calls += request
            return reply(request)
        }
    }

    private val key = "0123456789abcdef0123456789abcdef"
    private val config = BtPanelConfig(site = "https://1.2.3.4:8888/abc", key = key)
    private val ok = """{"status":true,"msg":"","data":true}"""

    private fun <T> run(transport: BtTransport, block: suspend BtSiteApi.() -> T): T =
        runBlocking {
            val panel = BtPanelApi(config, transport) { 1700000000L }
            BtSiteApi(panel).block()
        }

    private fun json(body: String) = BtRaw(200, "application/json; charset=utf-8", body)

    @Test
    fun `site list goes to the data table and carries nothing extra`() {
        val transport = Fake { json("""{"status":true,"msg":"","data":[{"id":"1","name":"x.work"}]}""") }
        val reply = run(transport) { sites(2, 30, "x.work") }
        val call = transport.calls.single()
        assertEquals("https://1.2.3.4:8888/abc/data", call.url)
        assertEquals("getData", call.form["action"])
        assertEquals("sites", call.form["table"])
        assertEquals("2", call.form["p"])
        assertEquals("30", call.form["limit"])
        assertEquals("true", call.form["filtered"])
        assertEquals("x.work", call.form["search.value"])
        // 带上 sort/order 面板就回空数组，这一格宁少不多。
        assertFalse(call.form.containsKey("sort"))
        assertFalse(call.form.containsKey("order"))
        // 行数据在 data 那一位，仓库层再从 root 里捞总数。
        assertTrue(reply.data.toString().contains("x.work"))
    }

    @Test
    fun `blank keyword switches filtering off instead of sending an empty search`() {
        val transport = Fake { json(ok) }
        run(transport) { sites(1, 30, "  ") }
        val form = transport.calls.single().form
        assertEquals("false", form["filtered"])
        assertFalse(form.containsKey("search.value"))
    }

    @Test
    fun `add site packs the domain list json and the static site sends an empty type`() {
        val transport = Fake { json("""{"siteStatus":true,"siteId":9,"databaseStatus":false,"ftpStatus":false}""") }
        val root = run(transport) {
            addSite(domain = "aa-test.work", path = "/www/wwwroot/aa-test.work", phpVersion = "00")
        }
        val form = transport.calls.single().form
        assertEquals("""{"domain":"aa-test.work","domainlist":[],"count":0}""", form["webname"])
        assertEquals("00", form["version"])
        // 纯静态站的 type 是空串，写 PHP 面板会去找一个不存在的 php-fpm。
        assertEquals("", form["type"])
        assertEquals("false", form["sql"])
        assertEquals("false", form["ftp"])
        // AddSite 没有 msg 信封，新站编号只在整棵回包里。
        assertTrue(root.toString().contains("\"siteId\":9"))
    }

    @Test
    fun `php site asks for database and ftp in the same shot`() {
        val transport = Fake { json("""{"siteStatus":true,"siteId":10}""") }
        run(transport) {
            addSite(
                domain = "aa-test2.work",
                path = "/www/wwwroot/aa-test2.work",
                phpVersion = "84",
                remark = "一次性",
                typeId = "3",
                withDatabase = true,
                withFtp = true
            )
        }
        val call = transport.calls.single()
        assertEquals("https://1.2.3.4:8888/abc/site", call.url)
        assertEquals("AddSite", call.form["action"])
        assertEquals("PHP", call.form["type"])
        assertEquals("true", call.form["sql"])
        assertEquals("true", call.form["ftp"])
        assertEquals("3", call.form["type_id"])
        assertEquals("一次性", call.form["ps"])
        assertEquals("utf8", call.form["codeing"])
    }

    @Test
    fun `default document key is capital Index`() {
        val transport = Fake { json(ok) }
        run(transport) { setIndex("7", listOf("index.php", "index.html")) }
        val form = transport.calls.single().form
        assertEquals("SetIndex", form["action"])
        // 小写 index 会让面板回 HTTP 400。
        assertEquals("index.php,index.html", form["Index"])
        assertFalse(form.containsKey("index"))
    }

    @Test
    fun `remark uses site_rname with rname not the domain object`() {
        val transport = Fake { json(ok) }
        run(transport) { setRemark("7", "改过的备注") }
        val form = transport.calls.single().form
        assertEquals("site_rname", form["action"])
        assertEquals("改过的备注", form["rname"])
    }

    @Test
    fun `stop and start both key on id plus the main domain`() {
        val transport = Fake { json(ok) }
        run(transport) { stopSite("7", "x.work") }
        run(transport) { startSite("7", "x.work") }
        assertEquals(listOf("SiteStop", "SiteStart"), transport.calls.map { it.form["action"] })
        assertTrue(transport.calls.all { it.form["name"] == "x.work" && it.form["id"] == "7" })
    }

    @Test
    fun `access log toggle is a flip not a set`() {
        val transport = Fake { json(ok) }
        run(transport) { toggleAccessLog("7") }
        val form = transport.calls.single().form
        assertEquals("logsOpen", form["action"])
        // 面板这边是「翻转」，带 open 参数也不会变成「设为开」，所以宁可不带。
        assertFalse(form.containsKey("open"))
    }

    @Test
    fun `closing the rate limit goes through the batch endpoint with zeros`() {
        val transport = Fake { json(ok) }
        run(transport) { closeLimitNet("7") }
        val form = transport.calls.single().form
        assertEquals("multiple_limit_net", form["action"])
        assertEquals("[7]", form["site_ids"])
        assertEquals("0", form["perserver"])
        assertEquals("0", form["perip"])
        assertEquals("0", form["limit_rate"])
        assertEquals("true", form["close_limit_net"])
    }

    @Test
    fun `opening the rate limit sends all three numbers`() {
        val transport = Fake { json(ok) }
        run(transport) { setLimitNet("7", 50, 10, 512) }
        val form = transport.calls.single().form
        assertEquals("SetLimitNet", form["action"])
        assertEquals("50", form["perserver"])
        assertEquals("10", form["perip"])
        assertEquals("512", form["limit_rate"])
    }

    @Test
    fun `ssl read takes the whole reply because GetSSL has no data envelope`() {
        // 实测 GetSSL 是平铺对象：只回 status + 证书字段，没有 data。
        val transport = Fake {
            json("""{"status":true,"httpTohttps":true,"auth_type":"letsencrypt","key":"SECRET-PRIVATE-KEY"}""")
        }
        val ssl = run(transport) { ssl("x.work") }.toString()
        assertTrue(ssl.contains("httpTohttps"))
        // 整棵树里确实带着私钥——解析层不读它是下一层的责任，这里保证没被就地丢掉。
        assertTrue(ssl.contains("SECRET-PRIVATE-KEY"))
        assertEquals("GetSSL", transport.calls.single().form["action"])
    }

    @Test
    fun `deploying ssl puts cert and key into the signed form only`() {
        val transport = Fake { json(ok) }
        run(transport) { setSsl("x.work", "CERT-PEM", "PRIVATE-PEM") }
        val call = transport.calls.single()
        assertEquals("SetSSL", call.form["action"])
        assertEquals("CERT-PEM", call.form["csr"])
        assertEquals("PRIVATE-PEM", call.form["key"])
        assertFalse(call.url.contains("CERT"))
    }

    @Test
    fun `closing ssl carries the fixed updateOf`() {
        val transport = Fake { json(ok) }
        run(transport) { closeSsl("x.work") }
        val form = transport.calls.single().form
        assertEquals("CloseSSLConf", form["action"])
        assertEquals("1", form["updateOf"])
    }

    @Test
    fun `proxy delete only works through RemoveProxy`() {
        val transport = Fake { json(ok) }
        run(transport) { removeProxy("x.work", "api") }
        val form = transport.calls.single().form
        assertEquals("RemoveProxy", form["action"])
        assertEquals("api", form["proxyname"])
    }

    @Test
    fun `proxy form repeats the fields the panel form always sends`() {
        val transport = Fake { json(ok) }
        run(transport) { createProxy("x.work", "api", "/api", "http://127.0.0.1:8080", cache = true, cacheSeconds = "60") }
        val form = transport.calls.single().form
        assertEquals("CreateProxy", form["action"])
        assertEquals("x.work", form["sitename"])
        assertEquals("/api", form["proxydir"])
        assertEquals("http://127.0.0.1:8080", form["proxysite"])
        assertEquals("1", form["cache"])
        assertEquals("60", form["cachetime"])
        assertEquals("x.work", form["todomain"])
        assertTrue(form["subfilter"].orEmpty().startsWith("[{"))
    }

    @Test
    fun `redirect form sends the domain json array and both type keys`() {
        val transport = Fake { json(ok) }
        run(transport) {
            createRedirect("x.work", "old", "https://new.work/", listOf("x.work", "m.x.work"), "/old", "path", "1", true)
        }
        val form = transport.calls.single().form
        assertEquals("CreateRedirect", form["action"])
        assertEquals("""["x.work","m.x.work"]""", form["redirectdomain"])
        assertEquals("/old", form["redirectpath"])
        assertEquals("path", form["redirecttype"])
        assertEquals("path", form["domainorpath"])
        assertEquals("1", form["type"])
        assertEquals("1", form["holdpath"])
    }

    @Test
    fun `site level 301 keys on srcDomain and toDomain`() {
        val transport = Fake { json(ok) }
        run(transport) { set301("x.work", "http://old.work", "http://new.work") }
        val form = transport.calls.single().form
        assertEquals("Set301Status", form["action"])
        assertEquals("http://old.work", form["srcDomain"])
        assertEquals("http://new.work", form["toDomain"])
        assertEquals("1", form["type"])
    }

    @Test
    fun `security headers pack the five booleans as one json object`() {
        val transport = Fake { json(ok) }
        run(transport) {
            setSecurityHeaders(
                "x.work",
                linkedMapOf(
                    "x_xss_protection" to true,
                    "x_content_type_options" to false,
                    "referrer_policy" to true,
                    "content_security_policy" to false,
                    "permissions_policy" to false
                )
            )
        }
        val form = transport.calls.single().form
        assertEquals("set_security_headers", form["action"])
        assertEquals("x.work", form["site_name"])
        assertEquals(
            """{"x_xss_protection":true,"x_content_type_options":false,"referrer_policy":true,"content_security_policy":false,"permissions_policy":false}""",
            form["security_info"]
        )
    }

    @Test
    fun `rewrite read is the only rewrite call this layer can make`() {
        val transport = Fake { json("""{"rewrite":["0.当前","layui"],"default_list":["0.当前","layui"]}""") }
        run(transport) { rewrite("x.work") }
        val call = transport.calls.single()
        assertEquals("GetRewriteList", call.form["action"])
        assertEquals("x.work", call.form["siteName"])
    }

    @Test
    fun `logs come back whole because the body rides in msg`() {
        val transport = Fake { json("""{"status":true,"msg":"127.0.0.1 - GET /"}""") }
        val access = run(transport) { accessLog("x.work") }.toString()
        assertTrue(access.contains("GET"))
        assertEquals("GetSiteLogs", transport.calls.single().form["action"])
    }

    @Test
    fun `password protection close needs only the id`() {
        val transport = Fake { json(ok) }
        run(transport) { closeHasPwd("7") }
        run(transport) { setHasPwd("7", "admin", "abc123") }
        assertEquals(listOf("CloseHasPwd", "SetHasPwd"), transport.calls.map { it.form["action"] })
        assertEquals("abc123", transport.calls[1].form["password"])
    }

    @Test
    fun `panel rejection surfaces the panel sentence as the result message`() {
        val transport = Fake { json("""{"status":false,"msg":"指定参数无效!"}""") }
        val error = runCatching { run(transport) { deleteSite("7", "x.work") } }.exceptionOrNull()
        assertTrue(error is BtException)
        assertEquals("指定参数无效!", (error as BtException).message)
        assertTrue(error.fromPanel)
    }

    @Test
    fun `every site call is a signed form post`() {
        val transport = Fake { json(ok) }
        run(transport) { domains("7") }
        val call = transport.calls.single()
        assertEquals("POST", call.verb)
        assertEquals("1700000000", call.form["request_time"])
        assertEquals(BtPanelApi.sign(key, 1700000000L), call.form["request_token"])
        assertEquals("GetSiteDomains", call.form["action"])
    }

    @Test
    fun `run path and index and binding read their own actions`() {
        val transport = Fake { json(ok) }
        run(transport) { runPath("7") }
        run(transport) { index("7") }
        run(transport) { dirBinding("7") }
        run(transport) { dirUserIni("7", "/") }
        assertEquals(
            listOf("GetSiteRunPath", "GetIndex", "GetDirBinding", "GetDirUserINI"),
            transport.calls.map { it.form["action"] }
        )
        assertEquals("/", transport.calls[3].form["path"])
    }

    @Test
    fun `php version change needs no prior stop and keys on siteName`() {
        val transport = Fake { json(ok) }
        run(transport) { setPhpVersion("x.work", "82") }
        val form = transport.calls.single().form
        assertEquals("SetPHPVersion", form["action"])
        assertEquals("x.work", form["siteName"])
        assertEquals("82", form["version"])
    }
}
