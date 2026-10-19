package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.BtPanelConfig
import noline.zhouzhou.data.model.BtTable
import noline.zhouzhou.data.model.btNetwork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宝塔接口层单测。签名、模块路由、错误分诊这些必须在假响应上钉死；
 * 真实回包长什么样另有 BtPanelModelsTest，那份照 13.1.0 的实测原文写。
 */
class BtPanelApiTest {

    private class Fake(val reply: (BtRequest) -> BtRaw) : BtTransport {
        val calls = mutableListOf<BtRequest>()
        override suspend fun execute(request: BtRequest): BtRaw {
            calls += request
            return reply(request)
        }
    }

    private val key = "0123456789abcdef0123456789abcdef"
    private val config = BtPanelConfig(site = "https://1.2.3.4:8888/abc", key = key)

    private fun api(transport: BtTransport, now: Long = 1700000000L) =
        BtPanelApi(config, transport) { now }

    private fun json(body: String) = BtRaw(200, "application/json; charset=utf-8", body)

    private fun <T> run(transport: BtTransport, block: suspend BtPanelApi.() -> T): T =
        runBlocking { api(transport).block() }

    @Test
    fun `signature is md5 of timestamp plus md5 of key`() {
        // 两次 MD5、小写十六进制，顺序是「时间戳在前」——反过来的话面板直接判认证失败。
        assertEquals("2728e6791d8b6492a57cc2f99fa61a82", BtPanelApi.sign(key, 1700000000L))
        assertEquals("aab62f3a8fb5413d1a147dacb5aff3df", BtPanelApi.sign(key, 1L))
        assertEquals("8516ac99dc60603295de7bdb6a153530", BtPanelApi.md5Hex(key))
    }

    @Test
    fun `every request is a form post to the module url with fresh signature`() {
        val transport = Fake { json("""{"status":true,"msg":"","data":{"cpuStatus":"3"}}""") }
        run(transport) { systemTotal() }
        val call = transport.calls.single()
        assertEquals("https://1.2.3.4:8888/abc/system", call.url)
        assertEquals("1700000000", call.form["request_time"])
        assertEquals(BtPanelApi.sign(key, 1700000000L), call.form["request_token"])
        assertEquals("GetSystemTotal", call.form["action"])
    }

    @Test
    fun `site address keeps its security entry and trailing slash`() {
        assertEquals("https://x.work:8888/entry/system", BtPanelApi.moduleUrl("https://x.work:8888/entry/", BtModule.System))
        assertEquals("https://x.work/data", BtPanelApi.moduleUrl(" https://x.work ", BtModule.Data))
    }

    @Test
    fun `missing config never reaches the network`() {
        val transport = Fake { json("{}") }
        val error = assertThrows(BtException::class.java) {
            runBlocking { BtPanelApi(BtPanelConfig(), transport).systemTotal() }
        }
        assertEquals(BtIssue.NoConfig, error.issue)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `bare object without envelope is data itself`() {
        val transport = Fake { json("""{"up":"12.5","down":"1.2","recv_total":"3GB"}""") }
        val network = btNetwork(run(transport) { networkInfo() })
        assertEquals("12.5 KB/s", network.upText)
        assertEquals("1.2 KB/s", network.downText)
        // 认不出的键不能丢：面板版本一多，只有原样带出来才查得出版本差异。
        assertEquals(listOf("累计接收" to "3GB"), network.extras.map { it.label to it.value })
    }

    @Test
    fun `bare array crontab list gets rows with ids`() {
        val transport = Fake {
            json(
                """[{"id":3,"name":"备份网站","where1":"/www/wwwroot","cycle":"每小时","type":"minuteTask","url":""},
                    |{"id":7,"sName":"清理日志","job":"toShell","where1":"/www/wwwlogs"}]""".trimMargin()
            )
        }
        val rows = run(transport) { crontab() }
        assertEquals(2, rows.size)
        assertEquals("3", rows.first().id)
        assertEquals("备份网站", rows.first().title)
        assertEquals("每小时", rows.first().subtitle)
        // 第二条只给了 sName，认不出 name 也要有标题。
        assertEquals("清理日志", rows[1].title)
    }

    @Test
    fun `getData params carry table page and keyword`() {
        val transport = Fake { json("""{"status":true,"msg":"","data":{"rows":[],"records":7}}""") }
        val page = run(transport) { table(BtTable.Sites, keyword = "blog") }
        val form = transport.calls.single().form
        assertEquals("getData", form["action"])
        assertEquals("sites", form["table"])
        assertEquals("1", form["p"])
        assertEquals("true", form["filtered"])
        assertEquals("blog", form["search.value"])
        assertEquals(7, (page as BtPanelApi.TablePage).total)
    }

    @Test
    fun `total count read from the paging html beside data`() {
        // 13.1.0 实测：/data 不给 records，总条数只写在和 data 平级的 page 那串分页 HTML 里。
        val transport = Fake {
            json(
                """{"status":true,"msg":"","auto_list":false,
                   "page":"<span class='Pcurrent'>1</span><span class='Pcount'>共11970条</span>",
                   "data":[{"id":1,"log":"x","type":"API配置"}]}""".trimMargin()
            )
        }
        val page = run(transport) { table(BtTable.Logs) }
        assertEquals(11970, page.total)
    }

    @Test
    fun `status false keeps the panel sentence`() {
        val transport = Fake {
            json("""{"status":false,"msg":"当前IP地址[8.8.8.8]不允许使用接口,请检查接口设置!"}""")
        }
        val error = assertThrows(BtException::class.java) {
            runBlocking { api(transport).diskInfo() }
        }
        assertEquals(BtIssue.IpBlocked, error.issue)
        assertTrue(error.message.contains("不允许使用接口"))
        // 这句是面板写的，界面才敢标成「面板原话」。
        assertTrue(error.fromPanel)
    }

    @Test
    fun `msg can be an array of validation errors`() {
        val transport = Fake {
            json("""{"status":false,"msg":["站点名不能为空","域名格式不对"]}""")
        }
        val error = assertThrows(BtException::class.java) {
            runBlocking { api(transport).reloadPanel() }
        }
        assertEquals("站点名不能为空；域名格式不对", error.message)
    }

    @Test
    fun `status as string zero still counts as failure`() {
        val transport = Fake { json("""{"status":"0","msg":"接口已关闭"}""") }
        val error = assertThrows(BtException::class.java) {
            runBlocking { api(transport).networkInfo() }
        }
        assertEquals(BtIssue.ApiOff, error.issue)
    }

    @Test
    fun `html page means the address or the switch is wrong`() {
        val transport = Fake { BtRaw(404, "text/html; charset=utf-8", "<html><body>404</body></html>") }
        val error = assertThrows(BtException::class.java) {
            runBlocking { api(transport).systemTotal() }
        }
        assertEquals(BtIssue.HtmlPage, error.issue)
        assertTrue(error.message.contains("安全入口"))
        // 「安全入口」这句是我们自己的判断，不是面板说的。
        assertFalse(error.fromPanel)
    }

    @Test
    fun `service action sends name and operation type`() {
        val transport = Fake { json("""{"status":true,"msg":"服务重启成功"}""") }
        val text = run(transport) { service("php-fpm-82", BtServiceOp.Restart) }
        val form = transport.calls.single().form
        assertEquals("ServiceAdmin", form["action"])
        assertEquals("php-fpm-82", form["name"])
        assertEquals("restart", form["type"])
        assertEquals("服务重启成功", text)
    }

    @Test
    fun `write without a message still reports what happened`() {
        val transport = Fake { json("""{"status":true,"msg":""}""") }
        val text = run(transport) { service("nginx", BtServiceOp.Reload) }
        assertEquals("重载指令已经发给面板", text)
    }

    @Test
    fun `task log comes back as plain text from msg`() {
        val transport = Fake { json("""{"status":true,"msg":"2026-10-19 03:00 备份完成"}""") }
        val log = run(transport) { crontabLog("3") }
        assertEquals("2026-10-19 03:00 备份完成", log)
        assertEquals("GetLogs", transport.calls.single().form["action"])
    }

    @Test
    fun `issues always come with a next step`() {
        // 没有实测条件时，advice 是用户唯一的线索，任何一类都得给出一条。
        BtIssue.entries.forEach { issue ->
            assertTrue(issue.name, issue.advice.isNotBlank())
        }
    }

    @Test
    fun `classify common panel complaints`() {
        assertEquals(BtIssue.WrongKey, BtPanelApi.issueForMessage("request_token 校验失败"))
        assertEquals(BtIssue.WrongKey, BtPanelApi.issueForMessage("密钥不正确"))
        assertEquals(BtIssue.ClockSkew, BtPanelApi.issueForMessage("请求已经超时,请检查服务器时间"))
        assertEquals(BtIssue.IpBlocked, BtPanelApi.issueForMessage("当前IP[1.1.1.1]不支持此接口"))
        assertEquals(BtIssue.ApiOff, BtPanelApi.issueForMessage("API 接口未开启"))
        assertEquals(BtIssue.Unknown, BtPanelApi.issueForMessage("脚本执行失败"))
    }
}
