package noline.zhouzhou.ui.widget

import noline.zhouzhou.data.model.ChangeInfo
import noline.zhouzhou.data.model.GoalInfo
import noline.zhouzhou.data.model.HealthOverview
import noline.zhouzhou.data.model.HealthRecord
import noline.zhouzhou.data.model.IdealRange
import noline.zhouzhou.data.model.goalPercentText
import noline.zhouzhou.data.model.goalProgress
import noline.zhouzhou.data.repository.PendingOp
import noline.zhouzhou.data.repository.RecordPreflight
import kotlin.math.abs

/**
 * 健康卡片上一次绘制需要的全部文案，和 [BillsWidgetData] 同一套做法：
 * 数字全在这里算完，Glance 只负责把字符串画出来——组合过程跑不了单测，
 * 「离线称的那条算不算现在这个数」这种事必须在 JVM 里就能验。
 *
 * 能画几行由 [WidgetSpec] 决定（两种卡片共用那份规格）：2 格高的宿主只有约 178dp，
 * 多塞一行明细会把底部按钮压扁，所以 7/30/90 天那三行只留给 3×3 那一格。
 */
data class HealthWidgetData(
    val configured: Boolean,
    val hasData: Boolean,
    /** 大号数字本体，不带单位（"68.5"）。 */
    val weightText: String,
    /** 涨跌那一句，用的就是概览页那个 `deltaLabel`。 */
    val deltaText: String,
    val trend: WidgetTrend,
    /** "今天称的" / "10/17 称的"。 */
    val dateText: String,
    val pendingText: String,
    /** 只有三格高那张放得下这行；算不出来时是空串，画的人据此跳过。 */
    val bmiText: String,
    val hasGoalBar: Boolean,
    val goalFraction: Float,
    val goalText: String,
    val percentText: String,
    val goalDone: Boolean,
    val rows: List<HealthWidgetRow>
) {
    companion object {
        /** 读不到本地缓存时的兜底：不能画一张空白卡，得说清是哪儿没通。 */
        fun unavailable() = HealthWidgetData(
            configured = false, hasData = false, weightText = "—", deltaText = "",
            trend = WidgetTrend.None, dateText = "", pendingText = "", bmiText = "",
            hasGoalBar = false, goalFraction = 0f, goalText = "", percentText = "",
            goalDone = false, rows = emptyList()
        )
    }
}

/** 卡片上的一行「变化」：这几天减了多少、从哪天算起的。 */
data class HealthWidgetRow(
    val label: String,
    val value: String,
    val meta: String,
    val trend: WidgetTrend
)

/** 涨跌的方向，只决定颜色；文案一律用模型里那套说法。 */
enum class WidgetTrend { Down, Flat, Up, None }

/** 概览页那三张 chip 的键名和叫法，卡片照抄，不自创第四种跨度。 */
private val ChangeSpans = listOf("d7" to "7 天", "d30" to "30 天", "d90" to "90 天")

fun buildHealthWidgetData(
    configured: Boolean,
    overview: HealthOverview?,
    pending: List<PendingOp>,
    spec: WidgetSpec = WidgetSpec.Full
): HealthWidgetData {
    val latest = overview?.latest
    // 队列里那条日期不小于缓存的记录时，它才是「现在」——离线称完重，
    // 卡片还画站点上那个旧数，等于当面骗人。
    val newestLocal = pending.mapNotNull { it.record }.maxByOrNull { it.date }
    val fromLocal = newestLocal != null && newestLocal.date >= latest?.date.orEmpty()

    val weight: Double? = when {
        fromLocal -> newestLocal?.weight
        latest != null -> latest.weight
        else -> null
    }
    val dateOf = if (fromLocal) newestLocal?.date else latest?.date
    val goal = overview?.goal
    // 进度条是照缓存里那几条算的，队列那条还没进 series，先不画——
    // 数字可以本地顶上，进度不能凭空跳一格。
    val hasBar = goal != null && weight != null && !fromLocal
    val progress = if (hasBar) overview?.let { goalProgress(it) } ?: 0f else 0f

    return HealthWidgetData(
        configured = configured,
        hasData = weight != null,
        weightText = weight?.let { oneDecimal(it) } ?: "—",
        deltaText = when {
            weight == null -> ""
            fromLocal -> "还没传上站点"
            else -> latest?.deltaLabel.orEmpty()
        },
        trend = if (fromLocal) WidgetTrend.None else trendOf(latest?.delta),
        dateText = when {
            weight == null || dateOf == null -> ""
            dateOf == RecordPreflight.today() -> "今天称的"
            else -> "${slashDate(dateOf)} 称的"
        },
        pendingText = if (pending.isEmpty()) "" else "${pending.size} 条待同步",
        // 体重要传给服务器才算 BMI 分档，本地那条只能空着这一行
        bmiText = if (fromLocal) "" else bmiText(latest),
        hasGoalBar = hasBar,
        goalFraction = progress,
        goalText = goalText(goal, overview?.profile?.ideal, spec.wide),
        percentText = if (hasBar) goalPercentText(progress, goal?.done == true) else "",
        goalDone = goal?.done == true,
        rows = if (spec.rows == 0) emptyList() else changeRows(overview, spec.rows)
    )
}

/** 涨跌方向 → 卡片上那支颜色。持平读不出方向，就跟着正文走。 */
private fun trendOf(delta: Double?): WidgetTrend = when {
    delta == null -> WidgetTrend.None
    abs(delta) < 0.05 -> WidgetTrend.Flat
    delta < 0 -> WidgetTrend.Down
    else -> WidgetTrend.Up
}

/** 体重一律 1 位小数，和概览页、和站上的趋势图同一个口径。 */
private fun oneDecimal(value: Double): String = "%.1f".format(value)

private fun bmiText(record: HealthRecord?): String = when {
    record == null -> ""
    record.bmi == null -> "先填身高，才能算 BMI"
    else -> listOfNotNull(
        "BMI ${oneDecimal(record.bmi)}",
        record.categoryLabel.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
}

/**
 * 目标那一行的三种话，和概览页那张目标卡一致：有目标说还差多少，
 * 没目标只有理想区间就说区间，两样都没有才说「还没有目标」。
 * 窄卡放不下整串区间，缩成「理想 58~68」。
 */
private fun goalText(goal: GoalInfo?, ideal: IdealRange?, wide: Boolean): String =
    when {
        goal != null -> goal.remainingLabel
        ideal != null -> if (wide)
            "理想区间 ${oneDecimal(ideal.low)} ~ ${oneDecimal(ideal.high)} kg"
        else
            "理想 ${ideal.low.shortKg()}~${ideal.high.shortKg()}"
        else -> "还没有目标"
    }

/** 窄卡上那格宽度只够整数，区间本来就是参考值，不差那 0.5。 */
private fun Double.shortKg(): String = "%.1f".format(this).removeSuffix(".0")

/**
 * 7/30/90 天各减了多少。一条基线都没有时不给三行「— / 无基线」，
 * 那和概览页一样收成一句话——卡片上这一栏整个不画。
 */
private fun changeRows(overview: HealthOverview?, limit: Int): List<HealthWidgetRow> {
    val changes = overview?.changes ?: return emptyList()
    if (ChangeSpans.none { changes[it.first]?.delta != null }) return emptyList()
    return ChangeSpans.take(limit).map { (key, label) ->
        val change = changes[key]
        HealthWidgetRow(
            label = label,
            value = changeValue(change),
            meta = change?.fromDate?.takeIf { it.isNotBlank() }?.let { slashDate(it) } ?: "无基线",
            trend = trendOf(change?.delta)
        )
    }
}

/** chip 上不带单位——三个数并排站着，kg 写三遍只是占地方。 */
private fun changeValue(change: ChangeInfo?): String {
    val delta = change?.delta ?: return "—"
    return when {
        abs(delta) < 0.05 -> "持平"
        delta < 0 -> "↓ ${oneDecimal(abs(delta))}"
        else -> "↑ ${oneDecimal(abs(delta))}"
    }
}
