package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库 / FTP 两个模块的读模型单测。
 *
 * 这里的每份 JSON 都是照正式面板 13.1.0 的实测回包抄的形状（见记忆「宝塔 13.1.0 实测回包形状」
 * 与「网站模块 74 端点实测表」同源的探测记录）。两条最要紧的性质单独钉住：
 * 明文密码在模型里无处可放，以及面板那些没信封的平铺对象照样读得动。
 */
class BtDbModelsTest {

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    // ── 列表：密码必须一个字节都留不下 ──

    @Test
    fun `database row drops the plaintext password the panel ships in every line`() {
        val rows = btDatabases(
            parse(
                """[{"id":3,"name":"aa_test_db","username":"aa_test_db","accept":"127.0.0.1",
                    |"db_password":"Sup3rSecret!","password":"Sup3rSecret!","ps":"测试库",
                    |"addtime":"2026-10-20 15:00:00","sid":0,"type":"MySQL","backup_count":2,
                    |"pid":"0","charset":"utf8","quota":{"used":16384,"size":0}}]""".trimMargin()
            )
        )
        val db = rows.single()
        assertEquals("aa_test_db", db.name)
        assertEquals("3", db.id)
        assertEquals("aa_test_db@127.0.0.1", db.access)
        assertEquals("测试库", db.remark)
        assertEquals(2, db.backupCount)
        assertEquals(16384L, db.usedBytes)
        assertEquals("0", db.sid)
        // 模型里没有能装下密码的字段，toString 也就绝不可能把它带出来。
        assertFalse(db.toString().contains("Sup3rSecret"))
        assertFalse(db.subtitle.contains("Sup3rSecret"))
        assertTrue(db.subtitle.contains("占用 16.0 KB"))
        assertTrue(db.subtitle.contains("备份 2 份"))
    }

    @Test
    fun `a line with neither name nor id is not a row`() {
        assertTrue(btDatabases(parse("""[{"ps":"面板自己塞的一行说明"}]""")).isEmpty())
    }

    @Test
    fun `the panel placeholder remark does not become a remark`() {
        // 实测：建库时备注留空，面板把输入框的占位词「填写备注」原样存进了 ps。
        val db = btDatabases(parse("""[{"id":1,"name":"aa_test_ui","ps":"填写备注"}]""")).single()
        assertEquals("", db.remark)
        val ftp = btFtpUsers(parse("""[{"id":1,"name":"aa_test_ftp","ps":"填写备注"}]""")).single()
        assertEquals("", ftp.remark)
    }

    @Test
    fun `missing quota and engine fall back instead of inventing zero`() {
        val db = btDatabases(parse("""[{"id":9,"name":"only_name"}]""")).single()
        assertNull(db.usedBytes)
        assertEquals("MySQL", db.engine)
        assertEquals("0", db.sid)
        assertEquals("", db.subtitle)
    }

    @Test
    fun `ftp row keeps status end time and quota but never the password`() {
        val users = btFtpUsers(
            parse(
                """[{"id":2,"name":"aa_test_ftp","path":"/www/wwwroot/aa","ps":"备注",
                    |"addtime":"2026-10-20 14:00:00","status":"0","end_time":"0",
                    |"password":"FtpPlainPass","quota":{"used":1024,"size":10485760}}]""".trimMargin()
            )
        )
        val ftp = users.single()
        assertEquals("2", ftp.id)
        assertFalse(ftp.enabled)
        assertEquals("永久", ftp.endAt)
        assertEquals("1.0 KB / 10.0 MB", ftp.quotaText)
        assertTrue(ftp.subtitle.contains("/www/wwwroot/aa"))
        assertFalse(ftp.toString().contains("FtpPlainPass"))
    }

    @Test
    fun `an unlimited quota says so instead of showing zero bytes`() {
        val ftp = btFtpUsers(parse("""[{"id":1,"name":"x","quota":{"used":0,"size":0}}]""")).single()
        assertEquals("空间不限量", ftp.quotaText)
    }

    // ── 详情：面板那些没信封的回包 ──

    @Test
    fun `GetInfo is a bare object and its table list carries size and rows`() {
        val info = btDbInfo(
            parse(
                """{"data_size":"0.02 MB","database":"aa_test_db",
                    |"tables":[{"table_name":"count_todos","type":"InnoDB","rows_count":0,
                    |"collation":"utf8mb4_general_ci","data_size":"0.02 MB","o_size":"0.05 MB","comment":""}]}""".trimMargin()
            )
        )
        assertEquals("aa_test_db", info.name)
        assertEquals("0.02 MB", info.sizeText)
        val table = info.tables.single()
        assertEquals("count_todos", table.name)
        assertEquals("InnoDB · utf8mb4_general_ci · 0 行 · 数据 0.02 MB", table.detail)
    }

    @Test
    fun `SHOW TABLES style nested arrays and flat string arrays both parse`() {
        assertEquals(listOf("count_todos"), btNestedNames(parse("""[["count_todos"]]""")))
        assertEquals(listOf("aa_test_db", "mysql"), btNestedNames(parse("""["aa_test_db","mysql"]""")))
        assertTrue(btNestedNames(parse("""{"data":[]}""")).isEmpty())
    }

    @Test
    fun `grants come from the name and tb_list pair`() {
        val grants = btDbGrants(
            parse("""[{"name":"aa_test_db","value":"aa_test_db","tb_list":[{"name":"count_todos","value":"SELECT"}]}]""")
        )
        val grant = grants.single()
        assertEquals("aa_test_db", grant.database)
        assertEquals(listOf("count_todos"), grant.tables)
    }

    @Test
    fun `the global grant row keys on the star not the panel chinese label`() {
        // 实测这台面板把全局那一行的 name 翻成了「全局权限」，只有 value 还是 `*`。
        val grants = btDbGrants(
            parse("""[{"name":"全局权限","value":"*","tb_list":[]},
                |{"name":"blog","value":"blog","tb_list":[{"name":"所有","value":"*"},
                |{"name":"config","value":"config"}]}]""".trimMargin())
        )
        assertEquals("*", grants.first().database)
        assertEquals(emptyList<String>(), grants.first().tables)
        assertEquals(listOf("所有", "config"), grants[1].tables)
    }

    @Test
    fun `account hosts translate the panel words for password presence and hide the value`() {
        val accounts = btMysqlAccounts(
            parse(
                """[{"user":"aa_test_db","list":[{"host":"127.0.0.1","password":"有",
                    |"password_last_changed":"2026-10-20 15:00:00",
                    |"access_list":[{"database":"aa_test_db","access":["ALL PRIVILEGES"]}]}]}]""".trimMargin()
            )
        )
        val host = accounts.single().hosts.single()
        assertEquals("127.0.0.1", host.host)
        assertEquals(true, host.hasPassword)
        assertEquals("2026-10-20 15:00:00", host.passwordChanged)
        assertEquals(listOf("aa_test_db ALL PRIVILEGES"), host.grants)
        assertFalse(accounts.toString().contains("有密码")) // 模型只存布尔，界面才翻成中文
    }

    @Test
    fun `an unknown password marker stays unknown rather than becoming no-password`() {
        val host = btMysqlAccounts(parse("""[{"user":"u","list":[{"host":"%","password":"*MD5*…"}]}]"""))
            .single().hosts.single()
        assertNull(host.hasPassword)
    }

    @Test
    fun `user host list is a bare array of user-at-host sentences`() {
        assertEquals(
            listOf("root@localhost", "aa_test_db@127.0.0.1"),
            btUserHosts(parse("""["root@localhost","aa_test_db@127.0.0.1"]"""))
        )
    }

    @Test
    fun `backups accept both the measured byte size and the documented human size`() {
        val measured = btDbBackups(
            parse("""{"data":[{"name":"aa_test_db_20261020.sql.gz","path":"/www/backup/database/mysql/aa_test_db/a.sql.gz","size":4096,"mtime":1760000000}]}""")
        ).single()
        assertEquals("4.0 KB", measured.humanSize)
        assertTrue(measured.timeText.isNotBlank())

        val documented = btDbBackups(
            parse("""{"data":[{"name":"b.sql.gz","size":"1.5 KB","addtime":"2026-10-20 16:00:00"}]}""")
        ).single()
        assertEquals("1.5 KB", documented.humanSize)
        assertEquals("2026-10-20 16:00:00", documented.timeText)
    }

    @Test
    fun `binlog current marker and server info read the flat keys`() {
        val binlog = btBinlogFiles(parse("""{"data":[{"name":"mysql-bin.000012","path":"/www/server/data/mysql-bin.000012","size":602,"current":true}]}""")).single()
        assertTrue(binlog.current)
        assertEquals(602L, binlog.sizeBytes)

        val server = btMysqlServer(parse("""{"datadir":"/www/server/data","port":"3306"}"""))
        assertEquals("/www/server/data", server.dataDir)
        assertEquals("3306", server.port)
    }

    @Test
    fun `timeout keeps every格 separate so a missing one can stay unfilled`() {
        val timeout = btMysqlTimeout(
            parse("""{"interactive_timeout":"180","wait_timeout":"180","binlog_expire_logs_seconds":"518400","default_password_lifetime":"0"}""")
        )
        assertEquals(180, timeout.interactiveSeconds)
        assertEquals(518400, timeout.binlogExpireSeconds)
        assertNull(timeout.expireLogDays)
        assertEquals(
            listOf("交互连接超时", "非交互连接超时", "Binlog 保留", "密码有效期"),
            timeout.rows.map { it.label }
        )
        assertEquals("6 天（518400 秒）", timeout.rows[2].value)
        assertEquals("永不过期", timeout.rows[3].value)
    }

    @Test
    fun `guard task reads both the enabled object and the never-configured status zero`() {
        val on = btGuardTask(parse("""{"status":true,"msg":{"id":12,"name":"[勿删]MySQL守护进程","status":1,"where1":"5"}}"""))
        assertTrue(on.enabled)
        assertEquals("12", on.taskId)
        assertEquals("5", on.everyMinutes)

        val off = btGuardTask(parse("""{"status":0}"""))
        assertFalse(off.enabled)
        assertEquals("", off.taskId)
    }

    @Test
    fun `login guard uses the panel status string not the envelope boolean`() {
        val off = btLoginGuard(parse("""{"status":"off"}"""))
        assertFalse(off.enabled)
        val on = btLoginGuard(
            parse("""{"status":"on","connection_control_failed_connections_threshold":"10","connection_control_min_connection_delay":"1000","connection_control_max_connection_delay":"60000"}""")
        )
        assertTrue(on.enabled)
        assertEquals(10, on.threshold)
        assertEquals(60000, on.maxDelayMs)
    }

    @Test
    fun `log body accepts a bare string, a msg-wrapped body and a path-only reply`() {
        assertEquals("2026-10-20T07:00:00.1Z 1 [Note] ready", btLogBody(parse("\"2026-10-20T07:00:00.1Z 1 [Note] ready\"")))
        assertEquals("slow body", btLogBody(parse("""{"status":true,"msg":"slow body"}""")))
        assertEquals("/www/server/data/mysqld.err", btLogBody(parse("""{"log":"/www/server/data/mysqld.err"}""")))
    }

    @Test
    fun `runtime counters pick the readable ones and compute the buffer pool hit rate`() {
        val fields = btMysqlRuntime(
            parse(
                """{"Uptime":"123456","Questions":"900","Innodb_row_reads":"12",
                    |"Innodb_buffer_pool_read_requests":"1000","Innodb_buffer_pool_reads":"20"}""".trimMargin()
            )
        )
        assertEquals("123456", fields.first { it.label.contains("运行") }.value)
        assertEquals("98.0%", fields.last { it.label.contains("命中率") }.value)
        // 两个计数器缺一个就不出这一行，别拿半个数据算成 100%。
        val partial = btMysqlRuntime(parse("""{"Innodb_buffer_pool_read_requests":"1000"}"""))
        assertTrue(partial.none { it.label.contains("命中率") })
    }

    @Test
    fun `memory parameters come back under the panel mem key with its own units`() {
        val fields = btMysqlMemory(parse("""{"mem":{"innodb_buffer_pool_size":"128","max_connections":"800"}}"""))
        assertEquals("InnoDB 缓冲池（MB）", fields.first { it.value == "128" }.label)
        assertEquals("800", fields.first { it.label == "最大连接数" }.value)
    }

    @Test
    fun `cloud servers keep address parts but never the stored db password`() {
        val servers = btServerOptions(
            parse("""[{"id":"0","db_host":"localhost","db_port":"3306","db_user":"root","db_password":"","ps":"本地服务器"}]""")
        )
        val local = servers.single()
        assertTrue(local.isLocal)
        assertEquals("localhost:3306", local.address)
        assertFalse(servers.toString().contains("db_password"))
    }

    @Test
    fun `ftp log lines flatten both the array shape and the grouped shape and hide secrets`() {
        val flat = btFtpLogLines(
            parse("""{"data":[{"time":"2026-10-20 15:00:00","action":"LOGIN","user":"aa_test_ftp","password":"x"}]}""")
        ).single()
        assertEquals("2026-10-20 15:00:00", flat.time)
        assertEquals("LOGIN", flat.action)
        assertFalse(flat.detail.contains("x\""))
        assertEquals("aa_test_ftp", flat.detail)

        val grouped = btFtpLogLines(
            parse("""{"data":{"upload":[{"time":"2026-10-20 15:01:00","file":"a.txt"}]}}""")
        ).single()
        assertEquals("上传", grouped.action)
        assertTrue(grouped.text.contains("a.txt"))
    }

    // ── 校验：全部按面板实测的严格程度来 ──

    @Test
    fun `database name and user follow the documented character class and byte limit`() {
        assertNull(BtNewDatabaseForm.nameError("aa_test-db.1"))
        assertNotNullRule(BtNewDatabaseForm.nameError(""))
        assertNotNullRule(BtNewDatabaseForm.nameError("带中文"))
        assertNotNullRule(BtNewDatabaseForm.nameError("a".repeat(65)))
        assertNull(BtNewDatabaseForm.nameError("a".repeat(64)))
        // 数字打头面板会当字符串处理，这一条是我们加的护栏，不是面板的规矩。
        assertNotNullRule(BtNewDatabaseForm.nameError("1db"))
        assertNotNullRule(BtNewDatabaseForm.userError("a".repeat(33)))
        assertNotNullRule(BtNewDatabaseForm.userError("bad name"))
    }

    @Test
    fun `password rule is length plus the characters the panel would choke on`() {
        assertNotNullRule(BtNewDatabaseForm.passwordError("short"))
        assertNotNullRule(BtNewDatabaseForm.passwordError("has'quote"))
        assertNotNullRule(BtNewDatabaseForm.passwordError("has\\slash"))
        assertNull(BtNewDatabaseForm.passwordError("Ab12345678"))
    }

    @Test
    fun `address list allows the forms the panel offers and flags the wide open one`() {
        assertNull(BtNewDatabaseForm.addressError("127.0.0.1"))
        assertNull(BtNewDatabaseForm.addressError("%"))
        assertNull(BtNewDatabaseForm.addressError("192.168.1.%"))
        assertNull(BtNewDatabaseForm.addressError("localhost,10.0.0.8"))
        assertNotNullRule(BtNewDatabaseForm.addressError("10.0.0.0/24 10.0.0.1"))
        assertTrue(BtNewDatabaseForm.isWideOpen("%"))
        assertFalse(BtNewDatabaseForm.isWideOpen("127.0.0.1"))
    }

    @Test
    fun `generated passwords satisfy the validators they are meant to pass`() {
        repeat(20) {
            val dbPassword = BtNewDatabaseForm.randomPassword()
            assertNull(BtNewDatabaseForm.passwordError(dbPassword))
            val ftpPassword = BtNewFtpForm.randomPassword()
            assertNull(BtNewFtpForm.passwordError(ftpPassword))
        }
    }

    @Test
    fun `ftp name and password are stricter than the database ones`() {
        assertNull(BtNewFtpForm.nameError("aa_test_ftp"))
        // 实测面板连中划线都拒，所以这里不放 -。
        assertNotNullRule(BtNewFtpForm.nameError("aa-test"))
        assertNotNullRule(BtNewFtpForm.nameError("1aa"))
        assertNotNullRule(BtNewFtpForm.passwordError("onlyletters"))
        assertNotNullRule(BtNewFtpForm.passwordError("12345678"))
        assertNull(BtNewFtpForm.passwordError("abc12345"))
    }

    @Test
    fun `ftp path must be absolute and stay out of parent directories`() {
        assertNull(BtNewFtpForm.pathError("/www/wwwroot/aa"))
        assertNotNullRule(BtNewFtpForm.pathError("www/wwwroot"))
        assertNotNullRule(BtNewFtpForm.pathError("/www/../etc"))
    }

    @Test
    fun `size fields accept zero and unit suffixes and clean up bare numbers`() {
        assertNull(BtNewFtpForm.sizeError("", allowBlank = true))
        assertNotNullRule(BtNewFtpForm.sizeError("", allowBlank = false))
        assertNull(BtNewFtpForm.sizeError("0", allowBlank = true))
        assertNull(BtNewFtpForm.sizeError("100kb", allowBlank = true))
        assertNotNullRule(BtNewFtpForm.sizeError("100 PB", allowBlank = true))
        assertEquals("100 KB", BtNewFtpForm.cleanSize("100"))
        assertEquals("1GB", BtNewFtpForm.cleanSize("1gb"))
        assertEquals("0 KB", BtNewFtpForm.cleanSize("0"))
    }

    @Test
    fun `charset list is the four the panel offers`() {
        assertEquals(listOf("utf8", "utf8mb4", "gbk", "big5"), BtDbCharsets)
    }

    private fun assertNotNullRule(error: String?) {
        assertTrue("预期这一条要被拦下", error != null)
    }
}
