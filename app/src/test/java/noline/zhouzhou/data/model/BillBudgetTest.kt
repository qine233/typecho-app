package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算是 App 自己算的进度：站点没有预算端点，所以「超没超」「这期过了几天」
 * 这几句话一旦算错，用户在通知和桌面卡片上看到的就是错的。
 */
class BillBudgetTest {

    @Test
    fun `an unset budget says nothing`() {
        val status = billBudgetStatus(BillBudget(), spent = 500.0, kindSpent = mapOf("veg" to 500.0))
        assertTrue(!status.active)
        assertTrue(!status.over)
        assertEquals(0f, status.usedFraction, 0.001f)
        assertEquals("", status.alert())
    }

    @Test
    fun `progress reports what is left, not what is spent only`() {
        val status = billBudgetStatus(BillBudget(limit = 800.0), spent = 680.0, kindSpent = emptyMap())
        assertTrue(status.active)
        assertTrue(!status.over)
        assertEquals(120.0, status.left, 0.001)
        assertEquals(0.85f, status.usedFraction, 0.001f)
        // 「还剩很多」不是要打扰人的事。
        assertEquals("", status.alert())
    }

    @Test
    fun `overspending the total is what puts a line in the notification`() {
        val status = billBudgetStatus(BillBudget(limit = 800.0), spent = 920.5, kindSpent = emptyMap())
        assertTrue(status.over)
        assertEquals(120.5, status.overBy, 0.001)
        // 条子不许画爆表：超过 100% 也只能是 100%。
        assertEquals(1f, status.usedFraction, 0.001f)
        assertTrue(status.alert(), status.alert().startsWith("本月买菜超预算"))
        assertTrue(status.alert(), status.alert().contains("120.50"))
    }

    @Test
    fun `the period decides which month week or year the alert talks about`() {
        val week = billBudgetStatus(
            BillBudget(period = BillBudgetPeriod.Week, limit = 200.0),
            spent = 260.0,
            kindSpent = emptyMap()
        )
        assertTrue(week.alert(), week.alert().startsWith("本周买菜超预算"))
        assertTrue(week.alert(), week.alert().contains("60.00"))

        val year = billBudgetStatus(
            BillBudget(period = BillBudgetPeriod.Year, limit = 9000.0),
            spent = 9500.0,
            kindSpent = emptyMap()
        )
        assertTrue(year.alert(), year.alert().startsWith("本年买菜超预算"))
        assertEquals("本年", year.budget.subject)
        assertEquals("每年", year.budget.periodLabel)
    }

    @Test
    fun `only the kinds with a limit are tracked`() {
        val budget = BillBudget(limit = 800.0, perKind = mapOf("veg" to 100.0, "fish" to 200.0))
        val status = billBudgetStatus(
            budget,
            spent = 500.0,
            kindSpent = mapOf("veg" to 90.0, "fish" to 260.0, "meat" to 150.0)
        )
        // 没设额度的「肉禽」不进这张表，否则用户会以为那也是条预算线。
        assertEquals(listOf("veg", "fish"), status.kinds.map { it.kind })
        assertEquals(90.0, status.kinds.first { it.kind == "veg" }.spent, 0.001)
        assertEquals(listOf("fish"), status.kindsOver.map { it.kind })
        assertEquals(60.0, status.kindsOver.first().overBy, 0.001)
    }

    @Test
    fun `kind budgets go quiet off the monthly setting`() {
        // 概览接口只汇总本月的分类（kinds_month），按周/按年时那个数根本对不上本期，
        // 所以额度照旧存着，只是不画那条会骗人的进度。
        val weekly = BillBudget(period = BillBudgetPeriod.Week, limit = 200.0, perKind = mapOf("fish" to 50.0))
        val status = billBudgetStatus(weekly, spent = 300.0, kindSpent = mapOf("fish" to 260.0))
        assertTrue(!weekly.kindsVisible)
        assertTrue(status.kinds.isEmpty())
        assertTrue(status.kindsOver.isEmpty())
        // 总预算那句照样要说。
        assertTrue(status.alert(), status.alert().startsWith("本周买菜超预算"))
    }

    @Test
    fun `kind overrun is named even when the total is still inside`() {
        val budget = BillBudget(perKind = mapOf("fish" to 200.0))
        val status = billBudgetStatus(budget, spent = 300.0, kindSpent = mapOf("fish" to 260.0))
        assertTrue(!status.over)
        assertTrue(status.alert(), status.alert().contains("水产超了"))
        assertTrue(status.alert(), !status.alert().contains("本月买菜超预算"))
    }

    @Test
    fun `a pile of overrun kinds is capped instead of filling the shade`() {
        val budget = BillBudget(
            limit = 100.0,
            perKind = mapOf("veg" to 10.0, "fish" to 10.0, "meat" to 10.0, "egg" to 10.0)
        )
        val spent = mapOf("veg" to 50.0, "fish" to 50.0, "meat" to 50.0, "egg" to 50.0)
        val status = billBudgetStatus(budget, spent = 200.0, kindSpent = spent)
        assertEquals(4, status.kindsOver.size)
        val alert = status.alert()
        assertTrue(alert, alert.contains("等 4 类"))
        // 总预算那句话 + 被点名的前两类，再多就不说了。
        assertTrue(alert, alert.contains("本月买菜超预算"))
        assertTrue(alert, 1 == alert.count { it == '，' })
    }

    @Test
    fun `a centimeter of rounding is not overspending`() {
        // 金额两位小数，浮点相加会带出 800.0000000000001 这种数；别为它报「超了」。
        val status = billBudgetStatus(BillBudget(limit = 800.0), spent = 800.0000001, kindSpent = emptyMap())
        assertTrue(!status.over)
        assertEquals("", status.alert())
    }

    @Test
    fun `week runs monday to sunday and a sunday stays in its own week`() {
        // 接口的 week_start 就是周一（实测 2026-10-12 周一 ~ 2026-10-18 周日），口径必须一致。
        assertEquals("2026-10-12" to "2026-10-18", billPeriodRange(BillBudgetPeriod.Week, "2026-10-14"))
        assertEquals("2026-10-12" to "2026-10-18", billPeriodRange(BillBudgetPeriod.Week, "2026-10-12"))
        assertEquals("2026-10-12" to "2026-10-18", billPeriodRange(BillBudgetPeriod.Week, "2026-10-18"))
        assertTrue(billDateInPeriod("2026-10-18", billPeriodRange(BillBudgetPeriod.Week, "2026-10-18")))
        assertTrue(!billDateInPeriod("2026-10-19", billPeriodRange(BillBudgetPeriod.Week, "2026-10-18")))
        // 还没拿到概览时边界是空的，这时候一笔都不能算进本期。
        assertTrue(!billDateInPeriod("2026-10-14", "" to ""))
    }

    @Test
    fun `month and year ranges follow the calendar including leap february`() {
        assertEquals("2026-10-01" to "2026-10-31", billPeriodRange(BillBudgetPeriod.Month, "2026-10-14"))
        assertEquals("2026-02-01" to "2026-02-28", billPeriodRange(BillBudgetPeriod.Month, "2026-02-28"))
        assertEquals("2028-02-01" to "2028-02-29", billPeriodRange(BillBudgetPeriod.Month, "2028-02-05"))
        assertEquals("2026-01-01" to "2026-12-31", billPeriodRange(BillBudgetPeriod.Year, "2026-10-14"))
        // 认不出的日期不猜：画一条「刚开头」的参考线比不画更糟。
        assertEquals("" to "", billPeriodRange(BillBudgetPeriod.Month, "not-a-date"))
    }

    @Test
    fun `elapsed counts the day it is running inclusively`() {
        val progress = billPeriodProgress(BillBudget(period = BillBudgetPeriod.Month, limit = 800.0), "2026-10-14")!!
        assertEquals(14, progress.goneDays)
        assertEquals(31, progress.totalDays)
        assertEquals("14/31 天", progress.label)
        assertEquals(14f / 31f, progress.fraction, 0.001f)

        val last = billRangeProgress("2026-10-01", "2026-10-31", "2026-10-31")!!
        assertEquals(1f, last.fraction, 0.001f)
        // 缓存里的日期跑到了区间外，也只能说「过完了」，不能说过了 400 天。
        val beyond = billRangeProgress("2026-01-01", "2026-01-31", "2026-03-01")!!
        assertEquals(31, beyond.goneDays)
        assertNull(billPeriodProgress(BillBudget(), "2026-10-14"))
    }

    @Test
    fun `budget text accepts blank and plain numbers only`() {
        assertEquals(0.0, parseBillBudgetText("")!!, 0.001)
        assertEquals(0.0, parseBillBudgetText("  ")!!, 0.001)
        assertEquals(800.0, parseBillBudgetText("800")!!, 0.001)
        assertEquals(800.55, parseBillBudgetText(" 800.55 ")!!, 0.001)
        assertNull(parseBillBudgetText("八百"))
        assertNull(parseBillBudgetText("-5"))
        assertNull(parseBillBudgetText("1000001"))
    }

    @Test
    fun `saving normalizes away zeros and kinds the server does not know`() {
        val normalized = BillBudget(
            period = BillBudgetPeriod.Week,
            limit = 799.999,
            perKind = mapOf("veg" to 0.0, "fish" to 199.999, "mystery" to 50.0)
        ).normalized()
        assertEquals(BillBudgetPeriod.Week, normalized.period)
        assertEquals(800.0, normalized.limit, 0.001)
        assertEquals(listOf("fish"), normalized.perKind.keys.toList())
        assertEquals(200.0, normalized.perKind["fish"] ?: 0.0, 0.001)
        // other 也算「没设」，所以整个预算等于没设。
        assertTrue(!BillBudget(perKind = mapOf("veg" to 0.0)).active)
    }

    @Test
    fun `an old file without a period still reads as monthly`() {
        // 存进文件的键一直是 monthly；换成 limit 而不留着 SerialName，老数据会直接掉成「没设预算」。
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(
            BillBudget.serializer(),
            """{"monthly":800,"perKind":{"veg":100}}"""
        )
        assertEquals(800.0, legacy.limit, 0.001)
        assertEquals(BillBudgetPeriod.Month, legacy.period)
        assertTrue(legacy.kindsVisible)

        val roundTrip = json.decodeFromString(
            BillBudget.serializer(),
            json.encodeToString(BillBudget.serializer(), BillBudget(period = BillBudgetPeriod.Year, limit = 9000.0))
        )
        assertEquals(BillBudgetPeriod.Year, roundTrip.period)
        assertEquals("每年", roundTrip.periodLabel)
        assertEquals(BillBudgetPeriod.Month, BillBudgetPeriod.of("Weekk"))
        assertEquals(BillBudgetPeriod.Week, BillBudgetPeriod.of("Week"))

        // 新版本多个档位、老版本读到它：只能是「每月」，不能整个文件解码失败变成「没设预算」。
        val fromFuture = json.decodeFromString(
            BillBudget.serializer(),
            """{"period":"Fortnight","monthly":800,"perKind":{"veg":100}}"""
        )
        assertEquals(BillBudgetPeriod.Month, fromFuture.period)
        assertEquals(800.0, fromFuture.limit, 0.001)
        assertEquals(100.0, fromFuture.perKind["veg"] ?: 0.0, 0.001)
    }
}
