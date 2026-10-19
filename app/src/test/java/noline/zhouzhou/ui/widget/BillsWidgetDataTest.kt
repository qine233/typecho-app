package noline.zhouzhou.ui.widget

import noline.zhouzhou.data.model.BillAggregate
import noline.zhouzhou.data.model.BillBudget
import noline.zhouzhou.data.model.BillBudgetPeriod
import noline.zhouzhou.data.model.BillDayGroup
import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillRecord
import noline.zhouzhou.data.model.BillSpan
import noline.zhouzhou.data.model.BillsOverview
import noline.zhouzhou.data.model.billBudgetStatus
import noline.zhouzhou.data.repository.PendingBill
import noline.zhouzhou.data.repository.TitleHint
import noline.zhouzhou.ui.navigation.BillPrefill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片上的每个数字都得能在 JVM 里算一遍：Glance 的组合过程跑不了单测，
 * 而「离线记的那笔算不算进本月」这种事，肉眼在桌面上根本看不出来。
 */
class BillsWidgetDataTest {

    private val span = BillSpan(today = "2026-10-18", monthDayOf = 18, monthDays = 31)

    private val overview = BillsOverview(
        currency = "CNY",
        month = BillAggregate(amount = 1200.0, count = 40),
        byDate = listOf(BillDayGroup(date = "2026-10-18", amount = 32.5, count = 2)),
        recent = listOf(
            BillRecord(id = "r1", date = "2026-10-18", title = "白菜", amount = 6.0, kind = "veg"),
            BillRecord(id = "r2", date = "2026-10-17", title = "五花肉", amount = 26.5, kind = "meat")
        ),
        span = span
    )

    private fun build(
        configured: Boolean = true,
        data: BillsOverview? = overview,
        spent: Double = 1200.0,
        budget: BillBudget = BillBudget(limit = 2000.0),
        kindSpent: Map<String, Double> = emptyMap(),
        pending: List<PendingBill> = emptyList(),
        hints: List<TitleHint> = emptyList(),
        spec: WidgetSpec = WidgetSpec.Full
    ) = buildBillsWidgetData(
        configured = configured,
        overview = data,
        status = billBudgetStatus(budget, spent, kindSpent),
        pending = pending,
        hints = hints,
        spec = spec
    )

    private fun add(date: String, title: String, amount: Double, kind: String = "other") =
        PendingBill(uid = "u-$title", input = BillInput(date = date, title = title, amount = amount, kind = kind))

    @Test
    fun `本月金额含离线还没交上去的那笔`() {
        // 仓库那份预算进度已经把离线新增算进去了，测试也照这个口径给
        val data = build(spent = 1225.0, pending = listOf(add("2026-10-18", "五花肉", 25.0, "meat")))
        assertEquals("¥1225.00", data.monthAmount)
        // 预算进度走的是仓库算好的那份，卡片不该自己再减一遍口径
        assertEquals("61%", data.percentText)
    }

    @Test
    fun `别的月份和已认领到 id 的不算进本月`() {
        val data = build(
            pending = listOf(
                add("2026-09-30", "大米", 60.0, "grain"),
                PendingBill(uid = "u2", serverId = "20261018-009", input = BillInput("2026-10-18", "土豆", 8.0))
            )
        )
        assertEquals("¥1200.00", data.monthAmount)
        assertEquals("2 笔待同步", data.pendingText)
    }

    @Test
    fun `今日金额取今天那组再加今天的新增`() {
        val data = build(pending = listOf(add("2026-10-18", "鸡蛋", 7.5, "egg")))
        assertEquals("今天 40.00", data.todayText)
    }

    @Test
    fun `今天没开火就直说`() {
        assertEquals("今天还没花", build(data = overview.copy(byDate = emptyList())).todayText)
    }

    @Test
    fun `没有概览时不画数字`() {
        val data = build(configured = false, data = null)
        assertFalse(data.hasData)
        assertTrue(data.configured.not())
        assertEquals("—", data.monthAmount)
        assertEquals("", data.monthMeta)
        assertEquals("", data.todayText)
    }

    @Test
    fun `本月过了几天写在金额旁边`() {
        assertEquals("本月 · 40 笔 · 18/31 天", build().monthMeta)
    }

    @Test
    fun `一笔都还没记时不写笔数`() {
        val data = build(data = overview.copy(month = BillAggregate(amount = 0.0, count = 0)))
        assertEquals("本月 · 18/31 天", data.monthMeta)
    }

    @Test
    fun `日期只留月日`() {
        assertEquals("10/18", slashDate("2026-10-18"))
        assertEquals("10/08", slashDate("2026-10-08"))
        assertEquals("2026", slashDate("2026"))
    }

    @Test
    fun `进度条给的是真实比例，封顶一格`() {
        val data = build(spent = 1225.0)
        assertTrue(data.hasBudgetBar)
        assertEquals(0.6125f, data.budgetFraction, 0.0001f)
    }

    @Test
    fun `没设预算时既不画条也只写一句`() {
        val data = build(budget = BillBudget())
        assertFalse(data.hasBudgetBar)
        assertEquals(0f, data.budgetFraction, 0f)
        assertEquals("", data.percentText)
        assertEquals("没设预算", data.budgetText)
        assertFalse(data.over)
    }

    @Test
    fun `超预算写的是超了多少`() {
        val data = build(spent = 2025.0)
        assertEquals("超了 25.00", data.budgetText)
        assertTrue(data.over)
        // 条画满，但「超了多少」才是人要看的那个数
        assertEquals(1f, data.budgetFraction, 0f)
    }

    @Test
    fun `没超就写还剩多少`() {
        assertEquals("还剩 775.00", build(spent = 1225.0).budgetText)
    }

    @Test
    fun `预算按周或按年设时卡片得改口`() {
        // 卡片上面那行永远是「本月」，不加前缀这句话会被读成这个月还剩 775。
        val weekly = build(budget = BillBudget(period = BillBudgetPeriod.Week, limit = 200.0), spent = 120.0)
        assertEquals("本周还剩 80.00", weekly.budgetText)
        val yearly = build(budget = BillBudget(period = BillBudgetPeriod.Year, limit = 9000.0), spent = 9025.0)
        assertEquals("本年超了 25.00", yearly.budgetText)
        assertTrue(yearly.over)
        // 每月那一档不加前缀，和卡片上面那行是同一个口径。
        assertEquals("还剩 775.00", build(spent = 1225.0).budgetText)
    }

    @Test
    fun `只设了分类预算时不画总进度`() {
        val data = build(budget = BillBudget(perKind = mapOf("veg" to 100.0)), spent = 0.0, kindSpent = mapOf("veg" to 300.0))
        assertFalse(data.hasBudgetBar)
        assertEquals("只设了分类预算 · 蔬菜超了", data.budgetText)
        assertTrue(data.over)
    }

    @Test
    fun `离线那几笔排在最前并标成待同步`() {
        val data = build(pending = listOf(add("2026-10-18", "鸡蛋", 7.5, "egg")))
        assertEquals(
            listOf(
                WidgetBillRow("鸡蛋", "¥7.50", "待同步", BillPrefill("鸡蛋", "egg"), pending = true),
                WidgetBillRow("白菜", "¥6.00", "10/18", BillPrefill("白菜", "veg")),
                WidgetBillRow("五花肉", "¥26.50", "10/17", BillPrefill("五花肉", "meat"))
            ),
            data.rows
        )
    }

    @Test
    fun `品名截到六个字，列宽是死的`() {
        val data = build(
            data = overview.copy(
                recent = listOf(BillRecord(id = "1", date = "2026-10-18", title = "有机农家土鸡蛋", amount = 18.0, kind = "egg"))
            )
        )
        assertEquals("有机农家土鸡", data.rows.first().title)
    }

    @Test
    fun `最近买过的去重后最多三行`() {
        val data = build(
            data = overview.copy(
                recent = listOf(
                    BillRecord(id = "1", title = "白菜", kind = "veg"),
                    BillRecord(id = "2", title = "白菜", kind = "veg"),
                    BillRecord(id = "3", title = "五花肉", kind = "meat"),
                    BillRecord(id = "4", title = "鸡蛋", kind = "egg"),
                    BillRecord(id = "5", title = "苹果", kind = "fruit")
                )
            ),
            hints = listOf(TitleHint("豆腐", "veg"))
        )
        assertEquals(
            listOf(
                BillPrefill("白菜", "veg"),
                BillPrefill("五花肉", "meat"),
                BillPrefill("鸡蛋", "egg")
            ),
            data.rows.map { it.prefill }
        )
    }

    @Test
    fun `一条记录都没有才用历史候选凑，那种行没有金额`() {
        val data = build(
            data = overview.copy(recent = emptyList(), byDate = emptyList()),
            hints = listOf(TitleHint("豆腐", "veg"), TitleHint("酱油", "drinks"))
        )
        assertEquals(
            listOf(
                WidgetBillRow("豆腐", "", "常买", BillPrefill("豆腐", "veg")),
                WidgetBillRow("酱油", "", "常买", BillPrefill("酱油", "other"))
            ),
            data.rows
        )
    }

    @Test
    fun `流水只留给整卡那格`() {
        val data = overview.copy(
            recent = (1..5).map {
                BillRecord(id = "$it", date = "2026-10-18", title = "菜$it", amount = it.toDouble(), kind = "veg")
            }
        )
        assertEquals(0, build(data = data, spec = WidgetSpec.Compact).rows.size)
        // 3×2 只有两格高，硬塞一行流水会把底部的「记一笔」压扁
        assertEquals(0, build(data = data, spec = WidgetSpec.Medium).rows.size)
        assertEquals(3, build(data = data, spec = WidgetSpec.Full).rows.size)
    }

    @Test
    fun `小卡该排的行数确实一格都不剩`() {
        // 2×2 放不下流水，所以它的 rows 恒为空——就算队列里有货
        val data = build(
            spec = WidgetSpec.Compact,
            pending = listOf(add("2026-10-18", "鸡蛋", 7.5, "egg"))
        )
        assertTrue(data.rows.isEmpty())
        // 待同步的笔数还是得说，那是唯一能提醒用户「App 还没打开过」的地方
        assertEquals("1 笔待同步", data.pendingText)
    }

    @Test
    fun `读不到缓存的兜底卡片是张会说人话的空卡`() {
        val data = BillsWidgetData.unavailable()
        assertFalse(data.hasData)
        assertEquals("—", data.monthAmount)
        assertTrue(data.rows.isEmpty())
    }
}
