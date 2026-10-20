package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向代理读模型的单测。
 *
 * 这里钉的几件事：面板的回包壳层比文档写得乱（列表外面裹了一层 `data`，域名一口给
 * `domain_list` 另一口直接给数组）；「开关」有两种完全不同的写法（列表的 `status` 是字符串
 * `"1"`，全局设置的 `websocket_status` 是布尔）；文档说 `"0"` / `"1"` 的缓存那一格，真面板给的是
 * 布尔；以及**认不出来的一律留 null**，不猜成 false——猜错一次，界面上就把一条正在跑的代理说成停用的。
 */
class BtReverseProxyModelsTest {

    private fun element(body: String) = Json.parseToJsonElement(body)

    /** 面板转发时 Host 那一格的默认值就是 nginx 变量，测试里也照它写。 */
    private val hostVar = "\$http_host"

    /** 13.1.0 实测的列表形状：总数只在 `page` 那串分页 HTML 里。 */
    private val listBody = """
        {
          "status": true,
          "msg": "",
          "data": {
            "where": "",
            "page": "<span class='p'>共2条</span>",
            "data": [
              {"id":"1","name":"api.example.com","path":"/www/wwwroot/api.example.com","status":"1",
               "ps":"接口转发","addtime":"2026-01-02 03:04:05","edate":"0000-00-00","rname":""},
              {"id":"2","name":"old.example.com","path":"/www/wwwroot/old","status":"0","ps":"",
               "addtime":"2025-11-01 00:00:00","edate":"2026-11-01","site_id":"9"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `the list envelope is unwrapped and the total comes out of the pagination html`() {
        val rows = btReverseProxyEntries(element(listBody))
        assertEquals(2, rows.size)
        assertEquals(2, btTotal(element(listBody)))

        val first = rows.first()
        assertEquals("1", first.id)
        assertEquals("api.example.com", first.name)
        assertEquals(true, first.enabled)
        assertEquals("接口转发", first.remark)
        assertEquals("", first.renamed)

        val second = rows[1]
        assertEquals(false, second.enabled)
        assertEquals("old.example.com", second.title)
        // 文档没列过的那一格不当成不存在，摊进 extraKeys。
        assertEquals(listOf("site_id"), second.extraKeys)
    }

    @Test
    fun `a status the model has never seen stays unknown instead of becoming off`() {
        val rows = btReverseProxyEntries(element("""[{"id":"3","name":"x.example.com","status":"ing"}]"""))
        assertEquals("ing", rows.single().statusText)
        assertNull(rows.single().enabled)
    }

    @Test
    fun `rules keep both the flat fields and the nested switch objects`() {
        val rules = btReverseProxyRules(
            element(
                """
                [
                  {"proxy_type":"http","proxy_path":"/api","proxy_pass":"http://127.0.0.1:8080",
                   "proxy_host":"$hostVar","remark":"自测",
                   "websocket":{"websocket_status":true},"gzip":{"gzip_status":false},"header":[{"proxy_header":"Host"}]}
                ]
                """.trimIndent()
            )
        )
        val rule = rules.single()
        assertEquals("/api", rule.proxyPath)
        assertEquals("http://127.0.0.1:8080", rule.proxyPass)
        assertEquals(hostVar, rule.proxyHost)
        assertEquals(true, rule.websocketEnabled)
        assertEquals(false, rule.gzipEnabled)
        assertEquals(listOf("header"), rule.extraKeys)
    }

    @Test
    fun `a boolean written as a string inside the nested object still reads as on`() {
        val rules = btReverseProxyRules(element("""[{"proxy_path":"/a","websocket":{"websocket_status":"1"}}]"""))
        assertEquals(true, rules.single().websocketEnabled)
        // 整格缺失是「不知道」，不是「关」。
        assertNull(rules.single().gzipEnabled)
    }

    @Test
    fun `global conf reads the documented blocks and leaves the rest as extra sections`() {
        val conf = btReverseProxyConf(
            element(
                """
                {
                  "domain_list": ["api.example.com:80"],
                  "ssl_info": {"ssl_status": true, "private_key": "SECRET-DO-NOT-SHOW"},
                  "gzip": {"gzip_status": true},
                  "websocket": {"websocket_status": false},
                  "proxy_log": {"log_type": "file", "path": "/www/wwwlogs/proxy.log"},
                  "proxy_cache": {"cache_status": 1, "expires": "1d"},
                  "basic_auth": {"status": 0},
                  "ip_limit": {}
                }
                """.trimIndent()
            )
        )
        assertEquals(listOf("api.example.com:80"), conf.domainList)
        assertEquals(true, conf.sslEnabled)
        assertEquals(true, conf.gzipEnabled)
        assertEquals(false, conf.websocketEnabled)
        assertEquals("file", conf.logType)
        assertEquals(true, conf.cacheEnabled)
        assertEquals("1d", conf.cacheExpires)
        assertEquals(listOf("basic_auth", "ip_limit", "proxy_cache"), conf.extraSections)
    }

    /**
     * 13.1.0 实测：`proxy_cache.cache_status` 给的是布尔 `false`，不是文档写的 `"0"` / `"1"`。
     * 「面板给了缓存这一格」不等于「缓存开着」——只按存在与否判，界面上就会把关着的缓存说成开着。
     */
    @Test
    fun `the real panel writes cache status as a plain boolean false`() {
        val conf = btReverseProxyConf(
            element(
                """
                {
                  "domain_list": ["api.example.com:80"],
                  "proxy_cache": {"cache_status": false, "expires": "1d", "cache_dir": "/www/server/nginx/proxy_cache_dir"},
                  "primary_port": "80"
                }
                """.trimIndent()
            )
        )
        assertEquals(false, conf.cacheEnabled)
        assertEquals("1d", conf.cacheExpires)
        // 整格缺失仍然是「不知道」，界面上不显示状态、只显示按钮。
        assertNull(btReverseProxyConf(element("""{"domain_list": []}""")).cacheEnabled)
    }

    /** 那一口哪天回的不是对象（面板偶尔回一句字符串或裸数组），不能把整页拖崩。 */
    @Test
    fun `a conf that is not an object degrades to empty instead of throwing`() {
        val conf = btReverseProxyConf(element("""[" unexpected "]"""))
        assertEquals(emptyList<String>(), conf.domainList)
        assertNull(conf.sslEnabled)
        assertTrue(conf.raw.isEmpty())
    }

    @Test
    fun `domains come from either the nested list or a bare array`() {
        val nested = btReverseProxyDomains(
            element("""{"domain_list":[{"name":"api.example.com","port":"80","healthy":"1"}]}""")
        )
        assertEquals("api.example.com:80", nested.single().title)
        assertEquals(true, nested.single().healthy)

        val bare = btReverseProxyDomains(element("""[{"name":"a.example.com","port":"443","healthy":"0"}]"""))
        assertEquals("a.example.com:443", bare.single().title)
        assertEquals(false, bare.single().healthy)

        // healthy 只给过 1；别的写法留成 null，界面上摊原文而不是说「不通」。
        val odd = btReverseProxyDomains(element("""[{"name":"b.example.com","healthy":"checking"}]"""))
        assertNull(odd.single().healthy)
        assertEquals("b.example.com", odd.single().title)
    }

    @Test
    fun `the access log keeps both the body and the panel's own size text`() {
        val log = btReverseProxyLog(
            element("""{"msg":"10.0.0.1 - - [20/Oct/2026] GET /api 200\n","size":"12.30 KB"}""")
        )
        assertTrue(log.text.contains("GET /api"))
        assertEquals("12.30 KB", log.size)

        val empty = btReverseProxyLog(element("""{"status":true,"msg":"日志为空"}"""))
        // 正文装在 data.msg；这一口直接回在信封上时，读到的还是那句提示，不假装是日志内容。
        assertEquals("日志为空", empty.text)
        assertEquals("", empty.size)
    }

    @Test
    fun `the engine wire is the path segment and an unknown one falls back to nginx`() {
        assertEquals("openresty", BtProxyEngine.Openresty.wire)
        assertEquals(BtProxyEngine.Apache, BtProxyEngine.from("apache"))
        assertEquals(BtProxyEngine.Nginx, BtProxyEngine.from(""))
        assertEquals(BtProxyEngine.Nginx, BtProxyEngine.from("httpd"))
    }

    @Test
    fun `the form blocks everything the panel would reject`() {
        assertEquals(null, BtReverseProxyForm("api.example.com", "http://127.0.0.1:8080").problem())
        assertTrue(BtReverseProxyForm(domains = "", proxyPass = "http://127.0.0.1:1").problem()!!.contains("域名"))
        assertTrue(BtReverseProxyForm("*.example.com", "http://127.0.0.1:1").problem()!!.contains("泛解析"))
        assertTrue(BtReverseProxyForm("api.example.com", "").problem()!!.contains("目标地址"))
        assertTrue(BtReverseProxyForm("api.example.com", "127.0.0.1:8080").problem()!!.contains("http://"))
        assertTrue(BtReverseProxyForm("api.example.com", "https://127.0.0.1:8080").canSubmit)
        assertTrue(BtReverseProxyForm("api.example.com", "http://127.0.0.1:1", proxyPath = "api").problem()!!.contains("/"))
        // 空路径当作没填，面板按 / 处理。
        assertTrue(BtReverseProxyForm("api.example.com", "http://127.0.0.1:1", proxyPath = "").canSubmit)
    }

    @Test
    fun `proxy_type only knows http and unix, and unix wants a socket path`() {
        // 文档 create / add_proxy 两页都写死了：这一格是 http 或 unix，没有 websocket 那一档。
        assertTrue(BtReverseProxyForm("api.example.com", "/run/x.sock", proxyType = "unix").canSubmit)
        assertTrue(
            BtReverseProxyForm("api.example.com", "http://127.0.0.1:1", proxyType = "unix")
                .problem()!!.contains("socket")
        )
    }

    @Test
    fun `an entry with no name falls back to its directory for the title`() {
        val rows = btReverseProxyEntries(element("""[{"id":"4","path":"/www/wwwroot/only-dir","status":"1"}]"""))
        assertEquals("/www/wwwroot/only-dir", rows.single().title)
        assertFalse(rows.single().name.isNotBlank())
    }
}
