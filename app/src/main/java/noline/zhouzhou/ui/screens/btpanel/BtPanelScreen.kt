package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtField
import noline.zhouzhou.data.model.BtRow
import noline.zhouzhou.data.model.BtTable
import noline.zhouzhou.data.remote.BtServiceOp
import noline.zhouzhou.data.repository.BtDashboard
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel
import kotlinx.coroutines.delay

/** 要过确认门才能发出去的动作。 */
private sealed interface PendingAction {
    data class Service(val name: String, val op: BtServiceOp) : PendingAction
    data object ReloadPanel : PendingAction
    data class RunTask(val row: BtRow) : PendingAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtPanelScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtPanelViewModel>(appContainer) { BtPanelViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var editing by remember { mutableStateOf(!state.configured) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }
    LaunchedEffect(state.configured) {
        if (state.configured) editing = false
    }
    // 读到一次看板就把连接设置收起来：人接下来要看的是数据，不是那三个输入框。
    LaunchedEffect(state.dashboard?.fetchedAt) {
        if (state.dashboard != null) editing = false
    }

    // 停止和重启服务、重载面板都要人亲手打一遍关键词；重载单个服务和「立即执行」只问一句。
    when (val action = pending) {
        is PendingAction.Service -> ConfirmAction(
            title = "${action.op.label}${action.name}？",
            body = "这条指令会直接发到面板，${action.op.label}的是服务器上那个真实的服务，" +
                "不是 App 里的某个开关。站点会短暂断流。",
            keyword = action.op.label,
            mustType = action.op == BtServiceOp.Stop || action.op == BtServiceOp.Restart,
            onConfirm = {
                viewModel.runService(action.op)
                pending = null
            },
            onDismiss = { pending = null }
        )
        is PendingAction.ReloadPanel -> ConfirmAction(
            title = "重载面板本身？",
            body = "只重读面板配置、不动网站服务。期间网页端会短暂打不开，App 这边不受影响。",
            keyword = "重载",
            mustType = true,
            onConfirm = {
                viewModel.reloadPanel()
                pending = null
            },
            onDismiss = { pending = null }
        )
        is PendingAction.RunTask -> ConfirmAction(
            title = "立刻执行「${action.row.title}」？",
            body = "跑的是这条任务在面板里配好的脚本，干什么由面板那边说了算。",
            keyword = "执行",
            mustType = false,
            onConfirm = {
                viewModel.runTask(action.row)
                pending = null
            },
            onDismiss = { pending = null }
        )
        null -> Unit
    }

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
                            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong
                        )
                    }
                    Text("宝塔面板", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    if (state.configured) {
                        IconButton(onClick = { editing = !editing }) {
                            Icon(
                                Icons.Default.Build, contentDescription = "改配置",
                                modifier = Modifier.size(19.dp),
                                tint = if (editing) t.accent else t.textSecondary
                            )
                        }
                        IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                            Icon(
                                Icons.Default.Refresh, contentDescription = "重新读取",
                                modifier = Modifier.size(20.dp), tint = t.textSecondary
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = t.accent,
                    trackColor = t.hairline
                )
                Spacer(Modifier.height(10.dp))
            }

            state.failure?.let { FailureCard(it) }

            if (state.note.isNotBlank()) {
                Text(
                    state.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textMain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noLineCard(RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            if (editing) {
                ConfigCard(state, viewModel)
                Spacer(Modifier.height(14.dp))
            }

            state.dashboard?.let { DashboardCard(it) }

            if (!state.configured && !state.busy && state.dashboard == null) {
                EmptyHint()
            }

            // 没真读到看板之前不摆表格和服务按钮：配置存了不等于连上了，
            // 这时候给一张「站点 · 0 条」的空表，看着像面板说它是 0。
            if (state.dashboard != null) {
                Spacer(Modifier.height(14.dp))
                TableCard(state, viewModel) { pending = it }
                Spacer(Modifier.height(14.dp))
                ServiceCard(state, viewModel) { pending = it }

                if (state.logTitle.isNotBlank()) {
                    LogDialog(
                        title = state.logTitle,
                        text = state.logText.ifBlank { "面板没给出日志内容。" },
                        onClose = viewModel::closeLog
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ConfigCard(state: BtPanelUiState, viewModel: BtPanelViewModel) {
    val t = LocalNoLine.current
    var site by remember(state.site) { mutableStateOf(state.site) }
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var trust by remember(state.trustSelfSigned) { mutableStateOf(state.trustSelfSigned) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text("连接设置", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(4.dp))
        Text(
            "面板「网站 → 接口 / API 通信」里打开总开关，把密钥复制过来。" +
                "地址要把安全入口一起填全，形如 https://1.2.3.4:8888/入口码。",
            fontSize = 12.sp,
            color = t.textSecondary,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = site,
            onValueChange = { site = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("面板地址") },
            placeholder = { Text("https://1.2.3.4:8888/入口码") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.medium,
            colors = noLineFieldColors()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (state.keyMasked.isBlank()) "接口密钥" else "留空表示沿用已存的：${state.keyMasked}") },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.medium,
            colors = noLineFieldColors()
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = trust, onCheckedChange = { trust = it })
            Text("信任自签证书", fontSize = 13.sp, color = t.textMain)
        }
        if (trust) {
            Text(
                "面板多用自签证书，不打开这一条握手会直接失败。但打开之后 App 就不再核对" +
                    "「对面到底是不是你那台面板」，只建议在自己能控制的网络里用。",
                fontSize = 11.5.sp,
                color = t.warnText,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { viewModel.saveAndCheck(site, key, trust) },
                enabled = !state.busy
            ) { Text(if (state.busy) "正在读取…" else "保存并自检") }
            if (state.configured) {
                TextButton(onClick = viewModel::clearConfig) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 自检失败时摊三段：是什么、那句话、下一步做什么。 */
@Composable
private fun FailureCard(failure: BtFailure) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.warnBg, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(17.dp), tint = t.warnText)
            Spacer(Modifier.width(8.dp))
            Text(failure.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.warnText)
        }
        if (failure.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // 「面板原话」这四个字只配用在面板真说过的那句上，连不上之类是我们的判断，不冒充供词。
            val line = if (failure.fromPanel) "面板原话：${failure.detail}" else failure.detail
            Text(line, fontSize = 12.sp, color = t.textMain, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(failure.advice, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EmptyHint() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Text("还没连上面板", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "填好地址和密钥，点「保存并自检」。这一页只读看板加几个低风险动作——" +
                "建站、删站、建库、删库都不在这里，那种事还是到面板里亲手做。",
            fontSize = 12.5.sp,
            color = t.textSecondary,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun DashboardCard(dashboard: BtDashboard) {
    val t = LocalNoLine.current
    val system = dashboard.system
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text("服务器状态", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(2.dp))
        Text(
            "读取于 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(dashboard.fetchedAt)),
            fontSize = 11.sp,
            color = t.textLight
        )
        Spacer(Modifier.height(12.dp))

        UsageLine("CPU", system.cpuPercent?.let { "${it.toInt()}%" } ?: Missing, fraction(system.cpuPercent))
        Spacer(Modifier.height(10.dp))
        UsageLine(
            "内存",
            system.memText.ifBlank { Missing },
            fractionOf(system.memUsedGb, system.memTotalGb)
        )
        Spacer(Modifier.height(10.dp))
        val loads = listOf(system.loadOne, system.loadFive, system.loadFifteen).filter { it.isNotBlank() }
        UsageLine(
            "负载",
            (if (loads.isEmpty()) Missing else loads.joinToString(" / ")) +
                system.loadLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            null
        )
        if (system.uptimeText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            UsageLine("运行时长", system.uptimeText, null)
        }

        dashboard.network?.let { net ->
            if (net.upText.isNotBlank() || net.downText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (net.upText.isNotBlank()) StatPill("上行 ${net.upText}", tint = t.textSecondary)
                    if (net.downText.isNotBlank()) StatPill("下行 ${net.downText}", tint = t.textSecondary)
                }
            }
        }

        if (dashboard.counts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dashboard.counts.forEach { field ->
                    StatPill("${field.label} ${field.value}", tint = t.textSecondary)
                }
            }
        }

        if (dashboard.disks.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("磁盘", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(8.dp))
            dashboard.disks.forEach { disk ->
                UsageLine(
                    disk.name,
                    listOf(
                        disk.totalText.takeIf { it.isNotBlank() }?.let { "共 $it" },
                        disk.usedText.takeIf { it.isNotBlank() }?.let { "用 $it" },
                        disk.freeText.takeIf { it.isNotBlank() }?.let { "剩 $it" }
                    ).filterNotNull().joinToString(" · ").ifBlank { Missing },
                    fraction(disk.usedPercent)
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (system.warnings.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            system.warnings.forEach { warning ->
                Text(
                    "面板告警 · $warning",
                    fontSize = 11.5.sp,
                    color = t.warnText,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        FieldList("面板还给了这些", system.extras + dashboard.network?.extras.orEmpty())
    }
}

/** 没读到的值统一说成「面板没给」，不是 0、不是 —。 */
private const val Missing = "面板没给"

@Composable
private fun UsageLine(label: String, value: String, progress: Float?) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.5.sp, color = t.textSecondary, modifier = Modifier.width(64.dp))
        Text(
            value,
            fontSize = 12.5.sp,
            color = t.textStrong,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (progress != null) Text(
            "${(progress * 100).toInt()}%",
            fontSize = 12.sp,
            color = if (progress > 0.85f) t.healthOver else t.textLight,
            fontWeight = FontWeight.SemiBold
        )
    }
    if (progress != null) {
        Spacer(Modifier.height(4.dp))
        UsageBar(progress)
    }
}

/** 暖色主题下 Material 进度条轨道和填充几乎同色，自己画一条更读得出来。 */
@Composable
private fun UsageBar(progress: Float) {
    val t = LocalNoLine.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = t.hairline, cornerRadius = radius, size = Size(size.width, size.height))
        val filled = (size.width * progress.coerceIn(0f, 1f)).coerceAtLeast(size.height * 0.4f)
        drawRoundRect(
            color = if (progress > 0.85f) t.healthOver else t.accent,
            cornerRadius = radius,
            size = Size(filled, size.height)
        )
    }
}

@Composable
private fun FieldList(title: String, fields: List<BtField>) {
    if (fields.isEmpty()) return
    val t = LocalNoLine.current
    Spacer(Modifier.height(10.dp))
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textLight)
    Spacer(Modifier.height(4.dp))
    fields.forEach { field ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(field.label, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.width(92.dp))
            Text(field.value, fontSize = 11.5.sp, color = t.textMain)
        }
    }
}

@Composable
private fun TableCard(
    state: BtPanelUiState,
    viewModel: BtPanelViewModel,
    onRequest: (PendingAction) -> Unit
) {
    val t = LocalNoLine.current
    var keyword by remember { mutableStateOf(state.keyword) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BtTable.entries.forEach { table ->
                NoLineChip(
                    label = table.label,
                    selected = state.table == table,
                    onClick = {
                        keyword = ""
                        viewModel.setKeyword("")
                        viewModel.selectTable(table)
                    }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("在${state.table.label}里找") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            IconButton(onClick = {
                viewModel.setKeyword(keyword.trim())
                viewModel.loadTable()
            }, enabled = !state.busy) {
                Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        val heading = state.total?.let { "${state.table.label} · 共 $it 条" }
            ?: "${state.table.label} · ${state.rows.size} 条"
        Text(heading, fontSize = 12.sp, color = t.textLight)
        Spacer(Modifier.height(6.dp))

        if (state.rows.isEmpty() && !state.busy) {
            Text(
                if (state.keyword.isNotBlank()) "这个关键词没匹配到条目。"
                else "面板这张表是空的，或者接口没给到这个字段。",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        state.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 8.dp))
            RowCard(
                row = row,
                isTask = state.table == BtTable.Crontab,
                busy = state.busy,
                viewModel = viewModel,
                onRequest = onRequest
            )
        }
    }
}

@Composable
private fun RowCard(
    row: BtRow,
    isTask: Boolean,
    busy: Boolean,
    viewModel: BtPanelViewModel,
    onRequest: (PendingAction) -> Unit
) {
    val t = LocalNoLine.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            if (row.badge.isNotBlank()) StatPill(row.badge, tint = t.textSecondary)
        }
        if (row.subtitle.isNotBlank()) {
            Text(
                row.subtitle,
                fontSize = 11.5.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (row.fields.isNotEmpty()) {
            Text(
                row.fields.joinToString(" · ") { "${it.label} ${it.value}" },
                fontSize = 11.sp,
                color = t.textLight,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (isTask) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onRequest(PendingAction.RunTask(row)) },
                    enabled = !busy && row.id.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("立即执行", fontSize = 12.5.sp, color = t.accent) }
                TextButton(
                    onClick = { viewModel.showLog(row) },
                    enabled = !busy && row.id.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("看日志", fontSize = 12.5.sp, color = t.textSecondary) }
            }
        }
    }
}

@Composable
private fun ServiceCard(
    state: BtPanelUiState,
    viewModel: BtPanelViewModel,
    onRequest: (PendingAction) -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text("服务操作", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(4.dp))
        Text(
            "只给四个最轻的动作：重载不打断连接，重启会断几秒。" +
                "停止 / 重启 / 重载面板要亲手打一遍关键词才放行。",
            fontSize = 12.sp,
            color = t.textSecondary,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.serviceDraft,
            onValueChange = viewModel::setServiceDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("服务名") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.medium,
            colors = noLineFieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("nginx", "mysql", "php-fpm").forEach { name ->
                NoLineChip(
                    label = name,
                    selected = state.serviceDraft == name,
                    onClick = { viewModel.setServiceDraft(name) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "PHP 在服务列表里通常带版本号，比如 php-fpm-82；照着面板里的名字改。",
            fontSize = 11.5.sp,
            color = t.textLight,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtServiceOp.entries.forEach { op ->
                Button(
                    onClick = { onRequest(PendingAction.Service(state.serviceDraft.trim(), op)) },
                    enabled = !state.busy && state.serviceDraft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (op == BtServiceOp.Stop) t.healthOver else t.accent,
                        contentColor = t.onBrand
                    ),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(op.label, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onRequest(PendingAction.ReloadPanel) },
            enabled = !state.busy,
            shape = MaterialTheme.shapes.medium
        ) { Text("重载面板本身", fontSize = 13.sp) }
    }
}

private fun fractionOf(used: Double?, total: Double?): Float? {
    if (used == null || total == null || total <= 0) return null
    return (used / total).toFloat().coerceIn(0f, 1f)
}

private fun fraction(percent: Double?): Float? =
    percent?.let { (it / 100).toFloat().coerceIn(0f, 1f) }

/**
 * 确认门。mustType 为真时必须把关键词一字不差打出来，避免手滑点下去就把服务重启了。
 */
@Composable
private fun ConfirmAction(
    title: String,
    body: String,
    keyword: String,
    mustType: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalNoLine.current
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, fontSize = 13.sp, color = t.textMain, lineHeight = 19.sp)
                if (mustType) {
                    Spacer(Modifier.height(12.dp))
                    Text("请输入「$keyword」以确认", fontSize = 12.sp, color = t.textSecondary)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = noLineFieldColors()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !mustType || typed.trim() == keyword
            ) {
                Text("执行", color = if (mustType && typed.trim() != keyword) t.textLight else t.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LogDialog(title: String, text: String, onClose: () -> Unit) {
    val t = LocalNoLine.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("「$title」的日志") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = t.textMain, lineHeight = 18.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}
