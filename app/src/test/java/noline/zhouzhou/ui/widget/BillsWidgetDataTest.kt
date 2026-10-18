package noline.zhouzhou.ui.widget

import noline.zhouzhou.data.model.BillAggregate
import noline.zhouzhou.data.model.BillBudget
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
            BillRecord(id = "r2", date = "2026-10-18", title = "五花肉", amount = 26.5, kind = "meat")
        ),
        span = span
    )

    private fun build(
        configured: Boolean = true,
        data: BillsOverview? = overview,
        spent: Double = 1200.0,
        budget: BillBudget = BillBudget(monthly = 2000.0),
        kindSpent: Map<String, Double> = emptyMap(),
        pending: List<PendingBill> = emptyList(),
        hints: List<TitleHint> = emptyList()
    ) = buildBillsWidgetData(
        configured = configured,
        overview = data,
        status = billBudgetStatus(budget, spent, kindSpent),
        pending = pending,
        hints = hints
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
        assertEquals("买菜记账", data.caption)
        assertEquals("—", data.monthAmount)
        assertEquals("", data.todayText)
    }

    @Test
    fun `本月过了几天写在标题上`() {
        assertEquals("本月 · 18/31 天", build().caption)
    }

    @Test
    fun `进度条十个格子`() {
        assertEquals("░".repeat(10), budgetBar(0f))
        assertEquals("█".repeat(5) + "░".repeat(5), budgetBar(0.5f))
        // 才花了一点也要看得见一格，全空会让人以为没在记
        assertEquals("█" + "░".repeat(9), budgetBar(0.02f))
        assertEquals("█".repeat(10), budgetBar(1f))
        assertEquals("█".repeat(10), budgetBar(2.5f))
    }

    @Test
    fun `没设预算时既不画条也只写一句`() {
        val data = build(budget = BillBudget())
        assertEquals("", data.barText)
        assertEquals("", data.percentText)
        assertEquals("没设预算", data.budgetText)
        assertFalse(data.over)
    }

    @Test
    fun `超预算写的是超了多少`() {
        val data = build(spent = 2025.0)
        assertEquals("超了 25.00", data.budgetText)
        assertTrue(data.over)
    }

    @Test
    fun `没超就写还剩多少`() {
        assertEquals("还剩 775.00", build(spent = 1225.0).budgetText)
    }

    @Test
    fun `只设了分类预算时不画总进度`() {
        val data = build(budget = BillBudget(perKind = mapOf("veg" to 100.0)), spent = 0.0, kindSpent = mapOf("veg" to 300.0))
        assertEquals("", data.barText)
        assertEquals("只设了分类预算 · 蔬菜超了", data.budgetText)
        assertTrue(data.over)
    }

    @Test
    fun `常记条目取最近买过的去重最多三个`() {
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
            listOf(BillPrefill("白菜", "veg"), BillPrefill("五花肉", "meat"), BillPrefill("鸡蛋", "egg")),
            data.chips
        )
    }

    @Test
    fun `一条记录都没有才用历史候选凑`() {
        val data = build(
            data = overview.copy(recent = emptyList(), byDate = emptyList()),
            hints = listOf(TitleHint("豆腐", "veg"), TitleHint("酱油", "drinks"))
        )
        assertEquals(listOf(BillPrefill("豆腐", "veg"), BillPrefill("酱油", "other")), data.chips)
    }

    @Test
    fun `读不到缓存的兜底卡片是张会说人话的空卡`() {
        val data = BillsWidgetData.unavailable()
        assertEquals("买菜记账", data.caption)
        assertFalse(data.hasData)
        assertTrue(data.chips.isEmpty())
    }
}
