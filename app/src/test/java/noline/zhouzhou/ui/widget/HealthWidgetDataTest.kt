package noline.zhouzhou.ui.widget

import noline.zhouzhou.data.model.ChangeInfo
import noline.zhouzhou.data.model.GoalInfo
import noline.zhouzhou.data.model.HealthOverview
import noline.zhouzhou.data.model.HealthProfile
import noline.zhouzhou.data.model.HealthRecord
import noline.zhouzhou.data.model.IdealRange
import noline.zhouzhou.data.model.RecordInput
import noline.zhouzhou.data.model.WeightStats
import noline.zhouzhou.data.repository.PendingOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 体重卡片上的每个数都得能在 JVM 里算一遍：Glance 的组合过程跑不了单测，
 * 而「离线称的那条算不算现在这个数」「进度是不是和概览页同一个数」这种事，
 * 在桌面上根本看不出来。
 */
private val DateOnly = DateTimeFormatter.ofPattern("uuuu-MM-dd")

class HealthWidgetDataTest {

    /** 固定用「几天前」造日期，免得哪天跑到凌晨跑测试就撞上「今天」。 */
    private fun daysAgo(n: Int): String = LocalDate.now().minusDays(n.toLong()).format(DateOnly)

    private fun record(day: Int, weight: Double, delta: Double?, bmi: Double? = 22.5, category: String = "normal") =
        HealthRecord(
            date = daysAgo(day),
            weight = weight,
            delta = delta,
            bmi = bmi,
            category = category
        )

    private val base = HealthOverview(
        profile = HealthProfile(height = 172.0, targetWeight = 62.0),
        count = 12,
        first = daysAgo(30),
        last = daysAgo(0),
        latest = record(0, 65.5, delta = -0.4),
        stats = WeightStats(min = 65.0, max = 70.2, avg = 67.4),
        series = listOf(record(30, 70.0, null), record(15, 68.0, -2.0), record(0, 65.5, -0.4)),
        goal = GoalInfo(target = 62.0, remaining = 3.5),
        changes = mapOf(
            "d7" to ChangeInfo(span = 7, fromDate = daysAgo(7), delta = -0.6),
            "d30" to ChangeInfo(span = 30, fromDate = daysAgo(30), delta = 0.0),
            "d90" to ChangeInfo(span = 90, fromDate = null, delta = null)
        )
    )

    private fun build(
        configured: Boolean = true,
        data: HealthOverview? = base,
        pending: List<PendingOp> = emptyList(),
        spec: WidgetSpec = WidgetSpec.Full
    ) = buildHealthWidgetData(configured, data, pending, spec)

    private fun save(day: Int, weight: Double) =
        PendingOp(date = daysAgo(day), record = RecordInput(date = daysAgo(day), weight = weight))

    @Test
    fun `数字就是缓存里最新那条，涨跌和概览页同一个说法`() {
        val data = build()
        assertEquals("65.5", data.weightText)
        assertEquals("↓ 0.4 kg", data.deltaText)
        assertEquals(WidgetTrend.Down, data.trend)
        assertEquals("BMI 22.5 · 正常", data.bmiText)
    }

    @Test
    fun `今天称的和上周称的话不一样`() {
        assertEquals("今天称的", build().dateText)
        val older = base.copy(latest = record(3, 66.0, -0.2))
        assertEquals("${slashDate(older.latest!!.date)} 称的", build(data = older).dateText)
    }

    @Test
    fun `离线称的那条顶上来，BMI 和进度先不编`() {
        val data = build(pending = listOf(save(0, 64.8)))
        assertEquals("64.8", data.weightText)
        // 差值和分档都是服务器算的，本地只能说实话
        assertEquals("还没传上站点", data.deltaText)
        assertEquals(WidgetTrend.None, data.trend)
        assertEquals("", data.bmiText)
        // 队列那条还没进 series，进度不能凭空跳一格
        assertFalse(data.hasGoalBar)
        assertEquals(0f, data.goalFraction, 0f)
        assertEquals("", data.percentText)
        // 但待同步的笔数还是得说，那是唯一能提醒「App 还没打开过」的地方
        assertEquals("1 条待同步", data.pendingText)
    }

    @Test
    fun `队列里更早的那天不改写当前体重`() {
        val data = build(pending = listOf(save(9, 69.0)))
        assertEquals("65.5", data.weightText)
        assertEquals("↓ 0.4 kg", data.deltaText)
        assertEquals("1 条待同步", data.pendingText)
    }

    @Test
    fun `进度和概览页算的是同一个数`() {
        // 70.0 起、目标 62.0、现在 65.5 → 走了 (70-65.5)/(70-62) ≈ 56%
        val data = build()
        assertTrue(data.hasGoalBar)
        assertEquals(0.5625f, data.goalFraction, 0.0001f)
        assertEquals("56%", data.percentText)
        assertEquals("还要减 3.5 kg", data.goalText)
    }

    @Test
    fun `达到目标写的是守住它`() {
        val done = base.copy(goal = GoalInfo(target = 66.0, remaining = 0.0, done = true))
        val data = build(data = done)
        assertEquals("已达目标", data.goalText)
        assertTrue(data.goalDone)
        assertEquals("100%", data.percentText)
    }

    @Test
    fun `没设目标时窄卡只说短区间`() {
        val noGoal = base.copy(
            profile = HealthProfile(height = 172.0, ideal = IdealRange(low = 58.0, high = 68.0)),
            goal = null
        )
        assertEquals("理想区间 58.0 ~ 68.0 kg", build(data = noGoal).goalText)
        assertEquals("理想 58~68", build(data = noGoal, spec = WidgetSpec.Compact).goalText)
        // 没有目标就没有进度条，只剩一句区间
        assertFalse(build(data = noGoal).hasGoalBar)
    }

    @Test
    fun `既没目标也没区间`() {
        val bare = base.copy(profile = HealthProfile(height = 172.0), goal = null)
        val data = build(data = bare)
        assertEquals("还没有目标", data.goalText)
        assertEquals("", data.percentText)
    }

    @Test
    fun `变化那三行按跨度排，没基线的写无基线`() {
        assertEquals(
            listOf(
                HealthWidgetRow("7 天", "↓ 0.6", slashDate(daysAgo(7)), WidgetTrend.Down),
                HealthWidgetRow("30 天", "持平", slashDate(daysAgo(30)), WidgetTrend.Flat),
                HealthWidgetRow("90 天", "—", "无基线", WidgetTrend.None)
            ),
            build().rows
        )
    }

    @Test
    fun `一条基线都没有时那一栏整个不画`() {
        val none = base.copy(changes = mapOf("d7" to ChangeInfo(span = 7, delta = null)))
        assertTrue(build(data = none).rows.isEmpty())
        // 概览页在这种情况下也是一句话，不是三张空卡
        assertTrue(build(data = base.copy(changes = emptyMap())).rows.isEmpty())
    }

    @Test
    fun `明细只留给整卡那格`() {
        // 2 格高的宿主约 178dp，多塞一行明细会把底部那颗按钮压扁
        assertEquals(0, build(spec = WidgetSpec.Compact).rows.size)
        assertEquals(0, build(spec = WidgetSpec.Medium).rows.size)
        assertEquals(3, build(spec = WidgetSpec.Full).rows.size)
    }

    @Test
    fun `没填身高就直说要填身高`() {
        val noHeight = base.copy(latest = record(0, 65.5, delta = -0.4, bmi = null, category = ""))
        assertEquals("先填身高，才能算 BMI", build(data = noHeight).bmiText)
    }

    @Test
    fun `一条记录都没有时说人话`() {
        val empty = base.copy(count = 0, latest = null, stats = null, series = emptyList(), changes = emptyMap())
        val data = build(data = empty)
        assertFalse(data.hasData)
        assertEquals("—", data.weightText)
        assertEquals("", data.deltaText)
    }

    @Test
    fun `缓存都读不到时的兜底卡片`() {
        val data = build(configured = false, data = null)
        assertFalse(data.configured)
        assertFalse(data.hasData)
        assertEquals("—", data.weightText)
        assertEquals("", data.deltaText)
        assertEquals("", data.bmiText)
        assertTrue(data.rows.isEmpty())
        // 组合过程里真会画的那一行是「先在 App 里填好接口密钥」，兜底对象说的是同一件事
        val fallback = HealthWidgetData.unavailable()
        assertEquals(fallback.weightText, data.weightText)
        assertEquals(fallback.hasData, data.hasData)
    }

    @Test
    fun `只有队列里的货、站点还没拉过时也能画`() {
        val data = build(data = null, pending = listOf(save(0, 68.0)))
        assertTrue(data.hasData)
        assertEquals("68.0", data.weightText)
        assertEquals("还没传上站点", data.deltaText)
        assertEquals("还没有目标", data.goalText)
    }
}
