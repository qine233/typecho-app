package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划任务读模型单测。
 *
 * 每份 JSON 都是照正式面板 13.1.0 的实测回包抄的形状（一次性任务 `aa-test-cron` 建→改名→
 * 改周期→暂停→执行→删，每一步都读回来对过）。这里单独钉住的四条是面板不会自己交代的：
 * 显示名在 `rname` 那一列、空串和「面板没给」是两件事、改任务必须整行带回、
 * 以及 `result` 这种我们读不懂的格子绝不能上界面。
 */
class BtCronModelsTest {

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    // 实测的列表行：备份任务（save 是数字）+ shell 任务（save 是空串）+ 一个被停用的小时任务。
    private val listFixture = """
        [{"id":3,"name":"备份mysql数据库[全部]","rname":"备份mysql数据库[全部]","type":"day",
          "where1":"","where_hour":3,"where_minute":0,"echo":"aaaa","addtime":"2026-10-20 03:00:00",
          "status":1,"save":3,"backupTo":"localhost","sName":"ALL","sBody":"","sType":"database",
          "urladdress":"","save_local":0,"db_type":"mysql","table_list":"ALL","type_id":0,
          "user":"root","type_name":"默认分类","type_zh":"每天","cycle":"每天的03:00执行一次",
          "result":1,"flock":0,"params":"{}","second":"","sort":0,"notice":0},
         {"id":10,"name":"aa-test-cron","rname":"aa-test-cron-C","type":"minute-n",
          "where1":"1","where_hour":null,"where_minute":null,"echo":"bbbb","addtime":" ",
          "status":0,"save":"","backupTo":null,"sName":"","sBody":"echo aa-tick","sType":"toShell",
          "urladdress":null,"save_local":0,"db_type":null,"table_list":"","type_id":0,
          "user":"root","type_name":"默认分类","type_zh":"每1分钟","cycle":"每小时的第0分钟开始，每隔1分钟执行一次",
          "result":1,"flock":0,"params":"{}","second":"","sort":0,"notice":0}]
    """.trimIndent()

    @Test
    fun `the bare GetCrontab array parses and the display name comes from rname`() {
        val rows = btCronTasks(parse(listFixture))
        assertEquals(2, rows.size)
        // 实测：改名走 modify_crond，新名字落进 rname，name 那一列停在创建那一刻。
        assertEquals("aa-test-cron-C", rows[1].name)
        assertEquals("aa-test-cron", rows[1].createdAtName)
        assertEquals("每小时的第0分钟开始，每隔1分钟执行一次", rows[1].cycleText)
        assertEquals("已暂停", rows[1].statusText)
        assertEquals("Shell 脚本 · echo aa-tick", rows[1].subtitle)
    }

    @Test
    fun `backup task reads keep count and target while a shell task reads none`() {
        val backup = btCronTasks(parse(listFixture)).first()
        assertEquals(3, backup.keepBackups)
        assertTrue(backup.isBackupKind)
        assertEquals("全部", backup.targetText)
        assertEquals("保留 3 份 · 本机 · 不留本地", backup.backupText)
        assertTrue(backup.detailFields.any { it.label == "数据库类型" && it.value == "mysql" })

        val shell = btCronTasks(parse(listFixture))[1]
        // `save` 在这一行是空串：面板没给份数，不是「保留 0 份」。
        assertNull(shell.keepBackups)
        assertFalse(shell.isBackupKind)
        assertEquals("", shell.backupText)
    }

    @Test
    fun `an empty flag is not the same as off`() {
        val off = btCronTasks(parse("""[{"id":1,"name":"t","status":0,"save_local":""}]""")).single()
        assertEquals(false, off.enabled)
        // `save_local` 给的是空串，界面上只能说「面板没说」，不能画成一个关着的开关。
        assertNull(off.keepLocal)
        val missing = btCronTasks(parse("""[{"id":1,"name":"t"}]""")).single()
        assertEquals("面板没给状态", missing.statusText)
    }

    @Test
    fun `a value the model cannot read stays out of the UI`() {
        val row = btCronTasks(parse(listFixture))[1]
        // `result` 实测八个任务七个是 1、唯一写着上传失败的那个反而是 0，猜不动，所以哪儿都不摆。
        assertFalse(row.detailFields.any { it.value.contains("result") })
        assertTrue(row.extraKeys.contains("notice"))
        assertTrue(row.extraKeys.contains("flock"))
        assertFalse(row.extraKeys.contains("type_zh"))
    }

    @Test
    fun `editing sends the whole row back with the display name and addtime`() {
        val row = btCronTasks(parse(listFixture))[1]
        val body = row.editableBody()
        // 第 1 条实测：面板把没发的格子抹成 null，所以整行都得发回去。
        assertEquals("echo aa-tick", body["sBody"])
        assertEquals("1", body["where1"])
        assertEquals("bbbb", body["echo"])
        // 第 2 条：addtime 要带回去，不然面板把它写成空格（抠成文案时空格会被 trim 掉）。
        assertEquals("", body["addtime"])
        val fresh = btCronTasks(parse(listFixture))[0]
        assertEquals("2026-10-20 03:00:00", fresh.editableBody()["addtime"])
        // 第 3 条：name 这格发的是界面那个名字，发原始 name 会把上一次改的名盖回去。
        assertEquals("aa-test-cron-C", body["name"])
        // 面板算出来的格子不发回去，免得它拿我们的猜测覆盖自己的计算。
        assertFalse(body.containsKey("cycle"))
        assertFalse(body.containsKey("type_zh"))
        assertFalse(body.containsKey("sort"))
        // null 的格子跳过而不是发空串。
        assertFalse(body.containsKey("backupTo"))
        assertFalse(body.containsKey("db_type"))
        // status 在 raw 里是数字 0，发回去得是「0」这个字符串。
        assertEquals("0", body["status"])
    }

    @Test
    fun `single task lookup has no computed columns and still reads`() {
        // `get_crond_find` 是裸对象，而且比列表少 cycle / type_zh / user 这几列。
        val task = btCronTask(
            parse(
                """{"id":10,"name":"aa-test-cron","rname":"","type":"day","where1":"","where_hour":null,
                   |"where_minute":null,"echo":"bbbb","addtime":"2026-10-20 16:20:56","status":1,
                   |"save":0,"backupTo":"localhost","sName":"","sBody":"echo aa-test-cron","sType":"toShell",
                   |"urladdress":"","save_local":0,"db_type":"","type_id":0,"result":1,"params":"{}"}""".trimMargin()
            )
        )!!
        assertEquals("aa-test-cron", task.name)
        assertEquals("", task.categoryName)
        // 面板那句原话没有，只能按代码自己拼。
        assertEquals("每天", task.cycleText)
        assertEquals(0, task.keepBackups)
    }

    @Test
    fun `cycle and kind labels cover what this panel actually uses`() {
        assertEquals("每 5 分钟", btCronCycleLabel("minute-n", "5"))
        assertEquals("每周日", btCronCycleLabel("week", "0"))
        assertEquals("每周日", btCronCycleLabel("week", "7"))
        assertEquals("每月 15 号", btCronCycleLabel("month", "15"))
        // 认不出的代码原样带出来，不糊一个「未知类型」上去。
        assertEquals("toWeird", btCronKindLabel("toWeird"))
        assertEquals("面板没给周期", btCronCycleLabel("", ""))
        assertEquals("微软 OneDrive", btCronBackupTargetText("msonedrive"))
        assertEquals("", btCronBackupTargetText(""))
    }

    @Test
    fun `pick list also carries the usable backup targets`() {
        // `GetDataList` 的 orderOpt 名字像排序选项，实测它是这台能用起来的备份目标。
        val pick = btCronPickList(
            parse(
                """{"data":[{"name":"example.com","ps":"博客"},{"name":"","ps":"没名字的一行"}],
                   |"orderOpt":[{"name":"微软OneDrive","value":"msonedrive","status":1}]}""".trimMargin()
            )
        )
        assertEquals(listOf("example.com"), pick.targets.map { it.id })
        assertEquals(listOf("msonedrive"), pick.backupTargets.map { it.id })
        assertEquals("微软OneDrive", pick.backupTargets.single().name)
    }

    @Test
    fun `database pick list skips the all-tables row`() {
        val pairs = btCronDatabases(
            parse(
                """[{"name":"aa_test_db","ps":"","table_list":[{"tb_name":"所有","value":"ALL","cron_id":0},
                   |{"tb_name":"options","value":"options","cron_id":0}]},
                   |{"name":"mysql","table_list":[]}]""".trimMargin()
            )
        )
        assertEquals(listOf("aa_test_db", "mysql"), pairs.map { it.first })
        assertEquals(listOf("options"), pairs[0].second)
        assertTrue(pairs[1].second.isEmpty())
    }

    @Test
    fun `rotate log config reads strings as strings and a missing switch as unknown`() {
        val on = btCronRotateLog(
            parse("""{"status":true,"msg":{"log_size":0,"hour":"0","minute":"0","num":10,
                   |"compress":false,"stype":"day","status":1}}""".trimMargin())
        )
        assertEquals(true, on.enabled)
        assertEquals("0", on.hour)
        assertEquals(10, on.keep)
        assertTrue(on.summary.contains("已开启"))
        // 实测删掉那条切割任务之后，面板连 status 这一格都不给了。
        val never = btCronRotateLog(
            parse("""{"status":true,"msg":{"log_size":0,"hour":"0","minute":"0","num":10,
                   |"compress":false,"stype":"day"}}""".trimMargin())
        )
        assertNull(never.enabled)
        assertFalse(never.summary.contains("已开启"))
        assertFalse(never.summary.contains("未开启"))
    }

    @Test
    fun `auto backup with only a status means there is no such task`() {
        // 实测这台没建过自动备份，面板回的就是 `msg:{status:0}` 一个键。
        val none = btCronAutoBackup(parse("""{"status":true,"msg":{"status":0}}"""), "mysql")
        assertFalse(none.configured)
        assertEquals("面板里没有这一条自动备份任务", none.summary)

        val some = btCronAutoBackup(
            parse("""{"status":true,"msg":{"id":11,"name":"自动备份mysql数据库[所有]","type":"day",
                   |"status":1,"save":3,"backupTo":"localhost"}}""".trimMargin()),
            "mysql"
        )
        assertTrue(some.configured)
        assertTrue(some.summary.contains("自动备份mysql数据库[所有]"))
    }

    @Test
    fun `log path says 无 and that is not a path`() {
        val none = btCronLogInfo(parse("""{"status":true,"msg":{"log_path":"无","size":"0B"}}"""))
        assertFalse(none.exists)
        val some = btCronLogInfo(
            parse("""{"status":true,"msg":{"log_path":"/www/server/cron/bbbb.log","size":"621.0 B"}}""")
        )
        assertTrue(some.exists)
        assertEquals("621.0 B", some.sizeText)
    }

    @Test
    fun `string lists come back bare from the panel`() {
        assertEquals(
            listOf("http://example.com", "http://www.example.com"),
            btCronStringList(parse("""["http://example.com","http://www.example.com",""]"""))
        )
        assertEquals(listOf("root", "www"), btCronStringList(parse("""["root","www"]""")))
        assertTrue(btCronStringList(parse("""{"status":true}""")).isEmpty())
    }

    @Test
    fun `categories live in the msg slot of a status envelope`() {
        // 实测这台的分类口回 `{"status":true,"msg":[]}`。
        assertTrue(btCronCategories(parse("""{"status":true,"msg":[]}""")).isEmpty())
        val list = btCronCategories(parse("""{"status":true,"msg":[{"id":2,"name":"维护"}]}"""))
        assertEquals(listOf("2"), list.map { it.id })
        assertEquals("维护", list.single().name)
    }

    // ── 表单校验 ──

    @Test
    fun `the N in a cycle depends on which cycle was picked`() {
        assertNull(BtCronForm.where1Error("day", ""))
        assertEquals("每 5 分钟", btCronCycleLabel("minute-n", "5"))
        assertNotEquals(null, BtCronForm.where1Error("minute-n", ""))
        assertNotEquals(null, BtCronForm.where1Error("minute-n", "0"))
        assertNull(BtCronForm.where1Error("hour-n", "12"))
        assertNotEquals(null, BtCronForm.where1Error("month", "32"))
        assertNull(BtCronForm.where1Error("week", "0"))
        assertNotEquals(null, BtCronForm.where1Error("week", "8"))
        assertNotEquals(null, BtCronForm.where1Error("day-n", "abc"))
    }

    @Test
    fun `clock fields say which field is wrong in Chinese`() {
        // 这条专门盯着 CJK 字符串模板：少了大括号的话，拼接会直接少字。
        assertEquals("时只能填整数", BtCronForm.clockError("aa", "时", 23))
        assertEquals("分得在 0–59 之间", BtCronForm.clockError("99", "分", 59))
        assertNull(BtCronForm.clockError("7", "时", 23))
        assertEquals("要选一个时", BtCronForm.clockError("", "时", 23))
    }

    @Test
    fun `url script name and path each get their own reason`() {
        assertNotEquals(null, BtCronForm.nameError("   "))
        assertNull(BtCronForm.nameError("aa-test-cron"))
        assertNotEquals(null, BtCronForm.scriptError("  \n "))
        assertNotEquals(null, BtCronForm.urlError("example.com/feed"))
        assertNull(BtCronForm.urlError("https://example.com/feed"))
        assertNotEquals(null, BtCronForm.urlError("https://example.com/a b"))
        assertNotEquals(null, BtCronForm.keepError("-1"))
        assertNull(BtCronForm.keepError(""))
        assertNotEquals(null, BtCronForm.pathError("backup/www"))
        assertNotEquals(null, BtCronForm.pathError("/www/../etc"))
        assertNull(BtCronForm.pathError("/www/wwwroot/example.com"))
        assertNotEquals(null, BtCronForm.categoryError("一二三四五六七八九十一二三四五六七八九十a"))
    }
}
