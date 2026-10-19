package noline.zhouzhou.ui.widget

import noline.zhouzhou.data.model.BillBudgetStatus
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.BillRecord
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
    val monthAmount: String,
    val monthMeta: String,
    val todayText: String,
    val pendingText: String,
    val hasBudgetBar: Boolean,
    val budgetFraction: Float,
    val budgetText: String,
    val percentText: String,
    val over: Boolean,
    val rows: List<WidgetBillRow>
) {
    companion object {
        /** 读不到本地缓存时的兜底：不能画一张空白卡，得说清是哪儿没通。 */
        fun unavailable() = BillsWidgetData(
            configured = false, hasData = false, monthAmount = "—",
            monthMeta = "", todayText = "", pendingText = "", hasBudgetBar = false,
            budgetFraction = 0f, budgetText = "", percentText = "", over = false,
            rows = emptyList()
        )
    }
}

/**
 * 桌面选取器里的三种规格。
 *
 * 规格不只是「排几行流水」——桌面给的高度是格子数算死的（2 格 ≈ 178dp），
 * 3×2 里硬塞一行流水会把底部的「记一笔」压扁，所以流水只留给 3×3 那一格。
 * 字号和按钮高度同理，都得跟着格子走，写在枚举里比散在组合过程里好核对。
 */
enum class WidgetSpec(
    val rows: Int,
    /** 窄卡（2×2）放不下「本月 · 40 笔 · 18/31 天」这种长串，Glance 不会打点，只会裁掉。 */
    val wide: Boolean,
    val amountSp: Float,
    val buttonDp: Int
) {
    /** 2×2：本月合计 + 今天 + 记一笔 */
    Compact(0, false, 24f, 34),

    /** 3×2：再来一条预算进度和百分比 */
    Medium(0, true, 26f, 36),

    /** 3×3：整卡，最近买过的排三行 */
    Full(3, true, 27f, 38)
}

/** 卡片上的一行流水：点它是「再买一次」，所以得带着品名和分类。 */
data class WidgetBillRow(
    val title: String,
    val amountText: String,
    val meta: String,
    val prefill: BillPrefill,
    /** 这条还躺在本机队列里，金额标成提醒色，免得看着像已经记上了。 */
    val pending: Boolean = false
)

fun buildBillsWidgetData(
    configured: Boolean,
    overview: BillsOverview?,
    status: BillBudgetStatus,
    pending: List<PendingBill>,
    hints: List<TitleHint>,
    spec: WidgetSpec = WidgetSpec.Full
): BillsWidgetData {
    val currency = overview?.currency ?: "CNY"
    val today = overview?.span?.today?.takeIf { it.length >= 10 } ?: RecordPreflight.today()
    val month = today.take(7)
    val unsent = pending.unsentAdds()

    val monthTotal = (overview?.month?.amount ?: 0.0) +
        unsent.filter { it.date.take(7) == month }.sumOf { it.amount }
    val todayTotal = (overview?.byDate?.firstOrNull { it.date == today }?.amount ?: 0.0) +
        unsent.filter { it.date == today }.sumOf { it.amount }

    val monthCount = overview?.month?.count ?: 0
    val hasBar = status.budget.hasMonthly
    val empty = overview == null

    return BillsWidgetData(
        configured = configured,
        hasData = !empty,
        monthAmount = overview?.let { moneyShort(monthTotal, currency) } ?: "—",
        monthMeta = if (empty) ""
        else listOfNotNull(
            "本月",
            "${monthCount} 笔".takeIf { monthCount > 0 },
            overview.span.monthFraction
        ).joinToString(" · "),
        todayText = if (empty) ""
        else if (todayTotal <= 0.0) "今天还没花" else "今天 ${moneyPlain(todayTotal)}",
        pendingText = if (pending.isEmpty()) "" else "${pending.size} 笔待同步",
        hasBudgetBar = hasBar,
        budgetFraction = status.usedFraction,
        budgetText = budgetText(status),
        percentText = if (hasBar) "${(status.usedFraction * 100).roundToInt()}%" else "",
        over = status.anyOver,
        rows = buildRows(unsent, overview, hints, currency, spec.rows)
    )
}

/** `2026-10-08` → `10/08`，比「10月08日」省下的那点宽度够放一个品名。 */
internal fun slashDate(date: String): String =
    if (date.length >= 10) "${date.substring(5, 7)}/${date.substring(8, 10)}" else date

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

/**
 * 最近买过的那几行：本机还没传上去的排最前（它们才是用户刚做的事），
 * 再往后是站点回的近几日流水；一条记录都没有才退到历史候选，那种行没有金额。
 *
 * 品名列占的宽度是死的，超长只能在算文案时截断，Glance 不会自己打点。
 */
internal const val RowTitleMaxChars = 6

private fun buildRows(
    unsent: List<BillRecord>,
    overview: BillsOverview?,
    hints: List<TitleHint>,
    currency: String,
    rowCount: Int
): List<WidgetBillRow> {
    val fromUnsent = unsent.sortedByDescending { it.date }.map {
        WidgetBillRow(
            title = it.title.trim(),
            amountText = moneyShort(it.amount, currency),
            meta = "待同步",
            prefill = BillPrefill(it.title, BillKind.normalize(it.kind)),
            pending = true
        )
    }
    val fromRecent = overview?.recent.orEmpty().mapNotNull { row ->
        row.title.trim().takeIf { it.isNotEmpty() }?.let {
            WidgetBillRow(
                title = it,
                amountText = moneyShort(row.amount, currency),
                meta = slashDate(row.date),
                prefill = BillPrefill(it, BillKind.normalize(row.kind))
            )
        }
    }
    val fromHints = hints.mapNotNull { hint ->
        hint.title.trim().takeIf { it.isNotEmpty() }?.let {
            WidgetBillRow(
                title = it,
                amountText = "",
                meta = "常买",
                prefill = BillPrefill(it, BillKind.normalize(hint.kind))
            )
        }
    }
    return (fromUnsent + fromRecent + fromHints)
        .distinctBy { it.title }
        .take(rowCount)
        .map { it.copy(title = it.title.take(RowTitleMaxChars)) }
}
