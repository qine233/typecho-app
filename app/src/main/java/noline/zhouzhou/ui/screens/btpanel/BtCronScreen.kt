package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtCronBatchOp
import noline.zhouzhou.data.model.BtCronCycles
import noline.zhouzhou.data.model.BtCronForm
import noline.zhouzhou.data.model.BtCronKinds
import noline.zhouzhou.data.model.BtCronTask
import noline.zhouzhou.data.model.BtSiteType
import noline.zhouzhou.data.model.btCronBackupTargetText
import noline.zhouzhou.data.repository.BtCronDetail
import noline.zhouzhou.data.repository.BtCronOptions
import noline.zhouzhou.data.repository.BtCronSettings
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * 计划任务：面板「计划任务」那一页的手机版。
 *
 * 一行一个任务：名字、周期、种类、对象、备份去向、状态都在行上。手有六只——
 * 建任务、改任务（整行编辑）、启用/暂停、立即执行、看执行日志、删任务，
 * 外加批量启停/删除/执行、建分类、把一批归到某个分类。
 *
 * 三件事在这页**只有读数没有按钮**：网站/数据库自动备份的开关、面板日志切割的开关、置顶。
 * 前两条实测会凭空建出任务（发 `status=0` 反而建了一条开着的每日全库备份），
 * 置顶那条怎么发都不动 `sort`，理由都原话写在最底下那张卡上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtCronScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtCronViewModel>(appContainer) { BtCronViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pending by remember { mutableStateOf<CronPending?>(null) }
    var batch by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var categorySheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    pending?.let { ask ->
        ConfirmAction(
            title = ask.label,
            body = ask.body,
            keyword = ask.keyword,
            mustType = ask.mustType,
            onConfirm = { ask.run(); pending = null },
            onDismiss = { pending = null }
        )
    }

    if (state.formOpen) {
        CronFormSheet(
            editing = state.formFor,
            options = state.options,
            loadingOptions = state.loadingOptions,
            busy = state.busy,
            onDismiss = viewModel::closeForm,
            onSubmit = viewModel::submitForm
        )
    }

    if (state.detailFor.isNotBlank()) {
        CronDetailSheet(
            detail = state.detail,
            loading = state.loadingDetail,
            busy = state.busy,
            onDismiss = viewModel::closeDetail,
            onReload = viewModel::reloadDetail,
            onClearLogs = { state.detail?.task?.let(viewModel::clearLogs) },
            onRun = { state.detail?.task?.let(viewModel::run) }
        )
    }

    if (categorySheet) {
        CategorySheet(
            categories = state.options?.categories.orEmpty(),
            pickedCount = picked.size,
            busy = state.busy,
            onAdd = { viewModel.addCategory(it) },
            onApply = { category ->
                val tasks = state.tasks.filter { it.id in picked }
                categorySheet = false
                viewModel.setCategory(category, tasks)
                picked = emptySet()
                batch = false
            },
            onDismiss = { categorySheet = false }
        )
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
                    Text("计划任务", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            batch = !batch
                            picked = emptySet()
                        },
                        enabled = !state.busy
                    ) {
                        Text(if (batch) "退出批量" else "批量", fontSize = 12.5.sp, color = t.accent)
                    }
                    IconButton(onClick = { viewModel.openForm() }) {
                        Icon(Icons.Default.Add, contentDescription = "新建任务", modifier = Modifier.size(21.dp), tint = t.accent)
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新读取", modifier = Modifier.size(20.dp), tint = t.textSecondary)
                    }
                }
                if (batch) {
                    val chosen = state.tasks.filter { it.id in picked }
                    Text(
                        if (chosen.isEmpty()) "勾几条，下面这几颗才按得动。面板的批量口只认编号，编号读不出来的行勾不上。"
                        else "已勾 ${chosen.size} 条。批量这几颗走的是同一个口，逐条判决实测不可信，按完一律以重读到的列表为准。",
                        fontSize = 11.5.sp,
                        color = t.textLight,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BtCronBatchOp.entries.filter { it != BtCronBatchOp.Exec }.forEach { op ->
                            OutlinedButton(
                                onClick = {
                                    if (op == BtCronBatchOp.Delete) {
                                        pending = CronPending(
                                            label = "删除这 ${chosen.size} 条任务？",
                                            body = "连脚本带日志一起删掉，删完这一页会重读列表，有几条还在会照实说。",
                                            keyword = "删除",
                                            mustType = true,
                                            run = {
                                                viewModel.batch(op, chosen)
                                                picked = emptySet()
                                                batch = false
                                            }
                                        )
                                    } else {
                                        val names = chosen.take(6).joinToString("、") { "「${it.name}」" } +
                                            if (chosen.size > 6) " 等 ${chosen.size} 条" else ""
                                        pending = CronPending(
                                            label = "${op.label}这 ${chosen.size} 条任务？",
                                            body = (
                                                if (op == BtCronBatchOp.Stop) "要动的是 $names。暂停后面板不再按周期跑它们，脚本、日志、已备出去的备份文件都留着。\n"
                                                else "要动的是 $names。启用后按各自周期重新跑。\n"
                                                ) +
                                                "批量这一口不带逐条判决，实测那个判决会撒谎，按完一律以重读到的列表为准。",
                                            keyword = "",
                                            mustType = false,
                                            run = {
                                                viewModel.batch(op, chosen)
                                                picked = emptySet()
                                                batch = false
                                            }
                                        )
                                    }
                                },
                                enabled = chosen.isNotEmpty() && !state.busy
                            ) { Text(op.label, fontSize = 12.5.sp) }
                        }
                        OutlinedButton(
                            onClick = {
                                pending = CronPending(
                                    label = "立即执行这 ${chosen.size} 条？",
                                    body = "面板挨个跑一遍，暂停中的也会被跑。这一发不做第二次确认了，勾错就现在退出去。",
                                    keyword = "执行",
                                    mustType = true,
                                    run = {
                                        viewModel.batch(BtCronBatchOp.Exec, chosen)
                                        picked = emptySet()
                                        batch = false
                                    }
                                )
                            },
                            enabled = chosen.isNotEmpty() && !state.busy
                        ) { Text("立即执行", fontSize = 12.5.sp) }
                        OutlinedButton(
                            onClick = {
                                // 分类名单是这一格的唯一来源：还没读过就顺手拉一次，别让人对着空列表挑。
                                if (state.options == null) viewModel.loadOptions()
                                categorySheet = true
                            },
                            enabled = chosen.isNotEmpty() && !state.busy
                        ) { Text("归到分类", fontSize = 12.5.sp) }
                    }
                    HorizontalDivider(color = t.hairline, modifier = Modifier.padding(top = 4.dp))
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = t.accent, trackColor = t.hairline)
                Spacer(Modifier.height(10.dp))
            }
            state.failure?.let { FailureCard(it) }
            if (state.note.isNotBlank()) {
                Text(
                    state.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textMain,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(12.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = viewModel::setKeyword,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("按任务名或脚本内容找") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                IconButton(onClick = viewModel::search, enabled = !state.busy) {
                    Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
                }
            }
            if (state.keyword.isNotBlank()) {
                Text(
                    "这个口面板不给分页也不给搜索参数，整张表一次取回来在本机筛。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            Text(
                buildString {
                    append("共 ${state.tasks.size} 条任务")
                    if (!state.keyword.isBlank()) append("（已按关键词筛过，下面是筛出来的）")
                    append(" · 启用 ${state.enabledCount} · 暂停 ${state.pausedCount}")
                    if (state.unknownCount > 0) append(" · 面板没给状态 ${state.unknownCount}")
                },
                fontSize = 12.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))

            if (state.tasks.isEmpty() && !state.busy) {
                Text(
                    when {
                        !state.configured -> "还没配好面板地址和密钥，回到宝塔那一页先保存自检。"
                        state.keyword.isNotBlank() -> "这个关键词没匹配到任务，面板那张表里也没有别的行。"
                        else -> "面板回了一个空列表：这台服务器上一条计划任务都没有。"
                    },
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
                )
            }

            state.tasks.forEachIndexed { index, task ->
                if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 6.dp))
                if (batch) {
                    CronBatchRow(
                        task = task,
                        checked = task.id in picked,
                        onToggle = {
                            if (task.id.isBlank()) return@CronBatchRow
                            picked = if (task.id in picked) picked - task.id else picked + task.id
                        }
                    )
                } else {
                    CronTaskRow(
                        task = task,
                        busy = state.busy,
                        onToggleStatus = {
                            val want = task.enabled?.not() ?: return@CronTaskRow
                            pending = CronPending(
                                label = if (want) "启用「${task.name}」？" else "暂停「${task.name}」？",
                                body = if (want) {
                                    "面板这条开关是翻转的：现在读到的状态是「${task.statusText}」，" +
                                        "按下去就翻成另一面，改完这一页会重新读一遍列表再回话。"
                                } else {
                                    "暂停之后面板不再按周期跑它，脚本、日志、备份文件都留着，随时能启用回去。"
                                },
                                keyword = "",
                                mustType = false,
                                run = { viewModel.setStatus(task, want) }
                            )
                        },
                        onRun = {
                            pending = CronPending(
                                label = "立即执行「${task.name}」？",
                                body = "这一发不等到下个周期，面板现在就去跑一次。" +
                                    "实测暂停中的任务照样会被跑，而且脚本是什么它就执行什么——" +
                                    "备份类的会真占一份备份，命令类的会真动这台机器。",
                                keyword = "执行",
                                mustType = true,
                                run = { viewModel.run(task) }
                            )
                        },
                        onEdit = { viewModel.openForm(task) },
                        onDetail = { viewModel.openDetail(task) },
                        onDelete = {
                            pending = CronPending(
                                label = "删除「${task.name}」？",
                                body = "面板删这一行时把 /www/server/cron 下那个脚本文件和它的日志一起带走。" +
                                    "已经备份出去的文件不动，但这条任务本身没法从这台机器上找回。",
                                keyword = "删除",
                                mustType = true,
                                run = { viewModel.delete(task) }
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SettingsCard(state.settings) { viewModel.refresh() }
            Spacer(Modifier.height(14.dp))
            CronLimitsCard()
            Spacer(Modifier.height(28.dp))
        }
    }
}

private data class CronPending(
    val label: String,
    val body: String,
    val keyword: String,
    val mustType: Boolean,
    val run: () -> Unit
)

@Composable
private fun CronTaskRow(
    task: BtCronTask,
    busy: Boolean,
    onToggleStatus: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.name.ifBlank { "（面板没给任务名）" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            StatPill(
                task.statusText,
                tint = when (task.enabled) {
                    true -> t.accent
                    false -> t.warnText
                    null -> t.textLight
                }
            )
        }
        Text(
            listOf(task.cycleText, task.kindText).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "面板这一行没给周期和种类" },
            fontSize = 12.sp,
            color = t.textSecondary,
            modifier = Modifier.padding(top = 3.dp)
        )
        if (task.targetText.isNotBlank()) {
            Text(
                task.targetText,
                fontSize = 11.5.sp,
                color = t.textLight,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (task.categoryName.isNotBlank()) {
            Text("分类 ${task.categoryName}", fontSize = 11.sp, color = t.textLight, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            CronRowAction("详情", enabled = !busy, onClick = onDetail)
            CronRowAction("编辑", enabled = !busy && task.id.isNotBlank(), onClick = onEdit)
            CronRowAction(
                text = if (task.enabled == true) "暂停" else "启用",
                enabled = !busy && task.enabled != null && task.id.isNotBlank(),
                onClick = onToggleStatus
            )
            CronRowAction("执行", enabled = !busy && task.id.isNotBlank(), onClick = onRun)
            CronRowAction("删除", enabled = !busy && task.id.isNotBlank(), color = t.healthOver, onClick = onDelete)
        }
    }
}

@Composable
private fun CronBatchRow(task: BtCronTask, checked: Boolean, onToggle: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = task.id.isNotBlank(), onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = task.id.isNotBlank()
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(task.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Text(
                listOf(task.cycleText, task.kindText).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp,
                color = t.textLight,
                maxLines = 1
            )
        }
        if (task.id.isBlank()) Text("没编号", fontSize = 11.sp, color = t.textLight)
        else if (task.enabled == false) StatPill("已暂停", tint = t.warnText)
    }
}

@Composable
private fun CronRowAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = LocalNoLine.current.textSecondary
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, fontSize = 12.5.sp, color = if (enabled) color else LocalNoLine.current.textLight) }
}

/** 详情：信息 + 执行日志两个页签。日志读不到和没日志是两件事，分开写。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronDetailSheet(
    detail: BtCronDetail?,
    loading: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onClearLogs: () -> Unit,
    onRun: () -> Unit
) {
    val t = LocalNoLine.current
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                detail?.task?.name?.let { "「$it」" } ?: "任务详情",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoLineChip(label = "信息", selected = tab == 0, onClick = { tab = 0 })
                NoLineChip(label = "执行日志", selected = tab == 1, onClick = { tab = 1 })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onReload, enabled = !loading, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新读取", modifier = Modifier.size(17.dp), tint = t.textSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) {
                LinearProgressIndicator(color = t.accent, trackColor = t.hairline)
                Spacer(Modifier.height(10.dp))
            }
            detail?.failures?.forEach { source ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(t.warnBg, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text("「${source.label}」这一格没读到", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = t.warnText)
                    Spacer(Modifier.height(3.dp))
                    Text(source.failure.title, fontSize = 11.5.sp, color = t.textMain, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (tab == 0) {
                detail?.let { body ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            body.task.statusText,
                            fontSize = 12.sp,
                            color = if (body.task.enabled == true) t.accent else t.warnText,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (body.task.extraKeys.isEmpty()) ""
                            else "面板这一行还有 ${body.task.extraKeys.size} 个字段这一页没摊开",
                            fontSize = 11.sp,
                            color = t.textLight
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    body.task.detailFields.forEach { field ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(field.label, fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.width(88.dp))
                            Text(
                                field.value,
                                fontSize = 12.sp,
                                color = t.textStrong,
                                lineHeight = 18.sp,
                                fontFamily = if (field.label in setOf("对象", "脚本文件", "访问地址")) FontFamily.Monospace else FontFamily.Default,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(color = t.hairline)
                    }
                    if (body.task.script.isNotBlank() && !body.task.isBackupKind) {
                        Spacer(Modifier.height(10.dp))
                        Text("脚本内容", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .background(t.surfaceSoft, RoundedCornerShape(10.dp))
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                body.task.script,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = t.textMain,
                                lineHeight = 17.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            body.logInfo.exists -> "日志文件在服务器上的位置：${body.logInfo.path}${
                                body.logInfo.sizeText.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                            }。手机上打不开这个路径，只能把位置报给你。"
                            else -> "这一条问不到日志文件的位置（面板回的是「无」）。"
                        },
                        fontSize = 11.5.sp,
                        color = t.textLight,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRun, enabled = !busy && body.task.id.isNotBlank()) {
                            Text("再跑一次", fontSize = 12.5.sp)
                        }
                        OutlinedButton(onClick = onClearLogs, enabled = !busy && body.hasLog) {
                            Text("清空日志", fontSize = 12.5.sp)
                        }
                    }
                } ?: Text("还在读这条任务…", fontSize = 12.sp, color = t.textSecondary)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .background(t.surfaceSoft, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    val text = detail?.logText.orEmpty()
                    when {
                        loading && detail == null -> Text("正在从面板拉日志…", fontSize = 11.5.sp, color = t.textSecondary)
                        text.isBlank() -> Text(
                            if (detail != null) "这条任务还没有执行日志：面板回的是「当前日志为空」，是数据不是故障。"
                            else "还没读到日志。",
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = t.textSecondary,
                            lineHeight = 17.sp
                        )
                        else -> text.lines().forEach { line ->
                            Text(
                                line,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = t.textMain,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "日志是这一页现拉的，面板只给尾部那一段；刚执行过想看来不来得及，按右上角重新读一次。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

/** 建 / 改任务。种类和周期决定露出哪几格，发回面板时也只发露出来的那些。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronFormSheet(
    editing: BtCronTask?,
    options: BtCronOptions?,
    loadingOptions: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (BtCronSpec) -> Unit
) {
    val t = LocalNoLine.current
    var spec by remember { mutableStateOf(editing?.let(BtCronSpec::of) ?: BtCronSpec()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                if (editing == null) "新建计划任务" else "改「${editing.name}」",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (editing == null) "面板会按这个周期在这台机器上定时执行。"
                else "改这一条是整行发回面板：这一格没摆出来的东西原样保留，摆出来的以这里填的为准。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = spec.name,
                onValueChange = { spec = spec.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("任务名") },
                supportingText = {
                    Text(
                        "这一格发过去落进的是面板的显示名；建任务那一刻那个内部名会留着不变，界面上看不到它。",
                        color = t.textLight
                    )
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(10.dp))

            FieldLabel("周期")
            ChipRow(BtCronCycles.map { it.id to it.name }, spec.cycle) { id ->
                spec = spec.copy(cycle = id)
            }
            if (cycleNeedsCount(spec.cycle)) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = spec.where1,
                    onValueChange = { spec = spec.copy(where1 = it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(cycleCountLabel(spec.cycle)) },
                    isError = BtCronForm.where1Error(spec.cycle, spec.where1) != null && spec.where1.isNotBlank(),
                    supportingText = {
                        Text(
                            when (spec.cycle) {
                                "week" -> "0–7，0 和 7 都是周日"
                                "month" -> "1–31 号"
                                else -> "每隔几个，1–9999"
                            },
                            color = t.textLight
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            }
            if (cycleNeedsHour(spec.cycle) || cycleNeedsMinute(spec.cycle)) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (cycleNeedsHour(spec.cycle)) {
                        ClockField(
                            label = "时（0–23）",
                            value = spec.hour,
                            onChange = { spec = spec.copy(hour = it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (cycleNeedsMinute(spec.cycle)) {
                        ClockField(
                            label = "分（0–59）",
                            value = spec.minute,
                            onChange = { spec = spec.copy(minute = it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel("执行种类")
            ChipRow(BtCronKinds.map { it.id to it.name }, spec.kind) { id ->
                spec = spec.copy(kind = id)
            }
            Spacer(Modifier.height(10.dp))

            when {
                spec.isUrlKind -> OutlinedTextField(
                    value = spec.urlAddress,
                    onValueChange = { spec = spec.copy(urlAddress = it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("要访问的网址") },
                    placeholder = { Text("https://example.com/cron") },
                    supportingText = {
                        Text("面板只看这一格空不空，协议写错它照收、跑起来才发现访问不了。", color = t.textLight)
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                spec.isScriptKind -> OutlinedTextField(
                    value = spec.script,
                    onValueChange = { spec = spec.copy(script = it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text(if (spec.kind == "toPython") "Python 脚本" else "Shell 命令") },
                    supportingText = {
                        Text(
                            "面板把这整段写进 /www/server/cron 下的脚本文件里，每次执行就是跑这个文件。",
                            color = t.textLight
                        )
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                spec.isBackupKind -> Column {
                    FieldLabel(if (spec.kind == "database") "要备份的库" else "要备份的对象")
                    val source = when (spec.kind) {
                        "database" -> options?.databases
                        "site" -> options?.sites
                        else -> null
                    }
                    when {
                        spec.kind == "path" -> OutlinedTextField(
                            value = spec.target,
                            onValueChange = { spec = spec.copy(target = it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("要备份的目录") },
                            placeholder = { Text("/www/wwwroot/example.com") },
                            isError = BtCronForm.pathError(spec.target) != null && spec.target.isNotBlank(),
                            shape = MaterialTheme.shapes.medium,
                            colors = noLineFieldColors()
                        )
                        source.isNullOrEmpty() -> Text(
                            if (loadingOptions) "正在问面板有哪些对象能备份…"
                            else "这一格没读到名单（面板那个口没给数据），对象名只能自己填。",
                            fontSize = 12.sp,
                            color = t.textSecondary,
                            lineHeight = 18.sp
                        )
                        else -> ChipRow(source.map { it.id to it.name }, spec.target) { id ->
                            spec = spec.copy(target = id)
                        }
                    }
                    if (source != null && source.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = spec.target,
                            onValueChange = { spec = spec.copy(target = it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("或者自己写对象名") },
                            shape = MaterialTheme.shapes.medium,
                            colors = noLineFieldColors()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = spec.keepBackups,
                        onValueChange = { spec = spec.copy(keepBackups = it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("保留几份（留空按面板默认）") },
                        isError = BtCronForm.keepError(spec.keepBackups) != null,
                        supportingText = { Text("0–9999；面板到份数自己删最老的那份。", color = t.textLight) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.medium,
                        colors = noLineFieldColors()
                    )
                    Spacer(Modifier.height(10.dp))
                    FieldLabel("备份到哪")
                    val targets = options?.backupTargets.orEmpty()
                    if (targets.isEmpty()) {
                        Text(
                            if (loadingOptions) "正在问面板有哪些备份去向…"
                            else "这一格没读到去向名单，留空就是不发这一格，面板沿用它原来的去向。",
                            fontSize = 12.sp,
                            color = t.textSecondary,
                            lineHeight = 18.sp
                        )
                    } else {
                        ChipRow(
                            targets.map { it.id to (btCronBackupTargetText(it.name).ifBlank { it.name }) },
                            spec.backupTo
                        ) { id -> spec = spec.copy(backupTo = id) }
                    }
                }
                else -> Text(
                    "这一种（${btKindName(spec.kind)}）面板不需要再填别的，周期定好就行。",
                    fontSize = 12.sp,
                    color = t.textSecondary,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("以哪个系统用户执行")
            val users = options?.users.orEmpty()
            if (users.isEmpty()) {
                OutlinedTextField(
                    value = spec.runAs,
                    onValueChange = { spec = spec.copy(runAs = it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("用户名（留空用面板默认）") },
                    supportingText = {
                        Text(
                            if (loadingOptions) "正在问面板有哪些用户，先手写也行。"
                            else "用户名单这一格没读到，只能自己填；填错面板会拒。",
                            color = t.textLight
                        )
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            } else {
                ChipRow(listOf("" to "面板默认") + users.map { it to it }, spec.runAs) { id ->
                    spec = spec.copy(runAs = id)
                }
            }

            options?.failures?.forEach { source ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "「${source.label}」没读到，这一格的下拉是空的：${source.failure.title}",
                    fontSize = 11.5.sp,
                    color = t.warnText,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = { onSubmit(spec) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        busy -> "正在发给面板…"
                        editing == null -> "建这条任务"
                        else -> "把改动发给面板"
                    },
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (editing == null) "面板建新任务时把脚本写进 /www/server/cron，之后每次执行都跑那个文件。"
                else "改完这一发是整行重发：面板的 modify_crond 会把没发的格子抹平，所以这一页把读到的原行一并带回。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
        }
    }
}

private fun btKindName(code: String): String =
    BtCronKinds.firstOrNull { it.id == code }?.name ?: code

@Composable
private fun FieldLabel(text: String) {
    val t = LocalNoLine.current
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            NoLineChip(label = label.ifBlank { "（不发这一格）" }, selected = selected == value, onClick = { onPick(value) })
        }
    }
}

@Composable
private fun ClockField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalNoLine.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.medium,
        colors = noLineFieldColors()
    )
}

/** 批量勾选之后：归类 + 建分类。默认分类面板不让归回去，这里只列自定义的。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    categories: List<BtSiteType>,
    pickedCount: Int,
    busy: Boolean,
    onAdd: (String) -> Unit,
    onApply: (BtSiteType) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalNoLine.current
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("分类", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "面板的分类只有「建」和「归进去」两个口：归进去会盖掉这批任务原来的分类，" +
                    "而面板没有删分类的接口，建错了只能去网页端删。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            Text("新建一个", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("分类名") },
                    isError = BtCronForm.categoryError(name) != null && name.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                Button(
                    onClick = { onAdd(name) },
                    enabled = !busy && BtCronForm.categoryError(name) == null
                ) { Text("建", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(16.dp))
            Text("把勾上的 $pickedCount 条归到", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            val pickable = categories.filter { it.id != "0" && it.id.isNotBlank() }
            if (pickable.isEmpty()) {
                Text(
                    "面板现在一个自定义分类都没有（或者这一格没读到）。先在上面建一个，再回来归。",
                    fontSize = 12.sp,
                    color = t.textSecondary,
                    lineHeight = 18.sp
                )
            } else {
                pickable.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = pickedCount > 0 && !busy) { onApply(category) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(category.name, fontSize = 13.5.sp, color = t.textStrong, modifier = Modifier.weight(1f))
                        Text("归过去", fontSize = 12.5.sp, color = if (pickedCount > 0) t.accent else t.textLight)
                    }
                    HorizontalDivider(color = t.hairline)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "「默认分类」在这一格是灰的：实测归回默认面板直接拒，原话「不能设置为系统分类或者默认分类!」。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
        }
    }
}

/** 面板级三块读数：只摆，不给按钮。 */
@Composable
private fun SettingsCard(settings: BtCronSettings?, onReload: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("面板级设置", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
            TextButton(onClick = onReload, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("重新读", fontSize = 11.5.sp, color = t.accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "这三块面板给的是开关加参数，而那两个开关实测是「一按就建任务」的东西，所以这里只把读数摊开，按钮一颗不放。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        settings?.failures?.forEach { source ->
            Text(
                "「${source.label}」没读到：${source.failure.title}",
                fontSize = 11.5.sp,
                color = t.warnText,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        SettingsLine("网站自动备份", settings?.autoSite?.summary)
        SettingsLine("数据库自动备份", settings?.autoMysql?.summary)
        SettingsLine("面板日志切割", settings?.rotate?.summary)
        if (settings == null) {
            Text("还没读到这一格。", fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SettingsLine(label: String, value: String?) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.width(96.dp))
        Text(
            value ?: "…",
            fontSize = 12.sp,
            color = t.textStrong,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 接口做不到或者不敢做的几件事，写在列表底下，免得人找半天找不到按钮。 */
@Composable
private fun CronLimitsCard() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("这一格面板接口做不到", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        listOf(
            "置顶 / 取消置顶：set_task_top 六种参数形状都试过，回一个空的 list，列表里那个 sort 一动不动。",
            "归回默认分类：面板原话「不能设置为系统分类或者默认分类!」，只能归到自定义分类之间。",
            "删分类：面板根本没有这个口，建错的分类只能去网页端删。",
            "网站/数据库自动备份开关、面板日志切割开关：实测 set_auto_config 和 set_rotate_log_status 发过去会凭空建出任务" +
                "（发 status=0 反倒建了一条开着的每日全库备份），所以这一页只读不写。",
            "文档里的「立即执行」set_execute_script 实测只回一个裸的 false，日志一条都不多；" +
                "这一页那颗「执行」走的是批量口的 exec，那条实测真会跑。",
            "备份类任务的实际备份文件要下载只能去面板网页，接口这一侧没有取件口。"
        ).forEach { line ->
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text("· ", fontSize = 12.sp, color = t.textSecondary)
                Text(line, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
