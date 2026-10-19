package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板各版本给的数据形状不一样，这一层的全部责任就是：
 * 认得出的转成数，认不出的原样带出来，两者都不成就说「没给」——绝不能把没给写成 0。
 */
class BtPanelModelsTest {

    private fun element(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `percent reads both string and number`() {
        assertEquals(25.45, parseBtPercent("25.45%")!!, 0.001)
        assertEquals(3.0, parseBtPercent("3")!!, 0.001)
        assertEquals(12.0, parseBtPercent("已使用 12 %")!!, 0.001)
        assertNull(parseBtPercent("101"))
        assertNull(parseBtPercent("正常"))
        assertNull(parseBtPercent(""))
    }

    @Test
    fun `size needs a unit before it becomes gigabytes`() {
        assertEquals(1.01, parseBtSizeGb("1.01GB")!!, 0.001)
        assertEquals(36.0, parseBtSizeGb("36G")!!, 0.001)
        assertEquals(0.5, parseBtSizeGb("512MB")!!, 0.001)
        assertEquals(2048.0, parseBtSizeGb("2TB")!!, 0.001)
        // 光秃秃的数字可能是端口、条数、也可能是 GB，不敢猜。
        assertNull(parseBtSizeGb("8888"))
        assertNull(parseBtSizeGb(""))
    }

    @Test
    fun `uptime is either a second count or the panel's own sentence`() {
        assertEquals("1 天 1 小时 1 分钟", formatBtUptime(JsonPrimitive("90061")))
        assertEquals("3 小时 25 分钟", formatBtUptime(JsonPrimitive("12345")))
        assertEquals("42 秒", formatBtUptime(JsonPrimitive("42")))
        assertEquals("已运行 12 天", formatBtUptime(JsonPrimitive("已运行 12 天")))
        assertEquals("", formatBtUptime(null))
    }

    @Test
    fun `system snapshot reads nested memory and load objects`() {
        val snapshot = btSystemSnapshot(
            element(
                """{"cpuStatus":"37.5",
                    |"memInfo":{"realUsed":"2.5GB","total":"3.84GB","used":"65.1%"},
                    |"load":{"one":"0.12","five":"0.08","fifteen":"0.03","status":"正常"},
                    |"online":true,
                    |"sTime":"12345",
                    |"warnings":{"webserver":{"status":"stopped","msg":"nginx 未运行"}}}""".trimMargin()
            )
        )
        assertEquals(37.5, snapshot.cpuPercent!!, 0.001)
        assertEquals(2.5, snapshot.memUsedGb!!, 0.001)
        assertEquals(3.84, snapshot.memTotalGb!!, 0.001)
        assertEquals("2.5GB / 3.84GB", snapshot.memText)
        assertEquals(listOf("0.12", "0.08", "0.03"), listOf(snapshot.loadOne, snapshot.loadFive, snapshot.loadFifteen))
        assertEquals("正常", snapshot.loadLabel)
        assertEquals("3 小时 25 分钟", snapshot.uptimeText)
        assertEquals(listOf("webserver · stopped · nginx 未运行"), snapshot.warnings)
        // 没专门认的键不能凭空消失，原样列出来才查得出版本差异。
        assertEquals(listOf("online" to "是"), snapshot.extras.map { it.label to it.value })
    }

    @Test
    fun `absent metrics stay absent`() {
        val snapshot = btSystemSnapshot(element("""{"cpuStatus":"","memInfo":{},"load":{}}"""))
        assertNull(snapshot.cpuPercent)
        assertNull(snapshot.memUsedGb)
        assertEquals("", snapshot.memText)
        assertEquals("", snapshot.uptimeText)
        assertTrue(snapshot.warnings.isEmpty())
        assertTrue(snapshot.extras.isEmpty())
    }

    @Test
    fun `garbage root degrades to an empty snapshot instead of throwing`() {
        val snapshot = btSystemSnapshot(element("""[1,2,3]"""))
        assertNull(snapshot.cpuPercent)
        assertTrue(snapshot.extras.isEmpty())
    }

    @Test
    fun `disks come bare or wrapped`() {
        val one = """{"disk":"/","size":"40GB","used":"12%","free":"35GB","percentage":12.5}"""
        val fromArray = btDisks(element("[$one]"))
        val fromEnvelope = btDisks(element("""{"status":true,"msg":"","data":[$one]}"""))
        assertEquals(fromArray, fromEnvelope)
        assertEquals("/", fromArray.single().name)
        assertEquals(12.5, fromArray.single().usedPercent!!, 0.001)
        // 没有盘名的条目画出来只是一条没头没尾的进度条。
        assertTrue(btDisks(element("""[{"size":"40GB"}]""")).isEmpty())
    }

    @Test
    fun `network keeps unknown counters as extra fields`() {
        val network = btNetwork(element("""{"up":"12.5KB","down":"1.2MB","recv_total":"3GB"}"""))
        assertEquals("12.5KB", network.upText)
        assertEquals(listOf("累计接收" to "3GB"), network.extras.map { it.label to it.value })
    }

    @Test
    fun `site rows fall back through several key names`() {
        val rows = btRows(
            BtTable.Sites,
            element("""{"data":{"rows":[{"id":1,"name":"www.a.com","domain":"www.a.com",
                |"status":"运行中","addtime":"2026-01-02","ssl":"on"}]}}""".trimMargin())
        )
        val row = rows.single()
        assertEquals("1", row.id)
        assertEquals("www.a.com", row.title)
        assertEquals("2026-01-02", row.subtitle)
        assertEquals("运行中", row.badge)
        assertEquals(listOf("ssl" to "on"), row.fields.map { it.label to it.value })
    }

    @Test
    fun `crontab rows read the alternate key names older panels use`() {
        val rows = btRows(
            BtTable.Crontab,
            element("""[{"id":7,"sName":"清理日志","job":"toShell","type":"minuteTask","where1":"/bin/sh"}]""")
        )
        val row = rows.single()
        assertEquals("清理日志", row.title)
        assertEquals("/bin/sh", row.subtitle)
        assertEquals("minuteTask", row.badge)
        assertEquals(listOf("任务类型" to "toShell"), row.fields.map { it.label to it.value })
    }

    @Test
    fun `log rows lead with what happened`() {
        val rows = btRows(
            BtTable.Logs,
            element("""[{"addtime":"2026-10-19 07:11","username":"admin","ip":"8.8.8.8",
                |"domain":"登录成功","agent":"Chrome"}]""".trimMargin())
        )
        val row = rows.single()
        assertEquals("登录成功", row.title)
        assertEquals("8.8.8.8", row.subtitle)
        assertEquals("admin", row.badge)
    }

    @Test
    fun `a row with no recognisable name still shows up`() {
        val rows = btRows(BtTable.Databases, element("""[{"id":3,"charset":"utf8"}]"""))
        assertEquals("未命名", rows.single().title)
    }

    @Test
    fun `total is only null when the panel really did not report it`() {
        assertEquals(7, btTotal(element("""{"records":7,"rows":[]}""")))
        // 0 条是「面板说这张表是空的」，和「没读到」是两件不同的事。
        assertEquals(0, btTotal(element("""{"data":{"records":0}}""")))
        assertNull(btTotal(element("""{"data":[]}""")))
    }

    @Test
    fun `items search two levels of shells`() {
        assertEquals(1, btItems(element("""{"data":{"rows":[{}]}}""")).size)
        assertEquals(1, btItems(element("""{"list":[{}]}""")).size)
        assertEquals(1, btItems(element("""[{}]""")).size)
        assertTrue(btItems(element("""{"status":true,"msg":"没有数据"}""")).isEmpty())
    }
}
