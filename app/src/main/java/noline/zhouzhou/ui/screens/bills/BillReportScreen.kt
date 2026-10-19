package noline.zhouzhou.ui.screens.bills

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BillDayBar
import noline.zhouzhou.data.model.BillBudgetPeriod
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.BillMonthReport
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.ui.components.NolineGuide
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel
import kotlin.math.roundToInt

/**
 * 月度报告。整月的明细一次取回（`limit=0` 才不限条数），合计、开火天数、分类占比、
 * 最贵的几笔都由客户端算——站点那边只肯给「本月」和「近 7 个月」两样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillReportScreen(
    appContainer: AppContainer,
    month: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel = typechoViewModel<BillReportViewModel>(appContainer) { BillReportViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var showBudgetSheet by remember { mutableStateOf(false) }

    LaunchedEffect(month) { viewModel.open(month) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = t.pageBg,
        topBar = {
            Column(modifier = Modifier.background(t.pageBg)) {
                Spacer(Modifier.statusBarsPadding())
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong
                        )
                    }
                    Text("月度报告", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(16.dp),
                            strokeWidth = 2.dp, color = t.accent
                        )
                    }
                    IconButton(onClick = { viewModel.shift(-1) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.ChevronLeft, "上一月",
                            modifier = Modifier.size(22.dp), tint = t.textStrong
                        )
                    }
                    Text(
                        monthTitle(state.month),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.textStrong,
                        modifier = Modifier.width(84.dp),
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = { viewModel.shift(1) },
                        enabled = state.canGoNext,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.ChevronRight, "下一月",
                            modifier = Modifier.size(22.dp),
                            tint = if (state.canGoNext) t.textStrong else t.textLight
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            val report = state.report

            if (state.issue != null && report == null) {
                ReportUnreachable(
                    issue = state.issue,
                    hint = state.issueHint,
                    onRetry = viewModel::retry,
                    onOpenSettings = onOpenSettings
                )
            } else if (report == null) {
                ReportSkeleton()
            } else {
                if (state.issue != null) {
                    Spacer(Modifier.height(6.dp))
                    ReportStaleStrip(
                        // 有存好的那份才说「下面是上次的」，不然空屏配这句话只会让人摸不着头脑。
                        hint = state.issueHint,
                        stale = state.stale,
                        onRetry = viewModel::retry
                    )
                }
                Spacer(Modifier.height(10.dp))
                ReportHero(state, report)
                Spacer(Modifier.height(10.dp))
                if (report.count > 0) {
                    // 只有「每月」那一档的预算能拿来量一个月；设成每周/每年时这里没有可比的东西，
                    // 与其画一条会骗人的进度条，不如整张卡不出现（改预算在记账首页照样能点）。
                    if (state.budgetStatus.budget.period == BillBudgetPeriod.Month) {
                        BudgetCard(
                            status = state.budgetStatus,
                            currency = state.currency,
                            subject = if (state.isCurrentMonth) "本月" else monthLabel(report.month),
                            onEdit = { showBudgetSheet = true }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    DayBarsCard(report, state.currency)
                    Spacer(Modifier.height(12.dp))
                    ReportKindsCard(report, state.currency)
                    Spacer(Modifier.height(12.dp))
                    TopItemsCard(report, state.currency)
                } else {
                    EmptyMonthCard(report.month)
                }
                Spacer(Modifier.height(14.dp))
                ReportFootnote(report, state.pendingCount, state.currency)
            }
        }
    }

    if (showBudgetSheet) {
        BillBudgetSheet(
            initial = state.budgetStatus.budget,
            currency = state.currency,
            onDismiss = { showBudgetSheet = false },
            onSave = { budget ->
                viewModel.saveBudget(budget)
                showBudgetSheet = false
            }
        )
    }
}

// ── 这个月花了多少 ──

@Composable
private fun ReportHero(state: BillReportUiState, report: BillMonthReport) {
    val t = LocalNoLine.current
    val delta = state.prevAmount?.let { report.amount - it }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                monthTitle(report.month),
                fontSize = 12.5.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                moneyText(report.amount, state.currency),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong
            )
            Spacer(Modifier.weight(1f))
            DeltaTag(delta, modifier = Modifier.padding(bottom = 7.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                if (report.count > 0) "${report.count} 笔" else null,
                if (report.days > 0) "开了火 ${report.days} 天" else null,
                state.prevAmount?.let { "上月 ${moneyText(it, state.currency)}" }
            ).joinToString(" · "),
            fontSize = 12.sp,
            color = t.textSecondary
        )
        if (report.count > 0) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(t.surfaceSunken)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    "平均每个开火的日子 ${moneyText(report.avgDay, state.currency)}；单笔均值 ${moneyText(report.avgBill, state.currency)}",
                    fontSize = 11.5.sp,
                    color = t.textMain,
                    lineHeight = 17.sp
                )
                report.max?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "最贵一笔 ${it.title} ${moneyText(it.amount, state.currency)}（${shortDate(it.date)}）",
                        fontSize = 11.5.sp,
                        color = t.textSecondary
                    )
                }
            }
        }
    }
}

// ── 按日 ──

@Composable
private fun DayBarsCard(report: BillMonthReport, currency: String) {
    if (report.series.isEmpty()) return
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每天都在花多少", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.weight(1f))
            report.busiestDay?.let {
                Text(
                    "最贵 ${shortDate(it.date)} ${moneyText(it.amount, currency)}",
                    fontSize = 11.sp,
                    color = t.textSecondary
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        DayBars(report.series)
        Spacer(Modifier.height(6.dp))
        Text(
            "横轴是这个月的每一天，贴地的那根是「那天没开火」，不是缺数据",
            fontSize = 10.5.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun DayBars(series: List<BillDayBar>) {
    val accent = LocalAccent.current
    val idle = accent.copy(alpha = 0.28f)
    val t = LocalNoLine.current
    val tallest = series.maxOf { it.amount }.coerceAtLeast(0.01)
    val busiest = series.maxByOrNull { it.amount }
    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
        ) {
            val slot = size.width / series.size
            // 这里全用 px 算，所以「最多这么宽」也得换算成 px，写死 9f 在 3x 屏上是 3dp 的细线。
            val barWidth = (slot * 0.62f).coerceAtMost(9.dp.toPx())
            series.forEachIndexed { index, day ->
                val fraction = (day.amount / tallest).toFloat().coerceIn(0f, 1f)
                val height = if (day.amount <= 0.0) 2.dp.toPx()
                else (size.height * 0.94f * fraction).coerceAtLeast(4.dp.toPx())
                val left = index * slot + (slot - barWidth) / 2f
                val hot = day.amount > 0.0 && day.date == busiest?.date
                drawRoundRect(
                    color = if (hot) accent else idle,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 三十格全写标签会糊成一片，只在 1 号和每隔 5 天落一个数字。
        Row(modifier = Modifier.fillMaxWidth()) {
            series.forEachIndexed { index, day ->
                val show = index == 0 || day.date.substring(8, 10).toIntOrNull()?.rem(5) == 0
                Text(
                    if (show) dayLabel(day.date) else "",
                    fontSize = 9.sp,
                    color = t.textLight,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

// ── 分类 ──

@Composable
private fun ReportKindsCard(report: BillMonthReport, currency: String) {
    if (report.kinds.isEmpty()) return
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("这个月都买了什么", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        report.kinds.forEach { stat ->
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
                    textAlign = TextAlign.End
                )
                Text(
                    moneyText(stat.amount, currency),
                    fontSize = 11.5.sp,
                    color = t.textStrong,
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(7.dp))
        }
        Text(
            "占比是这个月自己跟自己比，跟站点的「本月分类」算法一致",
            fontSize = 10.5.sp,
            color = t.textLight
        )
    }
}

// ── 最贵的几笔 ──

@Composable
private fun TopItemsCard(report: BillMonthReport, currency: String) {
    if (report.topItems.isEmpty()) return
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("最贵的几笔", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        report.topItems.forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(
                    shortDate(row.date),
                    fontSize = 11.sp,
                    color = t.textLight,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(58.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.title.ifBlank { "（没写品名）" },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = t.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // joinToString 的 prefix 在空列表上也会输出，商店没填就会留个光杆「·」
                    Text(
                        listOf(BillKind.label(row.kind), row.shop, row.note)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 10.5.sp,
                        color = t.textLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    moneyText(row.amount, currency),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textStrong
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = t.hairline)
        }
    }
}

// ── 空月 / 取不到 ──

@Composable
private fun EmptyMonthCard(month: String) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("${monthTitle(month)}没有买菜记录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "整月的流水取回来了，是空的——真没买菜，不是没同步。",
            fontSize = 12.sp,
            color = t.textSecondary,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun ReportUnreachable(issue: NolineIssue?, hint: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val t = LocalNoLine.current
    val blocking = issue != null && issue != NolineIssue.Network
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("这个月的账没取回来", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            hint.ifBlank { NolineGuide.body(issue) },
            fontSize = 12.5.sp,
            color = t.textSecondary,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "再试一次",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LocalAccent.current)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 15.dp, vertical = 7.dp)
            )
            if (blocking) {
                Text(
                    "去设置自检",
                    fontSize = 13.sp,
                    color = t.textSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun ReportStaleStrip(hint: String, stale: Boolean, onRetry: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(t.warnBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            (if (stale) "这个月的流水没重新取到，下面存着的是上次那一份。" else "这个月的流水没取到。") +
                if (hint.isNotBlank()) " $hint" else "",
            fontSize = 12.sp,
            color = t.warnText,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "再试一次",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = LocalAccent.current,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ReportSkeleton() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(112.dp)
                .clip(RoundedCornerShape(16.dp)).background(t.surfaceSunken)
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(16.dp)).background(t.surfaceSunken)
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .clip(RoundedCornerShape(16.dp)).background(t.surfaceSunken)
        )
    }
}

/** 说清这份报告是怎么来的——它不像概览那样是站点算好的。 */
@Composable
private fun ReportFootnote(report: BillMonthReport, pendingCount: Int, currency: String) {
    val t = LocalNoLine.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            buildString {
                append("合计 ${moneyText(report.amount, currency)}，${report.count} 笔。")
                if (report.includesPending && pendingCount > 0) {
                    append(" 其中 $pendingCount 笔还没传上站点，先把它们算进来了。")
                }
            },
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "月报是 App 按整月明细自己算的，站点只提供流水。",
            fontSize = 10.5.sp,
            color = t.textLight
        )
    }
}
