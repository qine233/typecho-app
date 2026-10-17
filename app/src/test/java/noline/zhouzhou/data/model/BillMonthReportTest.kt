package noline.zhouzhou.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月报是客户端算的，站点那边只肯给整月明细，所以这些数字对不对只能自己断言。
 */
class BillMonthReportTest {

    private fun rec(
        date: String,
        title: String,
        amount: Double,
        kind: String = "veg",
        time: Long = 0L
    ) = BillRecord(id = "$date-$title", date = date, title = title, amount = amount, kind = kind, time = time)

    private val october = listOf(
        rec("2026-10-02", "番茄", 12.0, "veg"),
        rec("2026-10-02", "排骨", 38.5, "meat"),
        rec("2026-10-05", "鲈鱼", 66.0, "fish"),
        rec("2026-10-18", "鸡蛋", 9.9, "egg")
    )

    @Test
    fun `amount days and both averages come from the records`() {
        val report = buildBillMonthReport("2026-10", october, today = "2026-10-18")
        assertEquals(126.4, report.amount, 0.001)
        assertEquals(4, report.count)
        // 开火的是 3 天，不是 18 天，也不是这个月的 31 天。
        assertEquals(3, report.days)
        assertEquals(42.13, report.avgDay, 0.001)
        assertEquals(31.6, report.avgBill, 0.001)
    }

    @Test
    fun `records from a neighbouring month never leak in`() {
        val report = buildBillMonthReport(
            "2026-10",
            october + listOf(rec("2026-09-30", "最后一把青菜", 7.0), rec("2026-11-01", "下月的", 5.0)),
            today = "2026-10-18"
        )
        assertEquals(126.4, report.amount, 0.001)
        assertEquals(4, report.count)
    }

    @Test
    fun `kind shares are sorted and add up to the whole month`() {
        val report = buildBillMonthReport("2026-10", october, today = "2026-10-18")
        assertEquals(listOf("fish", "meat", "veg", "egg"), report.kinds.map { it.kind })
        assertEquals(66.0, report.kinds.first().amount, 0.001)
        assertEquals(4, report.kinds.size)
        val shares = report.kinds.sumOf { it.share }
        assertEquals(1.0, shares, 0.0001)
    }

    @Test
    fun `a kind the server silently folded into other stays in other`() {
        // 服务器不认识分类代码时不报错、直接归 other，报告得跟着同一个口径，不能凭空多出一类。
        val report = buildBillMonthReport("2026-10", october + rec("2026-10-09", "杂货", 3.0, "mystery"))
        assertEquals(listOf("other"), report.kinds.filter { it.kind == "other" }.map { it.kind })
        assertEquals(3.0, report.kinds.first { it.kind == "other" }.amount, 0.001)
    }

    @Test
    fun `series covers the whole past month but only the elapsed days of this one`() {
        val past = buildBillMonthReport("2026-09", october, today = "2026-10-18")
        assertEquals(30, past.series.size)
        assertEquals("2026-09-01", past.series.first().date)
        assertEquals("2026-09-30", past.series.last().date)

        val current = buildBillMonthReport("2026-10", october, today = "2026-10-18")
        assertEquals(18, current.series.size)
        assertEquals("2026-10-18", current.series.last().date)

        // 未来的月份一根柱子都没有：画成一片 0 元是假数据。
        assertTrue(buildBillMonthReport("2026-11", october, today = "2026-10-18").series.isEmpty())
    }

    @Test
    fun `days without spending still occupy their slot`() {
        val report = buildBillMonthReport("2026-10", october, today = "2026-10-18")
        val third = report.series.first { it.date == "2026-10-03" }
        assertEquals(0.0, third.amount, 0.001)
        assertEquals(0, third.count)
        val second = report.series.first { it.date == "2026-10-02" }
        assertEquals(50.5, second.amount, 0.001)
        assertEquals(2, second.count)
    }

    @Test
    fun `busiest day and priciest single item are the two different things`() {
        val report = buildBillMonthReport("2026-10", october, today = "2026-10-18")
        // 10-02 那天两笔共 50.5，比鲈鱼单笔 66 更早更「贵的一天」——两个口径本来就不该混。
        assertEquals("2026-10-05", report.busiestDay?.date)
        assertEquals(66.0, report.busiestDay?.amount ?: 0.0, 0.001)
        assertEquals("鲈鱼", report.max?.title)
        assertEquals(listOf("鲈鱼", "排骨", "番茄", "鸡蛋"), report.topItems.map { it.title })
    }

    @Test
    fun `top items caps at five and keeps the priciest`() {
        val many = (1..8).map { rec("2026-10-0$it", "菜$it", it * 10.0) }
        val report = buildBillMonthReport("2026-10", many, today = "2026-10-18")
        assertEquals(5, report.topItems.size)
        assertEquals(80.0, report.topItems.first().amount, 0.001)
    }

    @Test
    fun `an empty month is a real answer, not a missing one`() {
        val report = buildBillMonthReport("2026-08", emptyList(), today = "2026-10-18")
        assertTrue(report.isEmptyMonth)
        assertEquals(0.0, report.amount, 0.001)
        assertNull(report.max)
        assertNull(report.busiestDay)
        assertEquals(0.0, report.avgDay, 0.001)
        assertEquals(31, report.series.size)
    }

    @Test
    fun `a bad month string yields an empty report instead of throwing`() {
        val report = buildBillMonthReport("2026-1", october, today = "2026-10-18")
        assertEquals("2026-1", report.month)
        assertEquals(0, report.count)
        assertEquals(0.0, report.amount, 0.001)
        assertTrue(report.series.isEmpty())
    }

    @Test
    fun `range is a closed month and shift crosses the year`() {
        assertEquals("2026-10-01" to "2026-10-31", billMonthRange("2026-10"))
        assertEquals("2026-02-01" to "2026-02-28", billMonthRange("2026-02"))
        assertEquals("2024-02-01" to "2024-02-29", billMonthRange("2024-02"))
        assertNull(billMonthRange("10月"))

        assertEquals("2025-12", billMonthShift("2026-01", -1))
        assertEquals("2027-01", billMonthShift("2026-12", 1))
        assertEquals("2026-09", previousBillMonth("2026-10"))
        assertEquals("2026-01", previousBillMonth("2026-02"))
    }

    @Test
    fun `future months are recognised so the next button can be disabled`() {
        assertTrue(billMonthIsFuture("2026-11", "2026-10-18"))
        assertTrue(!billMonthIsFuture("2026-10", "2026-10-18"))
        assertTrue(!billMonthIsFuture("2026-09", "2026-10-18"))
    }
}
