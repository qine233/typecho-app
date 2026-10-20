package noline.zhouzhou.ui.screens.btpanel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划任务表单 → 面板那一行的那一步。
 *
 * 钉两件事：界面上没摆出来的格子**不发**（整行由仓库层从列表那一行带回来，这里只给覆盖），
 * 以及 `where1` 在 `week` 那一档要同时填进 `week` 格——面板读的是 `week`，不是 `where1`。
 */
class BtCronSpecTest {

    @Test
    fun `a shell task sends name cycle clock and script only`() {
        val body = BtCronSpec(
            name = "  aa-tick  ",
            cycle = "day",
            hour = "3",
            minute = "7",
            kind = "toShell",
            script = "echo hi"
        ).editOverrides()
        assertEquals("aa-tick", body["name"])
        assertEquals("day", body["type"])
        // 写入口只认 hour/minute，读回来的那一行叫 where_hour/where_minute：两个名字都要盖成新值，
        // 否则整行带回来的旧时刻会和界面上的新值一起发过去。
        assertEquals("3", body["hour"])
        assertEquals("7", body["minute"])
        assertEquals("3", body["where_hour"])
        assertEquals("7", body["where_minute"])
        assertEquals("echo hi", body["sBody"])
        // 每天不需要 N，所以 where1 这一格发空串（面板把它当「没有 N」）。
        assertEquals("", body["where1"])
        assertFalse(body.containsKey("save"))
        assertFalse(body.containsKey("backupTo"))
        assertFalse(body.containsKey("sName"))
        assertFalse(body.containsKey("user"))
    }

    @Test
    fun `weekly puts the weekday into week as well as where1`() {
        val body = BtCronSpec(
            name = "w", cycle = "week", where1 = "6", hour = "4", minute = "30",
            kind = "toShell", script = "x"
        ).editOverrides()
        assertEquals("6", body["where1"])
        assertEquals("6", body["week"])
        assertEquals("4", body["where_hour"])
        assertEquals("30", body["where_minute"])
    }

    @Test
    fun `non weekly cycles clear the week slot instead of leaving a stale weekday`() {
        val body = BtCronSpec(name = "w", cycle = "month", where1 = "15", kind = "toShell", script = "x").editOverrides()
        assertEquals("", body["week"])
        assertEquals("15", body["where1"])
    }

    @Test
    fun `backup task ships target keep and destination, url task ships only the url`() {
        val backup = BtCronSpec(
            name = "b", cycle = "day", hour = "1", minute = "0",
            kind = "database", target = "aa_test_db", keepBackups = "3", backupTo = "localhost"
        ).editOverrides()
        assertEquals("aa_test_db", backup["sName"])
        assertEquals("3", backup["save"])
        assertEquals("localhost", backup["backupTo"])
        assertFalse(backup.containsKey("sBody"))

        val url = BtCronSpec(name = "u", cycle = "hour-n", where1 = "2", kind = "toUrl", urlAddress = "https://x.test/c")
            .editOverrides()
        assertEquals("https://x.test/c", url["urladdress"])
        assertFalse(url.containsKey("sBody"))
        assertFalse(url.containsKey("save"))
    }

    @Test
    fun `hour offset only cycles ask for the minute`() {
        val body = BtCronSpec(name = "h", cycle = "hour-n", where1 = "2", minute = "15", kind = "toShell", script = "x")
            .editOverrides()
        assertFalse(body.containsKey("where_hour"))
        assertEquals("15", body["where_minute"])
    }

    @Test
    fun `which slots the form shows follows the cycle`() {
        assertTrue(cycleNeedsCount("minute-n"))
        assertTrue(cycleNeedsCount("week"))
        assertTrue(cycleNeedsCount("month"))
        assertFalse(cycleNeedsCount("day"))
        assertTrue(cycleNeedsHour("week"))
        assertFalse(cycleNeedsHour("hour-n"))
        assertTrue(cycleNeedsMinute("hour-n"))
        assertFalse(cycleNeedsMinute("minute-n"))
        assertEquals("星期几", cycleCountLabel("week"))
        assertEquals("几号", cycleCountLabel("month"))
    }

    @Test
    fun `back from a panel row keeps the display name and the panel clock strings`() {
        val task = btCronRow(
            """{"id":7,"rname":"改名过","name":"aa-test-cron","type":"week","where1":"0","sType":"toShell",
               |"sBody":"echo tick","where_hour":8,"where_minute":5,"status":1,"save":"","backupTo":"",
               |"cycle":"每周","user":"www","addtime":"2026-10-20 16:20:56"}""".trimMargin()
        )
        val spec = BtCronSpec.of(task)
        assertEquals("改名过", spec.name)
        assertEquals("week", spec.cycle)
        assertEquals("0", spec.where1)
        assertEquals("8", spec.hour)
        assertEquals("5", spec.minute)
        assertEquals("echo tick", spec.script)
        // 面板的 save 空串不能变成「保留 0 份」，回填后表单这一格应当是空的。
        assertEquals("", spec.keepBackups)
        assertEquals("www", spec.runAs)
    }

    /** 只用模型层那一个入口，测试不自己拼解析。 */
    private fun btCronRow(text: String) =
        noline.zhouzhou.data.model.btCronTasks(Json.parseToJsonElement("[$text]")).single()
}
