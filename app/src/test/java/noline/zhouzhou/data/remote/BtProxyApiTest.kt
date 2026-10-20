package noline.zhouzhou.data.remote

import kotlinx.coroutines.runBlocking
import noline.zhouzhou.data.model.BtPanelConfig
import noline.zhouzhou.data.model.BtProxyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向代理模块的请求形状单测。
 *
 * 这一口的形状全按 docs.bt.cn/api 钉住（正式面板那边暂时只有列表一口读过），
 * 所以测试守的是「别把文档里那几个别扭的名字改顺手」：路径风格、`action` 要重复带一份、
 * `set_remak` 少一个 r、`GetSiteLogs` 是大驼峰、`stype` 在路径里而不在参数里。
 * 面板那边一次失败调用也算一次，攒够 20 次锁一小时，这些拼错一次就少一次机会。
 */
class BtProxyApiTest {

    private class Fake(val reply: (BtRequest) -> BtRaw) : BtTransport {
        val calls = mutableListOf<BtRequest>()
        override suspend fun execute(request: BtRequest): BtRaw {
            calls += request
            return reply(request)
        }
    }

    private val key = "0123456789abcdef0123456789abcdef"
    private val config = BtPanelConfig(site = "https://192.0.2.1:8888/abc", key = key)
    private val ok = """{"status":true,"msg":"设置成功","data":true}"""

    private fun <T> run(transport: BtTransport, block: suspend BtProxyApi.() -> T): T =
        runBlocking {
            val panel = BtPanelApi(config, transport) { 1700000000L }
            BtProxyApi(panel).block()
        }

    private fun json(body: String) = BtRaw(200, "application/json; charset=utf-8", body)

    private val listReply =
        """{"status":true,"msg":"","data":{"where":"","page":"共2条","data":[{"id":"1","name":"api.example.com"}]}}"""

    @Test
    fun `every proxy call is a post to the path style url carrying the action twice`() {
        val transport = Fake { json(listReply) }
        run(transport) { entries(BtProxyEngine.Nginx, page = 2, limit = 30, keyword = "api") }
        val call = transport.calls.single()
        assertEquals("https://192.0.2.1:8888/abc/mod/proxy/com/get_list/nginx", call.url)
        assertEquals("POST", call.verb)
        assertEquals("get_list", call.form["action"])
        assertEquals("2", call.form["p"])
        assertEquals("30", call.form["limit"])
        assertEquals("api", call.form["search"])
    }

    @Test
    fun `the engine is a path segment and never a form field`() {
        val transport = Fake { json(listReply) }
        run(transport) { rules("api.example.com", BtProxyEngine.Openresty) }
        run(transport) { globalConf("api.example.com", BtProxyEngine.Apache) }
        assertEquals("/mod/proxy/com/get_proxy_list/openresty", transport.calls[0].url.removePrefix(config.site.trimEnd('/')))
        assertEquals("/mod/proxy/com/get_global_conf/apache", transport.calls[1].url.removePrefix(config.site.trimEnd('/')))
        transport.calls.forEach { assertFalse(it.form.containsKey("stype")) }
    }

    /** 文档里路径带连字符的写法在 13.1.0 上发不出去，方法名一律下划线。 */
    @Test
    fun `no hyphens survive in a method name`() {
        val transport = Fake { json(ok) }
        run(transport) { clearCache("api.example.com", BtProxyEngine.Nginx) }
        val path = transport.calls.single().url.substringAfter("/abc")
        assertFalse(path.contains("-"))
        assertTrue(path.startsWith("/mod/proxy/com/"))
    }

    @Test
    fun `the odd method names stay exactly as documented`() {
        val transport = Fake { json(ok) }
        run(transport) { setRemark("api.example.com", "7", "自测", BtProxyEngine.Nginx) }
        run(transport) { siteLogs("api.example.com", BtProxyEngine.Nginx) }
        run(transport) { deleteProxy("7", "api.example.com", BtProxyEngine.Nginx) }
        assertEquals("set_remak", transport.calls[0].form["action"])
        assertEquals("GetSiteLogs", transport.calls[1].form["action"])
        assertEquals("delete", transport.calls[2].form["action"])
        assertEquals("7", transport.calls[0].form["id"])
        assertEquals("自测", transport.calls[0].form["remark"])
    }

    @Test
    fun `create sends the domain and target the panel expects and no wildcard check here`() {
        val transport = Fake { json("""{"status":true,"msg":"添加成功"}""") }
        val msg = run(transport) {
            createProxy(
                engine = BtProxyEngine.Nginx,
                domains = "aa-test.example.com",
                proxyPass = "http://127.0.0.1:8080",
                proxyPath = "/api",
                proxyHost = "\$http_host",
                proxyType = "http",
                remark = "自测"
            )
        }
        val form = transport.calls.single().form
        assertEquals("create", form["action"])
        assertEquals("aa-test.example.com", form["domains"])
        assertEquals("http://127.0.0.1:8080", form["proxy_pass"])
        assertEquals("/api", form["proxy_path"])
        assertEquals("\$http_host", form["proxy_host"])
        assertEquals("http", form["proxy_type"])
        assertEquals("添加成功", msg)
    }

    @Test
    fun `switches send their own status field not just the site name`() {
        val transport = Fake { json(ok) }
        run(transport) { setCache("api.example.com", enabled = true, expires = "1d", engine = BtProxyEngine.Nginx) }
        run(transport) { setGzip("api.example.com", false, "1k", "5", "", BtProxyEngine.Nginx) }
        run(transport) { setWebsocket("api.example.com", true, BtProxyEngine.Nginx) }
        run(transport) { setLog("api.example.com", "file", "/www/wwwlogs/proxy.log", BtProxyEngine.Nginx) }
        run(transport) { setSiteStop("api.example.com", "12", false, BtProxyEngine.Nginx) }

        assertEquals("1", transport.calls[0].form["cache_status"])
        assertEquals("1d", transport.calls[0].form["expires"])
        assertEquals("0", transport.calls[1].form["gzip_status"])
        // 关的时候那三格不发：面板会把它们当「清空成 0」。
        assertFalse(transport.calls[1].form.containsKey("gzip_min_length"))
        assertEquals("1", transport.calls[2].form["websocket_status"])
        assertEquals("file", transport.calls[3].form["log_type"])
        assertEquals("/www/wwwlogs/proxy.log", transport.calls[3].form["log_path"])
        assertEquals("0", transport.calls[4].form["site_status"])
        assertEquals("12", transport.calls[4].form["site_id"])
    }

    /** 关掉缓存时连 `expires` 都不该带，免得面板把时长写死成上一次的值。 */
    @Test
    fun `cache off drops the expires field`() {
        val transport = Fake { json(ok) }
        run(transport) { setCache("api.example.com", enabled = false, expires = "1d", engine = BtProxyEngine.Nginx) }
        val form = transport.calls.single().form
        assertEquals("0", form["cache_status"])
        assertFalse(form.containsKey("expires"))
    }

    @Test
    fun `the list reply hands back the whole envelope so the total is still reachable`() {
        val transport = Fake { json(listReply) }
        val reply = run(transport) { entries(BtProxyEngine.Nginx, 1, 30, "") }
        // 总数只在 data.page 那串分页 HTML 里，只回 .data 就数不出来。
        assertEquals(2, noline.zhouzhou.data.model.btTotal(reply.root))
    }

    @Test
    fun `signature fields ride along on every proxy request`() {
        val transport = Fake { json(ok) }
        run(transport) { clearCache("api.example.com", BtProxyEngine.Nginx) }
        val form = transport.calls.single().form
        assertEquals("1700000000", form["request_time"])
        assertEquals(32, form["request_token"]!!.length)
    }

    @Test
    fun `a status false reply throws instead of returning the message`() {
        val transport = Fake { json("""{"status":false,"msg":"站点不存在"}""") }
        val failure = runCatching { run(transport) { clearCache("nope.example.com", BtProxyEngine.Nginx) } }
        val error = failure.exceptionOrNull()
        assertTrue(error is BtException)
        // status=false 走的是异常，那句原文一字不改带出去，界面上才查得到原因。
        assertEquals("站点不存在", error?.message)
    }
}
