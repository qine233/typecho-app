package noline.zhouzhou.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import noline.zhouzhou.data.model.BtPanelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库模块的请求形状单测。每一条都钉着一个正式面板 13.1.0 上实测出来的坑：
 * 键名拼错就回「指定参数无效!」，多带一个 sort 就回空数组，
 * `set_restart_task` 压根不看状态参数，`DelBackup` 干脆是只说谎不干活。
 */
class BtDatabaseApiTest {

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

    private fun <T> run(transport: BtTransport, block: suspend BtDatabaseApi.() -> T): T =
        runBlocking {
            val panel = BtPanelApi(config, transport) { 1700000000L }
            BtDatabaseApi(panel).block()
        }

    private fun json(body: String) = BtRaw(200, "application/json; charset=utf-8", body)

    @Test
    fun `database list is fetched from the data table without any search parameter`() {
        val transport = Fake { json("""{"status":true,"msg":"","data":[],"totalPages":1}""") }
        run(transport) { databases(1, 30) }
        val call = transport.calls.single()
        // 这一张表只有 /data 一个口，直接问 /database 的列表拿不到东西。
        assertEquals("https://1.2.3.4:8888/abc/data", call.url)
        assertEquals("getData", call.form["action"])
        assertEquals("databases", call.form["table"])
        assertEquals("1", call.form["p"])
        assertEquals("30", call.form["limit"])
        // search.value 对这张表是摆设，宁可不发，也别让界面以为面板筛过了。
        assertFalse(call.form.containsKey("search.value"))
    }

    @Test
    fun `native list and host list use the id key while the others use sid`() {
        val transport = Fake { json(ok) }
        run(transport) {
            nativeDatabases("0")
            userHosts("0")
            panelGrants("0")
            tables("0", "aa_test_db")
        }
        val byAction = transport.calls.associate { it.form["action"]!! to it.form }
        // GetDatabaseList 和 GeUserHostList 这一对面板吃的是 id，另一个吃 sid；抄错就回空。
        assertEquals("0", byAction["GetDatabaseList"]!!["id"])
        assertFalse(byAction["GetDatabaseList"]!!.containsKey("sid"))
        assertEquals("0", byAction["GeUserHostList"]!!["id"])
        assertEquals("0", byAction["GetDatabasesList"]!!["sid"])
        assertEquals("0", byAction["get_database_table"]!!["sid"])
        assertEquals("aa_test_db", byAction["get_database_table"]!!["name"])
    }

    @Test
    fun `the user host action name keeps the missing t the panel itself shipped`() {
        val transport = Fake { json(ok) }
        run(transport) { userHosts("0") }
        assertEquals("GeUserHostList", transport.calls.single().form["action"])
    }

    @Test
    fun `flat status actions return the whole tree because they have no data envelope`() {
        val transport = Fake { json("""{"data_size":"0.02 MB","database":"aa_test_db","tables":[]}""") }
        val root = run(transport) { info("aa_test_db") }
        assertEquals("aa_test_db", root.jsonObject["database"]?.jsonPrimitive?.content)
        assertEquals("GetInfo", transport.calls.single().form["action"])
        assertEquals("aa_test_db", transport.calls.single().form["db_name"])
    }

    @Test
    fun `install status reads the sentence out of the msg slot`() {
        val transport = Fake { json("""{"status":true,"msg":"已安装 MySQL-8.0","data":true}""") }
        assertEquals("已安装 MySQL-8.0", run(transport) { installStatus() })
    }

    @Test
    fun `add database uses the panel key names and never associates a site`() {
        val transport = Fake { json("""{"status":true,"msg":"添加成功！"}""") }
        val msg = run(transport) {
            addDatabase(
                sid = "0", name = "aa_test_db", user = "aa_test_db",
                password = "Ab23456789012345", address = "127.0.0.1",
                charset = "utf8mb4", remark = "自测库"
            )
        }
        val form = transport.calls.single().form
        assertEquals("AddDatabase", form["action"])
        assertEquals("aa_test_db", form["name"])
        // 用户名这一格面板叫 db_user，密码叫 password，字符集叫 codeing——三个都得照抄。
        assertEquals("aa_test_db", form["db_user"])
        assertEquals("Ab23456789012345", form["password"])
        assertEquals("127.0.0.1", form["address"])
        assertEquals("utf8mb4", form["codeing"])
        assertEquals("自测库", form["ps"])
        assertEquals("0", form["pid"])
        assertEquals("添加成功！", msg)
    }

    @Test
    fun `a panel rejection surfaces as the panel sentence not a silent success`() {
        val transport = Fake { json("""{"status":false,"msg":"指定参数无效!"}""") }
        val error = runCatching { run(transport) { backup("3", "0") } }.exceptionOrNull()
        assertTrue(error is BtException)
        assertEquals("指定参数无效!", (error as BtException).message)
    }

    @Test
    fun `delete database carries both the id and the name the panel checks`() {
        val transport = Fake { json(ok) }
        run(transport) { deleteDatabase(id = "3", sid = "0", name = "aa_test_db", user = "aa_test_db") }
        val form = transport.calls.single().form
        assertEquals("DeleteDatabase", form["action"])
        assertEquals("3", form["id"])
        assertEquals("aa_test_db", form["name"])
        assertEquals("aa_test_db", form["db_user"])
    }

    @Test
    fun `add mysql user sends the address twice under both names the panel wants`() {
        val transport = Fake { json(ok) }
        run(transport) { addMysqlUser("0", "aa_test_u", "Ab23456789012345", "127.0.0.1") }
        val form = transport.calls.single().form
        assertEquals("AddMysqlUser", form["action"])
        assertEquals("127.0.0.1", form["address"])
        assertEquals("127.0.0.1", form["host"])
        assertEquals("aa_test_u", form["username"])
        assertEquals("aa_test_u", form["db_user"])
    }

    @Test
    fun `set timeout uses the four mysql variable names as form keys`() {
        val transport = Fake { json(ok) }
        run(transport) {
            setTimeout(
                interactiveSeconds = "180",
                waitSeconds = "180",
                expireLogDays = "6",
                passwordLifetimeDays = "0"
            )
        }
        val form = transport.calls.single().form
        assertEquals("SetTimeOut", form["action"])
        assertEquals("180", form["interactive_timeout"])
        assertEquals("180", form["wait_timeout"])
        // 这一格会连带把 binlog_expire_logs_seconds 按天重算，界面上必须说清（实测 600000→518400）。
        assertEquals("6", form["expire_logs_days"])
        assertEquals("0", form["default_password_lifetime"])
        assertFalse(form.containsKey("binlog_expire_logs_seconds"))
    }

    @Test
    fun `guard enable sends no status at all because the panel ignores it`() {
        val transport = Fake { json(ok) }
        run(transport) { enableGuard() }
        val form = transport.calls.single().form
        assertEquals("set_restart_task", form["action"])
        // 实测带 status=0 发过去同样是「建任务」，所以这一条只能当「开启」用。
        assertFalse(form.containsKey("status"))
    }

    @Test
    fun `guard disable goes through the crontab module`() {
        val transport = Fake { json(ok) }
        run(transport) { deleteGuardTask("12") }
        val call = transport.calls.single()
        assertEquals("https://1.2.3.4:8888/abc/crontab", call.url)
        assertEquals("DelCrontab", call.form["action"])
        assertEquals("12", call.form["id"])
    }

    @Test
    fun `there is no delete-backup call because the panel action only claims success`() {
        val methods = BtDatabaseApi::class.java.declaredMethods.map { it.name }
        // DelBackup 实测无论带什么都回「备份已删除！」而磁盘文件一个不少，所以这里不给这只假手。
        assertFalse(methods.any { it.contains("DelBackup", ignoreCase = true) })
        assertFalse(methods.any { it.contains("deleteBackup", ignoreCase = true) })
        assertFalse(methods.any { it.contains("import", ignoreCase = true) })
    }

    @Test
    fun `signature fields are always present and never mixed into the action params`() {
        val transport = Fake { json(ok) }
        run(transport) { clearBinlogs("7") }
        val form = transport.calls.single().form
        assertEquals("1700000000", form["request_time"])
        assertEquals(32, form["request_token"]!!.length)
        assertEquals("7", form["days"])
    }

    @Test
    fun `log bodies that come back as a plain string still reach the caller`() {
        val transport = Fake { json("\"2026-10-20T07:00:00.1Z 1 [Note] ready\"") }
        val root = run(transport) { errorLog() }
        assertTrue(root is JsonPrimitive)
        assertEquals("2026-10-20T07:00:00.1Z 1 [Note] ready", root.jsonPrimitive.content)
    }

    @Test
    fun `cloud server list comes back as a bare array`() {
        val transport = Fake { json("""[{"id":"0","db_host":"localhost","db_port":"3306","db_user":"root","db_password":"x","ps":"本地服务器"}]""") }
        val root = run(transport) { servers() }
        val first = root.jsonArray.first().jsonObject
        assertEquals("localhost", first["db_host"]?.jsonPrimitive?.content)
        assertEquals("3306", first["db_port"]?.jsonPrimitive?.content)
        // 网络层照收整棵树；模型层（BtServerOption）里没有能装下 db_password 的字段，
        // 这一格到底漏没漏，由 BtDbModelsTest 那条测试钉住。
    }
}
