package noline.zhouzhou.ui.screens.bills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BillAggregate
import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.BillMonthStat
import noline.zhouzhou.data.model.BillRecord
import noline.zhouzhou.data.model.BillsOverview
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.BillPreflight
import noline.zhouzhou.data.repository.billDelta
import noline.zhouzhou.ui.components.NolineGuide
import noline.zhouzhou.ui.navigation.BillPrefill
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 买菜记账。首屏一个 `/api/bills` 就够：本周/本月/本年/累计、近 7 个月的柱子、
 * 本月分类占比、最近 12 天的流水全在里面，明细和分类筛选才走 `/api/bills/records`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReport: (String) -> Unit,
    /** 桌面小组件带进来的半张表单：非空就当场把记一笔面板拉开，品名和分类替用户填好。 */
    initialAdd: BillPrefill? = null,
    onPrefillConsumed: () -> Unit = {}
) {
    val viewModel = typechoViewModel<BillsViewModel>(appContainer) { BillsViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current

    var editTarget by remember { mutableStateOf<BillRowUi?>(null) }
    var showNewSheet by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf<BillPrefill?>(null) }
    var showBudgetSheet by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialAdd) {
        if (initialAdd != null) {
            preset = initialAdd
            showNewSheet = true
            // 立刻交还：连着点同一颗常记条目时，深链值没变，效果不会再跑第二次。
            onPrefillConsumed()
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            toast = it
            viewModel.consumeToast()
            delay(2800)
            toast = null
        }
    }

    val overview = state.overview

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = t.pageBg,
        topBar = {
            Column(modifier = Modifier.background(t.pageBg)) {
                Spacer(Modifier.statusBarsPadding())
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong
                        )
                    }
                    Text("买菜记账", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.width(6.dp))
                    if (state.pendingCount > 0) {
                        Text(
                            "${state.pendingCount} 笔待同步",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .clickable { viewModel.syncNow() }
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp).size(17.dp),
                            strokeWidth = 2.dp, color = t.accent
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Default.Sync, "刷新",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                containerColor = LocalAccent.current,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("记一笔") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp)
        ) {
            if (state.showSetupGuide) {
                BillsSetupGuide(
                    issue = state.issue,
                    hint = state.issueHint,
                    onOpenSettings = onOpenSettings
                )
            } else {
                if (state.issue != null && overview != null) {
                    BillsIssueStrip(state.issue, state.issueHint, onOpenSettings)
                    Spacer(Modifier.height(6.dp))
                }
                if (overview == null) {
                    BillsSkeleton()
                } else if (overview.count == 0 && !state.budgetStatus.active) {
                    NoBillYet(span = overview.span)
                } else {
                    if (overview.count > 0) {
                        MonthHeroCard(overview)
                        Spacer(Modifier.height(10.dp))
                        PeriodRow(overview)
                        Spacer(Modifier.height(12.dp))
                    }
                    // 预算卡在本月那一格里，超没超都得一眼看得见，不用点进报告才知道。
                    BudgetCard(
                        status = state.budgetStatus,
                        currency = overview.currency,
                        subject = "本月",
                        onEdit = { showBudgetSheet = true }
                    )
                    if (overview.count > 0) {
                        Spacer(Modifier.height(14.dp))
                        MonthBarsCard(overview.months, onOpenReport)
                        Spacer(Modifier.height(12.dp))
                        KindShareCard(overview)
                    }
                }

                InvalidTitlesStrip(state.invalidTitles)

                if (overview != null && overview.count > 0) {
                    Spacer(Modifier.height(18.dp))
                    FilterRow(overview = overview, selected = state.filterKind, onSelect = viewModel::setFilter)
                    Spacer(Modifier.height(10.dp))

                    val detail = state.detail
                    val shown: List<BillDayUi> = when {
                        state.showDetail && detail != null -> detail.days
                        else -> viewModel.dayGroups()
                    }
                    if (state.showDetail) {
                        state.detail?.let { detail ->
                            Text(
                                "${detail.caption} · ${detail.total} 笔 · 合计 ${moneyText(detail.amount, overview)}",
                                fontSize = 12.sp,
                                color = t.textSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }
                    }
                    shown.forEach { day -> BillDayBlock(day, overview, onClick = { editTarget = it }) }

                    if (!state.showDetail) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (state.detailLoading) "正在取全部流水…" else "展开全部流水",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = LocalAccent.current,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(enabled = !state.detailLoading) { viewModel.toggleExpanded() }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "收起，只看最近这些天",
                            fontSize = 12.5.sp,
                            color = t.textSecondary,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable {
                                    viewModel.setFilter("")
                                    viewModel.toggleExpanded()
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        BillSheet(
            existing = null,
            preset = preset,
            hints = state.hints,
            errors = state.fieldErrors,
            saving = state.saving,
            onDismiss = {
                showNewSheet = false
                preset = null
            },
            onSubmit = { inputs ->
                if (viewModel.addBills(inputs)) {
                    showNewSheet = false
                    preset = null
                    viewModel.consumeToast()
                }
            },
            onDelete = null
        )
    }

    editTarget?.let { row ->
        BillSheet(
            existing = row,
            hints = state.hints,
            errors = state.fieldErrors,
            saving = state.saving,
            onDismiss = { editTarget = null },
            onSubmit = { inputs ->
                val input = inputs.firstOrNull() ?: return@BillSheet
                if (viewModel.updateBill(
                        row.record, input.date, input.title,
                        "%.2f".format(input.amount), input.kind ?: "other",
                        input.shop.orEmpty(), input.note.orEmpty()
                    )
                ) editTarget = null
            },
            onDelete = {
                val ref = row.localRef
                editTarget = null
                viewModel.deleteBill(ref)
            }
        )
    }

    if (showBudgetSheet) {
        BillBudgetSheet(
            initial = state.budgetStatus.budget,
            currency = overview?.currency ?: "CNY",
            onDismiss = { showBudgetSheet = false },
            onSave = { budget ->
                viewModel.saveBudget(budget)
                showBudgetSheet = false
            }
        )
    }

    toast?.let { message ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                message,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .padding(bottom = 96.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(t.lightboxBg)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}

// ── 概览 ──

@Composable
private fun MonthHeroCard(overview: BillsOverview) {
    val t = LocalNoLine.current
    val month = overview.month
    val delta = billDelta(month, overview.prevMonth)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "本月",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                moneyText(month?.amount ?: 0.0, overview),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong
            )
            Spacer(Modifier.weight(1f))
            DeltaTag(delta, modifier = Modifier.padding(bottom = 7.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // 本周/本月都是「过了一半」的区间，裸报涨跌会让人以为省钱了。
                listOfNotNull(
                    month?.let { "${it.count} 笔" }?.takeIf { month.count > 0 },
                    overview.span.monthFraction.takeIf { delta != null }
                ).joinToString(" · "),
                fontSize = 12.sp,
                color = t.textSecondary
            )
        }
        month?.let { agg ->
            Spacer(Modifier.height(12.dp))
            AggregateFootnote(agg, overview)
        }
    }
}

/** 本周 / 本年 / 累计三格。服务器不做差，涨跌是客户端减出来的。 */
@Composable
private fun PeriodRow(overview: BillsOverview) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PeriodCell(Modifier.weight(1f), "本周", overview.week, billDelta(overview.week, overview.prevWeek), overview, overview.span.weekFraction)
        PeriodCell(Modifier.weight(1f), "本年", overview.year, null, overview, null)
        PeriodCell(Modifier.weight(1f), "累计", overview.totals, null, overview, null)
    }
}

@Composable
private fun PeriodCell(
    modifier: Modifier,
    label: String,
    agg: BillAggregate?,
    delta: Double?,
    overview: BillsOverview,
    fraction: String?
) {
    val t = LocalNoLine.current
    Column(
        modifier = modifier
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Text(label, fontSize = 11.5.sp, color = t.textSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            if (agg == null || agg.count == 0) "—" else moneyShort(agg.amount, overview),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = t.textStrong
        )
        Spacer(Modifier.height(3.dp))
        if (delta != null) {
            DeltaTag(delta, small = true)
        } else {
            Text(
                agg?.let { if (it.count > 0) "${it.count} 笔" else "还没记" } ?: "还没记",
                fontSize = 10.5.sp,
                color = t.textLight
            )
        }
        fraction?.let {
            Text(it, fontSize = 10.sp, color = t.textLight, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/**
 * 均摊和最大一笔。
 * `avg_day` 是「每个开了火的日子花多少」，不是除以日历天数，所以文案里要点明白。
 */
@Composable
private fun AggregateFootnote(agg: BillAggregate, overview: BillsOverview) {
    val t = LocalNoLine.current
    if (agg.count == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(t.surfaceSunken)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            "开了火的 ${agg.days} 天，平均每天 ${moneyText(agg.avgDay, overview)}；单笔均值 ${moneyText(agg.avgBill, overview)}",
            fontSize = 11.5.sp,
            color = t.textMain,
            lineHeight = 17.sp
        )
        agg.max?.let {
            Spacer(Modifier.height(3.dp))
            Text(
                "最贵一笔 ${it.title} ${moneyText(it.amount, overview)}（${shortDate(it.date)}）",
                fontSize = 11.5.sp,
                color = t.textSecondary
            )
        }
    }
}

/** 少花是好事，所以降用强调色、升用报错色。月报页共用这一个。 */
@Composable
internal fun DeltaTag(delta: Double?, small: Boolean = false, modifier: Modifier = Modifier) {
    val t = LocalNoLine.current
    val accent = LocalAccent.current
    val text = when {
        delta == null -> "—"
        kotlin.math.abs(delta) < 0.005 -> "持平"
        delta < 0 -> "↓ ${moneyPlain(kotlin.math.abs(delta))}"
        else -> "↑ ${moneyPlain(delta)}"
    }
    val color = when {
        delta == null -> t.textLight
        kotlin.math.abs(delta) < 0.005 -> t.textSecondary
        delta < 0 -> accent
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        text + if (delta != null) "" else "（无基线）",
        fontSize = if (small) 11.sp else 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = modifier
    )
}

@Composable
private fun MonthBarsCard(months: List<BillMonthStat>, onOpenReport: (String) -> Unit) {
    if (months.isEmpty()) return
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("近 7 个月", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.weight(1f))
            // 点柱子下面的月份进整月报告；这里再给一个不用找地方的入口。
            Text(
                "本月报告",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = LocalAccent.current,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onOpenReport(months.last().month) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        MonthBars(months, onOpenReport)
        Spacer(Modifier.height(6.dp))
        Text(
            "中间那个月一根柱子都没有是真没买菜，不是数据缺了；点月份能看那个月的整月报告",
            fontSize = 10.5.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
    }
}

/**
 * 横轴直接用服务器给的 7 格，包括 amount==0 的那些。
 * 自己按有数据的月份生成横轴，「没消费」和「没记录」就长成一个样了。
 */
@Composable
private fun MonthBars(months: List<BillMonthStat>, onOpenReport: (String) -> Unit) {
    val accent = LocalAccent.current
    val idle = accent.copy(alpha = 0.22f)
    val labelColor = LocalNoLine.current.textLight
    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            val slot = size.width / months.size
            val barWidth = slot * 0.5f
            val tallest = months.maxOf { it.amount }.coerceAtLeast(0.01)
            months.forEachIndexed { index, stat ->
                val fraction = (stat.amount / tallest).toFloat().coerceIn(0f, 1f)
                // 0 的月份也要出现：画一根贴地的矮桩，而不是删掉格子。
                val height = if (stat.amount <= 0.0) 3f else (size.height * 0.92f * fraction).coerceAtLeast(6f)
                val left = index * slot + (slot - barWidth) / 2f
                drawRoundRect(
                    color = if (index == months.lastIndex) accent else idle,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            months.forEachIndexed { index, stat ->
                Text(
                    monthLabel(stat.month),
                    fontSize = 10.sp,
                    color = if (index == months.lastIndex) accent else labelColor,
                    fontWeight = if (index == months.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                    // 格子本身是 Canvas，摸不准边界；标签这一行才是能点的地方。
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenReport(stat.month) }
                        .padding(vertical = 3.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun KindShareCard(overview: BillsOverview) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("本月都买了什么", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(4.dp))
        if (overview.kindsMonth.isEmpty()) {
            Text(
                "本月还没有开销，等记满几笔这里就有占比了",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        overview.kindsMonth.forEach { stat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    BillKind.label(stat.kind),
                    fontSize = 11.5.sp,
                    color = t.textMain,
                    modifier = Modifier.width(58.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.surfaceSunken)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(stat.share.toFloat().coerceIn(0f, 1f))
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(LocalAccent.current.copy(alpha = 0.75f))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(stat.share * 100).roundToInt()}%",
                    fontSize = 11.sp,
                    color = t.textSecondary,
                    modifier = Modifier.width(34.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                Text(
                    moneyText(stat.amount, overview),
                    fontSize = 11.5.sp,
                    color = t.textStrong,
                    modifier = Modifier.width(60.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}

// ── 流水 ──

@Composable
private fun FilterRow(overview: BillsOverview, selected: String, onSelect: (String) -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val chips = listOf("" to "全部") + BillKind.Codes.map { it to BillKind.label(it) }
        chips.forEach { (code, label) ->
            val used = code.isEmpty() || overview.kindsMonth.any { it.kind == code }
            val active = selected == code
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    active -> Color.White
                    !used -> t.textLight
                    else -> t.textSecondary
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) LocalAccent.current else t.cardBg)
                    .border(1.dp, if (active) Color.Transparent else t.cardBorder, RoundedCornerShape(999.dp))
                    .clickable { onSelect(if (active) "" else code) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun BillDayBlock(day: BillDayUi, overview: BillsOverview, onClick: (BillRowUi) -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(dayCaption(day.date, overview.span.today), fontSize = 12.sp, color = t.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                moneyText(day.amount, overview),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = t.textMain
            )
        }
        day.rows.forEach { row ->
            BillRowItem(row, overview, onClick = { onClick(row) })
        }
        Spacer(Modifier.height(2.dp))
        HorizontalDivider(thickness = 0.5.dp, color = t.hairline)
    }
}

@Composable
private fun BillRowItem(row: BillRowUi, overview: BillsOverview, onClick: () -> Unit) {
    val t = LocalNoLine.current
    val record = row.record
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.title.ifBlank { "（没写品名）" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = t.textStrong
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    BillKind.label(record.kind),
                    fontSize = 10.5.sp,
                    color = t.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.surfaceSunken)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                if (row.pending) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "待同步",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            val second = listOfNotNull(
                record.shop.takeIf { it.isNotBlank() },
                record.note.takeIf { it.isNotBlank() }
            )
            if (second.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    second.joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            moneyText(record.amount, overview),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (row.pending) t.textSecondary else t.textStrong
        )
    }
}

// ── 空态 / 故障 ──

/** 空态不给冷冰冰的「暂无数据」，画一只装菜的篮子。 */
@Composable
private fun NoBillYet(span: noline.zhouzhou.data.model.BillSpan) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasketIllustration()
        Spacer(Modifier.height(16.dp))
        Text("还没有买菜记录", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "回来路上顺手记一笔，月底就知道钱都花在哪些菜上了",
            fontSize = 12.5.sp,
            color = t.textSecondary,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Spacer(Modifier.height(14.dp))
        // 一条都没有时服务器把区间算好了但没数据，说清「今天是哪天」免得用户以为没连上。
        if (span.today.isNotBlank()) {
            Text("站点今天：${span.today}", fontSize = 11.sp, color = t.textLight)
        }
    }
}

@Composable
private fun BasketIllustration() {
    val accent = LocalAccent.current
    Canvas(modifier = Modifier.size(124.dp, 96.dp)) {
        val w = size.width
        val h = size.height
        // 篮身
        val body = Path().apply {
            moveTo(w * 0.16f, h * 0.42f)
            lineTo(w * 0.84f, h * 0.42f)
            lineTo(w * 0.74f, h * 0.86f)
            lineTo(w * 0.26f, h * 0.86f)
            close()
        }
        drawPath(body, color = accent.copy(alpha = 0.16f))
        drawPath(body, color = accent.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        // 提梁
        drawArc(
            color = accent.copy(alpha = 0.45f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.10f),
            size = Size(w * 0.44f, h * 0.55f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
        // 篮里露出的两颗菜
        drawCircle(color = accent.copy(alpha = 0.55f), radius = w * 0.055f, center = Offset(w * 0.40f, h * 0.40f))
        drawCircle(color = accent.copy(alpha = 0.35f), radius = w * 0.05f, center = Offset(w * 0.60f, h * 0.40f))
        // 编织纹
        (0..2).forEach { i ->
            drawLine(
                color = accent.copy(alpha = 0.28f),
                start = Offset(w * (0.21f + i * 0.03f), h * (0.56f + i * 0.09f)),
                end = Offset(w * (0.79f - i * 0.03f), h * (0.56f + i * 0.09f)),
                strokeWidth = 2f
            )
        }
    }
}

@Composable
private fun BillsSkeleton() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(96.dp)
                .clip(RoundedCornerShape(16.dp)).background(t.surfaceSunken)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Box(
                    Modifier.weight(1f).height(68.dp)
                        .clip(RoundedCornerShape(14.dp)).background(t.surfaceSunken)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(16.dp)).background(t.surfaceSunken)
        )
    }
}

@Composable
private fun InvalidTitlesStrip(titles: List<String>) {
    if (titles.isEmpty()) return
    val t = LocalNoLine.current
    AnimatedVisibility(visible = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(t.warnBg)
                .padding(12.dp)
        ) {
            Text(
                // 记账被拒的条目接口回的是品名，不是日期。
                "这几条没记上：${titles.joinToString("、")}",
                fontSize = 12.5.sp,
                color = t.warnText,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun BillsSetupGuide(issue: NolineIssue?, hint: String, onOpenSettings: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Text(NolineGuide.title(issue), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(7.dp))
        Text(NolineGuide.body(issue), fontSize = 13.sp, color = t.textSecondary, lineHeight = 20.sp)
        if (hint.isNotBlank() && issue != NolineIssue.Network) {
            Spacer(Modifier.height(8.dp))
            Text("服务器提示：$hint", fontSize = 11.5.sp, color = t.textLight, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "去设置页自检",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .clip(CircleShape)
                .background(LocalAccent.current)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BillsIssueStrip(issue: NolineIssue?, hint: String, onOpenSettings: () -> Unit) {
    val t = LocalNoLine.current
    val blocking = issue != null && issue != NolineIssue.Network
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                NolineGuide.title(issue),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (blocking) {
                Text(
                    "去设置",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalAccent.current,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "下面是上次同步到的内容" +
                if (hint.isNotBlank() && issue != NolineIssue.Network) "：$hint" else "，联网或配好后会自动刷新",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
            lineHeight = 17.sp
        )
    }
}

// ── 文案小工具 ──
// 金额和日期的格式化在 BillsFormat.kt，月报页要用同一套。

private fun dayCaption(date: String, serverToday: String): String {
    if (date.isNotBlank() && date == serverToday) return "今天"
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    if (parsed == LocalDate.now().minusDays(1)) return "昨天"
    val weekday = when (parsed.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
    return "${shortDate(date)} · $weekday"
}
