package noline.zhouzhou.ui.widget

import noline.zhouzhou.data.model.BillBudgetStatus
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.BillsOverview
import noline.zhouzhou.data.repository.PendingBill
import noline.zhouzhou.data.repository.RecordPreflight
import noline.zhouzhou.data.repository.TitleHint
import noline.zhouzhou.data.repository.unsentAdds
import noline.zhouzhou.ui.navigation.BillPrefill
import noline.zhouzhou.ui.screens.bills.moneyPlain
import noline.zhouzhou.ui.screens.bills.moneyShort
import kotlin.math.roundToInt

/**
 * 小组件上一次绘制需要的全部文案。
 *
 * 卡片只会画这里面的字符串，数字一律在 [buildBillsWidgetData] 里算完——
 * Glance 的组合过程跑不了单测，所以「算得对不对」和「画得好不好看」必须分开。
 */
data class BillsWidgetData(
    val configured: Boolean,
    val hasData: Boolean,
    val caption: String,
    val monthAmount: String,
    val todayText: String,
    val barText: String,
    val percentText: String,
    val budgetText: String,
    val over: Boolean,
    val pendingText: String,
    val chips: List<BillPrefill>
) {
    companion object {
        /** 读不到本地缓存时的兜底：不能画一张空白卡，得说清是哪儿没通。 */
        fun unavailable() = BillsWidgetData(
            configured = false, hasData = false, caption = "买菜记账",
            monthAmount = "—", todayText = "", barText = "", percentText = "",
            budgetText = "", over = false, pendingText = "", chips = emptyList()
        )
    }
}

fun buildBillsWidgetData(
    configured: Boolean,
    overview: BillsOverview?,
    status: BillBudgetStatus,
    pending: List<PendingBill>,
    hints: List<TitleHint>
): BillsWidgetData {
    val currency = overview?.currency ?: "CNY"
    val today = overview?.span?.today?.takeIf { it.length >= 10 } ?: RecordPreflight.today()
    val month = today.take(7)
    val unsent = pending.unsentAdds()

    val monthTotal = (overview?.month?.amount ?: 0.0) +
        unsent.filter { it.date.take(7) == month }.sumOf { it.amount }
    val todayTotal = (overview?.byDate?.firstOrNull { it.date == today }?.amount ?: 0.0) +
        unsent.filter { it.date == today }.sumOf { it.amount }

    val chips = buildChips(overview, hints)

    return BillsWidgetData(
        configured = configured,
        hasData = overview != null,
        caption = if (overview == null) "买菜记账" else "本月 · ${overview.span.monthFraction}",
        monthAmount = overview?.let { moneyShort(monthTotal, currency) } ?: "—",
        todayText = if (overview == null) ""
        else if (todayTotal <= 0.0) "今天还没花" else "今天 ${moneyPlain(todayTotal)}",
        barText = if (status.budget.hasMonthly) budgetBar(status.usedFraction) else "",
        percentText = if (status.budget.hasMonthly) "${(status.usedFraction * 100).roundToInt()}%" else "",
        budgetText = budgetText(status),
        over = status.anyOver,
        pendingText = if (pending.isEmpty()) "" else "${pending.size} 笔待同步",
        chips = chips
    )
}

/** 十个格子的进度条。Glance 没有 weight，画不出按比例伸缩的条，字符条反而稳。 */
internal fun budgetBar(fraction: Float, cells: Int = 10): String {
    val filled = if (fraction <= 0f) 0
    else (fraction * cells).roundToInt().coerceAtLeast(1).coerceAtMost(cells)
    return "█".repeat(filled) + "░".repeat(cells - filled)
}

private fun budgetText(status: BillBudgetStatus): String {
    if (!status.active) return "没设预算"
    val head = when {
        !status.budget.hasMonthly -> "只设了分类预算"
        status.monthlyOver -> "超了 ${moneyPlain(status.monthlyOverBy)}"
        else -> "还剩 ${moneyPlain(status.monthlyLeft)}"
    }
    val overKind = status.kindsOver.firstOrNull()?.let { "${it.label}超了" } ?: ""
    return if (overKind.isEmpty()) head else "$head · $overKind"
}

/** 常记条目：最近买过的排前面，一条都没有才退到历史候选。 */
private fun buildChips(overview: BillsOverview?, hints: List<TitleHint>): List<BillPrefill> {
    val fromRecent = overview?.recent.orEmpty().mapNotNull { row ->
        row.title.trim().takeIf { it.isNotEmpty() }
            ?.let { BillPrefill(it, BillKind.normalize(row.kind)) }
    }
    val fromHints = hints.mapNotNull { hint ->
        hint.title.trim().takeIf { it.isNotEmpty() }
            ?.let { BillPrefill(it, BillKind.normalize(hint.kind)) }
    }
    return (fromRecent + fromHints).distinctBy { it.title }.take(3)
}
