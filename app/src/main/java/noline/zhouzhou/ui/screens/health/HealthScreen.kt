package noline.zhouzhou.ui.screens.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.ChangeInfo
import noline.zhouzhou.data.model.HealthOverview
import noline.zhouzhou.data.model.HealthRecord
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.ui.components.NolineGuide
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.typechoViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    appContainer: AppContainer,
    onOpenSettings: () -> Unit
) {
    val viewModel = typechoViewModel<HealthViewModel>(appContainer) { HealthViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    var sheetFor by remember { mutableStateOf<HealthRecord?>(null) }
    var showNewSheet by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            toast = it
            viewModel.consumeToast()
            delay(2600)
            toast = null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HealthHeader(
                loading = state.loading,
                pendingCount = state.pendingCount,
                onRefresh = { viewModel.refresh() },
                onSync = { viewModel.syncNow() }
            )
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
                SetupGuide(issue = state.issue, hint = state.issueHint, onOpenSettings = onOpenSettings)
            } else {
                // 有旧数据就别把页面变成错误页，但也不能装作一切正常：顶部压一条横条。
                if (state.issue != null && state.overview != null) {
                    IssueStrip(issue = state.issue, hint = state.issueHint, onOpenSettings = onOpenSettings)
                    Spacer(Modifier.height(4.dp))
                }
                val overview = state.overview
                if (overview == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
                } else if (overview.count == 0) {
                    NoRecordYet()
                } else {
                    WeightHeroCard(overview.latest, overview.profile)
                    Spacer(Modifier.height(14.dp))
                    GoalCard(overview)
                    Spacer(Modifier.height(12.dp))
                    ChangeChips(overview)
                    Spacer(Modifier.height(16.dp))
                    ChartCard(overview.series)
                    overview.stats?.let {
                        Spacer(Modifier.height(12.dp))
                        StatsRow(it.min, it.max, it.avg, overview.first, overview.last)
                    }
                }

                AnimatedVisibility(visible = state.invalidDates.isNotEmpty()) {
                    val dates = state.invalidDates.filter { it.isNotBlank() }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            if (dates.isEmpty()) "有一条记录的日期不被接受"
                            else "这些日期被服务器退回：${dates.joinToString("、")}",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (overview != null && overview.count > 0) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "全部记录",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    state.records.forEach { record ->
                        RecordRow(record = record, onClick = { sheetFor = record })
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        RecordSheet(
            existing = null,
            errors = state.fieldErrors,
            saving = state.saving,
            onDismiss = { showNewSheet = false },
            onSubmit = { d, w, f, n -> viewModel.save(d, w, f, n).also { if (it) viewModel.consumeToast() } },
            onDelete = {}
        )
    }

    sheetFor?.let { selected ->
        RecordSheet(
            existing = selected,
            errors = state.fieldErrors,
            saving = state.saving,
            onDismiss = { sheetFor = null },
            onSubmit = { d, w, f, n -> viewModel.save(d, w, f, n) },
            onDelete = { date ->
                sheetFor = null
                viewModel.delete(date)
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
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xCC33241B))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun HealthHeader(
    loading: Boolean,
    pendingCount: Int,
    onRefresh: () -> Unit,
    onSync: () -> Unit
) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Spacer(Modifier.statusBarsPadding().height(0.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "健康记录",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            if (pendingCount > 0) {
                Text(
                    "$pendingCount 条待同步",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(onClick = onSync)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "刷新",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun GoalCard(overview: HealthOverview) {
    val goal = overview.goal
    val ideal = overview.profile.ideal
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        when {
            goal != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        goal.remainingLabel,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (goal.done) accent else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "目标 ${"%.1f".format(goal.target)} kg",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                val start = overview.series.firstOrNull()?.weight ?: goal.target
                val progress = if (start <= goal.target) 1f
                else ((start - (overview.latest?.weight ?: start)) / (start - goal.target))
                    .coerceIn(0.0, 1.0).toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            ideal != null -> {
                Text(
                    "理想区间 ${"%.1f".format(ideal.low)} ~ ${"%.1f".format(ideal.high)} kg",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "博客后台还没设目标体重，先按身高给个参考区间",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Text("还没有目标", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在博客后台填上目标体重，这里就能看到还差多少",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChangeChips(overview: HealthOverview) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("d7" to "7 天", "d30" to "30 天", "d90" to "90 天").forEach { (key, label) ->
            ChangeChip(label, overview.changes[key], Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChangeChip(label: String, change: ChangeInfo?, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val delta = change?.delta
    val text = when {
        change == null || delta == null -> "—"
        kotlin.math.abs(delta) < 0.05 -> "持平"
        delta < 0 -> "↓ ${"%.1f".format(kotlin.math.abs(delta))}"
        else -> "↑ ${"%.1f".format(kotlin.math.abs(delta))}"
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (delta != null && delta < 0) accent else MaterialTheme.colorScheme.onSurface
        )
        Text(
            change?.fromDate?.drop(5)?.replace("-", "/") ?: "无基线",
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun ChartCard(series: List<HealthRecord>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Text("趋势", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        WeightTrendChart(series)
    }
}

@Composable
private fun StatsRow(min: Double, max: Double, avg: Double, first: String?, last: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("最低" to min, "最高" to max, "平均" to avg).forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.1f".format(value)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (first != null && last != null) {
        Text(
            "从 $first 记到 $last",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun RecordRow(record: HealthRecord, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.date, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${"%.1f".format(record.weight)} kg",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        record.deltaLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (record.fat != null || record.note.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            record.fat?.let { "体脂 ${"%.1f".format(it)}%" },
                            record.note.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (record.categoryLabel.isNotBlank()) {
                Text(
                    record.categoryLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/** 一条记录都没有时的引导。空态不给冷冰冰的「暂无数据」，画一台小秤。 */
@Composable
private fun NoRecordYet() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SmallScaleIllustration()
        Spacer(Modifier.height(18.dp))
        Text("还没有体重记录", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "每天早起称一次，两周就能看到线往哪边走",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun SmallScaleIllustration() {
    val accent = LocalAccent.current
    androidx.compose.foundation.Canvas(modifier = Modifier.size(120.dp, 96.dp)) {
        val w = size.width
        val h = size.height
        // 秤体
        drawRoundRect(
            color = accent.copy(alpha = 0.14f),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.32f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f)
        )
        // 表盘
        drawCircle(
            color = Color.White,
            radius = w * 0.16f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.30f)
        )
        drawCircle(
            color = accent.copy(alpha = 0.55f),
            radius = w * 0.16f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.30f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
        // 指针
        drawLine(
            color = accent,
            start = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.30f),
            end = androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.20f),
            strokeWidth = 4f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        // 刻度数字位
        drawCircle(
            color = accent.copy(alpha = 0.35f),
            radius = 5f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.72f)
        )
    }
}

/**
 * 画的是上次的缓存，但接口现在读不通——一句话说明「这些数据是旧的」，别默默骗人。
 * 网络抖动不算毛病，只留一行小字；配置类问题才给「去设置」的出口。
 */
@Composable
private fun IssueStrip(issue: NolineIssue?, hint: String, onOpenSettings: () -> Unit) {
    val blocking = issue != null && issue != NolineIssue.Network
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = NolineGuide.title(issue),
                fontSize = 13.5.sp,
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
            text = "下面是上次同步到的内容" +
                if (hint.isNotBlank() && issue != NolineIssue.Network) "：$hint" else "，联网或配好后会自动刷新",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
            lineHeight = 17.sp
        )
    }
}

/** 未配置 / 接口关闭 / 只读 / 密钥错，四种情况说四种话。 */
@Composable
private fun SetupGuide(issue: NolineIssue?, hint: String, onOpenSettings: () -> Unit) {
    val title = NolineGuide.title(issue)
    val body = NolineGuide.body(issue)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        if (hint.isNotBlank() && issue != NolineIssue.Network) {
            Spacer(Modifier.height(8.dp))
            Text(
                "服务器提示：$hint",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(16.dp))
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
