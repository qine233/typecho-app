package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.BtEndpoint
import noline.zhouzhou.data.model.BtEndpointSpec
import noline.zhouzhou.data.model.BtParamSpec
import noline.zhouzhou.data.model.BtPanelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用调用台那一层的单测：目录里写的路径、动词、动作放哪儿，就必须照那样发出去。
 *
 * 这一层最容易被改坏的地方是签名——一旦允许参数表覆盖 `request_token`，
 * 面板只会回一句「token 过期」，人根本想不到是自己填的那一行把签名顶掉了。
 */
class BtGenericInvokeTest {

    private class Fake(val reply: (BtRequest) -> BtRaw) : BtTransport {
        val calls = mutableListOf<BtRequest>()
        override suspend fun execute(request: BtRequest): BtRaw {
            calls += request
            return reply(request)
        }
    }

    private val key = "0123456789abcdef0123456789abcdef"
    private val config = BtPanelConfig(site = "https://1.2.3.4:8888/abc", key = key)
    private val now = 1700000000L

    private fun run(transport: BtTransport, block: suspend BtPanelApi.() -> Unit) =
        runBlocking { BtPanelApi(config, transport) { now }.block() }

    private fun endpoint(
        kind: String = "a",
        verb: String = "POST",
        path: String = "/site",
        name: String = "StopSite",
        params: List<BtParamSpec> = emptyList()
    ) = BtEndpoint(
        BtEndpointSpec(
            m = "site", mc = "网站管理", k = kind, v = verb, p = path, a = name,
            ti = "停止站点", g = "write", ps = params
        )
    )

    @Test
    fun `action style endpoint posts to the module path with the action in the body`() {
        val transport = Fake { BtRaw(200, "application/json", """{"status":true,"msg":"ok","data":[]}""") }
        run(transport) { invoke(endpoint(), mapOf("siteName" to "www.a.com")) }
        val call = transport.calls.single()
        assertEquals("POST", call.verb)
        assertEquals("https://1.2.3.4:8888/abc/site", call.url)
        assertEquals("StopSite", call.form["action"])
        assertEquals("www.a.com", call.form["siteName"])
        assertEquals(now.toString(), call.form["request_time"])
        assertEquals(BtPanelApi.sign(key, now), call.form["request_token"])
    }

    @Test
    fun `path style get endpoint puts everything in the query and sends no action`() {
        val transport = Fake { BtRaw(200, "application/json", """{"name":"nginx","version":"1.24"}""") }
        run(transport) {
            invoke(endpoint(kind = "p", verb = "GET", path = "/btdocker/container/get_list", name = "get_list"),
                mapOf("page" to "1", "size" to "20"))
        }
        val call = transport.calls.single()
        assertEquals("GET", call.verb)
        assertEquals("https://1.2.3.4:8888/abc/btdocker/container/get_list", call.url)
        // 签名照样要带，只是这一类接口动作已经在 URL 里了。
        assertEquals(setOf("request_time", "request_token", "page", "size"), call.form.keys)
        assertEquals("20", call.form["size"])
        assertFalse("路径式接口的动作已经在 URL 里，再塞一份反而会被面板判成非法参数", call.form.containsKey("action"))
    }

    @Test
    fun `blank params are dropped so the panel sees only what was filled`() {
        val transport = Fake { BtRaw(200, "application/json", "{}") }
        val params = listOf(BtParamSpec("id", 1), BtParamSpec("name"), BtParamSpec("tail"))
        run(transport) {
            invoke(endpoint(params = params), mapOf("id" to "3", "name" to "", "tail" to "  "))
        }
        assertEquals(setOf("request_time", "request_token", "action", "id"), transport.calls.single().form.keys)
    }

    @Test
    fun `signature keys can never be overwritten by the caller`() {
        val transport = Fake { BtRaw(200, "application/json", "{}") }
        run(transport) {
            invoke(
                endpoint(),
                mapOf("request_token" to "stolen", "request_time" to "1", "action" to "Evil", "id" to "3")
            )
        }
        val form = transport.calls.single().form
        assertEquals(BtPanelApi.sign(key, now), form["request_token"])
        assertEquals(now.toString(), form["request_time"])
        assertEquals("StopSite", form["action"])
        assertTrue(form.containsKey("id"))
    }

    @Test
    fun `a status false reply still surfaces the panel sentence`() {
        val transport = Fake { BtRaw(200, "application/json", """{"status":false,"msg":"IP不被允许"}""") }
        val failure = runCatching { run(transport) { invoke(endpoint(), emptyMap()) } }.exceptionOrNull()
        assertTrue(failure is BtException)
        assertEquals(BtIssue.IpBlocked, (failure as BtException).issue)
        assertEquals("IP不被允许", failure.message)
        assertTrue(failure.fromPanel)
    }
}
