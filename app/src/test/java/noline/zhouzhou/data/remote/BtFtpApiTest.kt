package noline.zhouzhou.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import noline.zhouzhou.data.model.BtPanelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FTP 模块的请求形状单测。
 *
 * 这一模块最值得钉住的是「面板做不到什么」：备注和根目录没有能写的 action、
 * 日志开关按不动、批量改密码回的是没有信封的裸数组。测试把这些都当成契约来守，
 * 免得下一次有人「顺手加个改备注的按钮」。
 */
class BtFtpApiTest {

    private class Fake(val reply: (BtRequest) -> BtRaw) : BtTransport {
        val calls = mutableListOf<BtRequest>()
        override suspend fun execute(request: BtRequest): BtRaw {
            calls += request
            return reply(request)
        }
    }

    private val key = "0123456789abcdef0123456789abcdef"
    private val config = BtPanelConfig(site = "https://1.2.3.4:8888/abc", key = key)
    private val ok = """{"status":true,"msg":"操作成功","data":true}"""

    private fun <T> run(transport: BtTransport, block: suspend BtFtpApi.() -> T): T =
        runBlocking {
            val panel = BtPanelApi(config, transport) { 1700000000L }
            BtFtpApi(panel).block()
        }

    private fun json(body: String) = BtRaw(200, "application/json; charset=utf-8", body)

    @Test
    fun `account list is a data table request and carries no search parameter`() {
        val transport = Fake { json("""{"status":true,"msg":"","data":[],"totalPages":1}""") }
        run(transport) { users(3, 30) }
        val call = transport.calls.single()
        assertEquals("https://1.2.3.4:8888/abc/data", call.url)
        assertEquals("ftps", call.form["table"])
        assertEquals("3", call.form["p"])
        assertFalse(call.form.containsKey("search.value"))
        assertFalse(call.form.containsKey("sort"))
    }

    @Test
    fun `add user uses the ftp prefixed key names`() {
        val transport = Fake { json("""{"status":true,"msg":"添加成功！"}""") }
        val msg = run(transport) {
            addUser("aa_test_ftp", "Ab2345678901", "/www/wwwroot/aa_test", "自测")
        }
        val form = transport.calls.single().form
        assertEquals("https://1.2.3.4:8888/abc/ftp", transport.calls.single().url)
        assertEquals("AddUser", form["action"])
        assertEquals("aa_test_ftp", form["ftp_username"])
        assertEquals("Ab2345678901", form["ftp_password"])
        assertEquals("/www/wwwroot/aa_test", form["path"])
        assertEquals("自测", form["ps"])
        assertEquals("添加成功！", msg)
    }

    @Test
    fun `status is sent as the panel digit words not a boolean`() {
        val transport = Fake { json(ok) }
        run(transport) { setStatus(id = "2", username = "aa_test_ftp", enabled = false) }
        val form = transport.calls.single().form
        assertEquals("SetStatus", form["action"])
        assertEquals("0", form["status"])
        assertEquals("2", form["id"])
    }

    @Test
    fun `batch password sends a json array and the reply stays a bare array`() {
        val transport = Fake {
            json("""[{"ftp_username":"a","status":true},{"ftp_username":"b","status":false,"msg":"账户不存在"}]""")
        }
        val root = run(transport) {
            batchSetPasswords(listOf(Triple("1", "a", "Ab2345678901"), Triple("2", "b\"c", "Ab2345678902")))
        }
        val form = transport.calls.single().form
        assertEquals("BatchSetUserPassword", form["action"])
        // id 是裸数字、用户名带引号要转义，否则面板回一句 JSON 语法错。
        assertEquals(
            """[{"id":1,"ftp_username":"a","new_password":"Ab2345678901"},{"id":2,"ftp_username":"b\"c","new_password":"Ab2345678902"}]""",
            form["data"]
        )
        assertEquals(2, root.jsonArray.size)
        assertFalse(root.jsonArray[1].jsonObject["status"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a non numeric id is quoted instead of breaking the json`() {
        val transport = Fake { json("[]") }
        run(transport) { batchSetPasswords(listOf(Triple("x1", "a", "Ab2345678901"))) }
        assertEquals("""[{"id":"x1","ftp_username":"a","new_password":"Ab2345678901"}]""", transport.calls.single().form["data"])
    }

    @Test
    fun `log state only ever asks because the switch does nothing`() {
        val transport = Fake { json("""{"status":true,"msg":"stop"}""") }
        assertEquals("stop", run(transport) { logsState() })
        val form = transport.calls.single().form
        assertEquals("set_ftp_logs", form["action"])
        assertEquals("getlog", form["exec_name"])
    }

    @Test
    fun `logs are paged per username`() {
        val transport = Fake { json("""{"page":"","data":[]}""") }
        run(transport) { loginLogs("aa_test_ftp", 1, 30) }
        run(transport) { actionLogs("aa_test_ftp", 2, 30) }
        val first = transport.calls[0].form
        assertEquals("get_login_logs", first["action"])
        assertEquals("aa_test_ftp", first["user_name"])
        assertEquals("1", first["p"])
        assertEquals("get_action_logs", transport.calls[1].form["action"])
        assertEquals("2", transport.calls[1].form["p"])
    }

    @Test
    fun `access limits go out with the units the panel parses`() {
        val transport = Fake { json("""{"status":true,"msg":"修改成功","data":true}""") }
        val msg = run(transport) { setAccess("aa_test_ftp", "0KB", "100KB", "1GB") }
        val form = transport.calls.single().form
        assertEquals("ModifyFtpUserAccess", form["action"])
        assertEquals("aa_test_ftp", form["username"])
        assertEquals("0KB", form["download_bandwidth"])
        assertEquals("100KB", form["upload_bandwidth"])
        assertEquals("1GB", form["max_size"])
        assertEquals("修改成功", msg)
    }

    @Test
    fun `delete user carries both id and username`() {
        val transport = Fake { json(ok) }
        run(transport) { deleteUser("2", "aa_test_ftp") }
        val form = transport.calls.single().form
        assertEquals("DeleteUser", form["action"])
        assertEquals("2", form["id"])
        assertEquals("aa_test_ftp", form["username"])
    }

    @Test
    fun `there is no remark or path edit because the panel has no such action`() {
        val methods = BtFtpApi::class.java.declaredMethods.map { it.name }
        // 实测六个候选名（ModifyFtp / SetFtpPath / UpdateFtp…）全回「指定参数无效!」，
        // 端口那一格会动整台服务，所以也不发。
        assertTrue(methods.none { it.contains("remark", ignoreCase = true) })
        assertTrue(methods.none { it.contains("path", ignoreCase = true) && it.startsWith("set") })
        assertTrue(methods.none { it.contains("setPort", ignoreCase = true) })
    }

    @Test
    fun `signature fields ride along on every ftp request`() {
        val transport = Fake { json(ok) }
        run(transport) { deleteUser("9", "z") }
        val form = transport.calls.single().form
        assertEquals("1700000000", form["request_time"])
        assertEquals(32, form["request_token"]!!.length)
    }
}
