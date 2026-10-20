package noline.zhouzhou.ui.screens.btpanel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtFileAccess
import noline.zhouzhou.data.model.BtFileEntry
import noline.zhouzhou.data.model.BtFileHistoryFile
import noline.zhouzhou.data.model.BtFilesForm
import noline.zhouzhou.data.model.BtFilesLimits
import noline.zhouzhou.data.model.BtQuickRoots
import noline.zhouzhou.data.model.BtRecycleEntry
import noline.zhouzhou.data.model.btPathCrumbs
import noline.zhouzhou.data.repository.BtFilesTools
import noline.zhouzhou.data.repository.btIsArchive
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * 文件管理器：面板「文件」那一页的手机版。
 *
 * 摆出来的能力都是这一格实测能发出去的：进目录、跳路径、搜索、建目录/建文件、
 * 编辑正文、改名、移动、复制、软链、解压、上传、改权限与属主、删除、看占用、查杀病毒那两个开关、
 * 回收站的放回/彻底删/恢复到指定位置，以及面板的文件操作历史。
 *
 * 底下那张「做不到」的卡解释了为什么没有压缩、没有下载直链、没有递归改权限的开关——
 * 全是被这台 13.1.0 拒过的，不是忘了做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtFilesScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtFilesViewModel>(appContainer) { BtFilesViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    val context = LocalContext.current
    var pending by remember { mutableStateOf<FilesPending?>(null) }
    var createSheet by remember { mutableStateOf(false) }
    var dialogInput by remember { mutableStateOf("") }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = btPickedFile(context, uri)
        viewModel.upload(context, uri, picked.name, picked.sizeBytes, picked.mime)
    }

    LaunchedEffect(state.dialog) { dialogInput = state.dialog?.initial.orEmpty() }
    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(5600)
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

    state.dialog?.let { dialog ->
        InputSheet(
            dialog = dialog,
            value = dialogInput,
            busy = state.busy,
            onChange = { dialogInput = it },
            onDismiss = viewModel::closeDialog,
            onSubmit = { viewModel.submitDialog(dialogInput) }
        )
    }

    state.actionFor?.let { entry ->
        FileActionSheet(
            entry = entry,
            busy = state.busy,
            onDismiss = viewModel::closeAction,
            onOpen = {
                if (entry.isDir) viewModel.enter(entry) else viewModel.openEditor(entry)
            },
            onRename = { viewModel.openDialog(BtFilesDialog.Kind.Rename, entry) },
            onMove = { viewModel.openDialog(BtFilesDialog.Kind.Move, entry) },
            onCopy = { viewModel.openDialog(BtFilesDialog.Kind.Copy, entry) },
            onLink = { viewModel.openDialog(BtFilesDialog.Kind.Link, entry) },
            onUnzip = { viewModel.openDialog(BtFilesDialog.Kind.Unzip, entry) },
            onAccess = { viewModel.showAccess(entry) },
            onSize = { viewModel.sizeOf(entry) },
            onScan = {
                pending = FilesPending(
                    label = "用面板的查杀扫一下 ${entry.name}？",
                    body = "这一发是让面板的病毒查杀去扫这一个" + (if (entry.isDir) "目录" else "文件") +
                        "。实测它只回一句「扫描指令已下发」，结果落在面板的安全日志里，" +
                        "这一页读不回来，所以按完看不到任何判决——要结论得去网页端的日志。",
                    keyword = "",
                    mustType = false,
                    run = { viewModel.scan(entry) }
                )
            },
            onDelete = {
                val recycleOn = state.page?.recycleEnabled != false
                pending = FilesPending(
                    label = "删除「${entry.name}」？",
                    body = (
                        when {
                            !recycleOn -> "这台面板的回收站是关着的（回包里 `file_recycle` 是假），删下去就直接没了，找不回来。"
                            entry.isDir -> "整个目录连同里面所有东西一起进回收站。目录越大搬进去越慢，" +
                                "面板那句「已移动到回收站」是真的，但里面的清单这一页读不出来。"
                            else -> "进回收站，之后能在「回收站」那一格放回去。已经打开着这个文件的进程不受影响，" +
                                "但新的读取会找不到它。"
                        }
                        ) + "\n\n路径：${entry.path}",
                    keyword = "删除",
                    mustType = true,
                    run = { viewModel.delete(entry) }
                )
            }
        )
    }

    state.accessFor?.let { entry ->
        AccessSheet(
            entry = entry,
            access = state.access,
            perm = state.accessPerm,
            owner = state.accessOwner,
            busy = state.busy,
            onPerm = viewModel::setAccessPerm,
            onOwner = viewModel::setAccessOwner,
            onDismiss = viewModel::closeAccess,
            onSubmit = {
                pending = FilesPending(
                    label = "把 ${entry.name} 改成 ${state.accessPerm}/${state.accessOwner}？",
                    body = "权限和属主是往这台机器的文件系统上写的，改错了网站可能立刻 500，" +
                        "MySQL 数据目录那一路改错属主连数据库都起不来。\n" +
                        "递归那一格实测不发作用（面板照回「设置成功」，子文件纹丝不动），所以这里只改这一层。\n\n路径：${entry.path}",
                    keyword = "",
                    mustType = false,
                    run = viewModel::submitAccess
                )
            }
        )
    }

    state.editor?.let { editor ->
        EditorSheet(
            editor = editor,
            busy = state.busy,
            onDismiss = {
                if (editor.dirty) {
                    pending = FilesPending(
                        label = "编辑器里的改动不要了？",
                        body = "这一格不留草稿：关掉没保存就丢，面板那边还是原来的文件。",
                        keyword = "",
                        mustType = false,
                        run = viewModel::closeEditor
                    )
                } else {
                    viewModel.closeEditor()
                }
            },
            onChange = viewModel::setEditorContent,
            onSave = viewModel::saveEditor
        )
    }

    if (createSheet) {
        ModalBottomSheet(onDismissRequest = { createSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("在这一层做点什么", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                Spacer(Modifier.height(4.dp))
                Text(
                    "目标都是 ${state.path}。面板建目录只认已经存在的父级，不会替你逐级建。",
                    fontSize = 12.sp,
                    color = t.textSecondary,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                SheetAction("新建目录") { createSheet = false; viewModel.openDialog(BtFilesDialog.Kind.NewDir) }
                SheetAction("新建文件") { createSheet = false; viewModel.openDialog(BtFilesDialog.Kind.NewFile) }
                SheetAction("从手机里选一个文件上传") { createSheet = false; uploadPicker.launch(arrayOf("*/*")) }
                SheetAction("跳到别的目录") { createSheet = false; viewModel.openDialog(BtFilesDialog.Kind.OpenPath) }
                SheetAction("扫描这一层（病毒查杀）") {
                    createSheet = false
                    pending = FilesPending(
                        label = "用面板的查杀扫这一层？",
                        body = "发下去面板只回一句「指令已下发」，结果只落在它的安全日志里，这一页读不回来。",
                        keyword = "",
                        mustType = false,
                        run = viewModel::scanHere
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("这两颗动的是这台机器上装的软件，不是当前目录里的文件", fontSize = 11.sp, color = t.textLight)
                SheetAction("装 RAR 解压支持") {
                    createSheet = false
                    pending = FilesPending(
                        label = "让面板装 RAR 解压支持？",
                        body = "这一发是叫面板去装它自己那份解压依赖（rar），装完才能解压 .rar 包。" +
                            "它会动这台机器上的软件，不止是文件；面板回的那句这一页只能原样贴出来。",
                        keyword = "",
                        mustType = false,
                        run = viewModel::installRar
                    )
                }
                SheetAction("更新 Composer") {
                    createSheet = false
                    pending = FilesPending(
                        label = "让面板更新 Composer？",
                        body = "面板当前报的 Composer 版本：${state.tools?.composer?.ifBlank { "（没读到）" } ?: "（还没读到）"}。" +
                            "这一发是让它自更新，属于软件维护，动的是全局的 composer 可执行文件。",
                        keyword = "",
                        mustType = false,
                        run = viewModel::updateComposer
                    )
                }
            }
        }
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
                    Text("文件管理器", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    if (state.view == BtFilesView.Browse) {
                        TextButton(
                            onClick = { viewModel.setBatch(!state.batch) },
                            enabled = !state.busy
                        ) {
                            Text(
                                if (state.batch) "退出批量" else "批量",
                                fontSize = 12.5.sp,
                                color = t.accent
                            )
                        }
                        IconButton(onClick = { createSheet = true }, enabled = !state.busy) {
                            Icon(Icons.Default.Add, contentDescription = "在这一层新建", modifier = Modifier.size(21.dp), tint = t.accent)
                        }
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新读取", modifier = Modifier.size(20.dp), tint = t.textSecondary)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BtFilesView.entries.forEach { view ->
                        NoLineChip(
                            label = view.label,
                            selected = state.view == view,
                            onClick = { if (state.view != view) viewModel.setView(view) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.view == BtFilesView.Browse) {
                        IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Upload, contentDescription = "上传到这里", modifier = Modifier.size(18.dp), tint = t.accent)
                        }
                    }
                }
                if (state.batch && state.view == BtFilesView.Browse) {
                    val keyword = state.keyword.trim()
                    val visible = state.page?.entries.orEmpty().filter {
                        keyword.isBlank() || it.name.contains(keyword, ignoreCase = true)
                    }
                    val chosen = visible.filter { it.path in state.picked }
                    Text(
                        if (chosen.isEmpty()) "勾几条，下面这几颗才按得动。批量删除是一条一条发的，面板不回逐条判决，有几条没掉只能重读之后数。"
                        else "已勾 ${chosen.size} 条：${chosen.take(4).joinToString("、") { it.name }}" +
                            if (chosen.size > 4) " 等 ${chosen.size} 条" else "",
                        fontSize = 11.5.sp,
                        color = t.textLight,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pending = FilesPending(
                                    label = "删除这 ${chosen.size} 条？",
                                    body = "一条一条发过去，面板不做批量判决，中途哪条失败这一页只能靠重读数出来。" +
                                        "回收站开着的话都能放回。要动的是：" +
                                        chosen.take(6).joinToString("、") { "「${it.name}」" } +
                                        if (chosen.size > 6) " 等 ${chosen.size} 条" else "",
                                    keyword = "删除",
                                    mustType = true,
                                    run = {
                                        viewModel.deleteChosen()
                                    }
                                )
                            },
                            enabled = chosen.isNotEmpty() && !state.busy
                        ) { Text("删除", fontSize = 12.5.sp) }
                        OutlinedButton(
                            onClick = {
                                viewModel.openDialog(BtFilesDialog.Kind.Unzip)
                            },
                            enabled = chosen.any { btIsArchive(it.name) } && !state.busy
                        ) { Text("解压选中的包", fontSize = 12.5.sp) }
                        TextButton(
                            onClick = viewModel::pickVisible,
                            enabled = chosen.size < visible.size,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                "全选这页 ${visible.size} 条",
                                fontSize = 12.5.sp,
                                color = if (chosen.size < visible.size) t.accent else t.textLight
                            )
                        }
                        TextButton(
                            onClick = viewModel::clearPicks,
                            enabled = chosen.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("全不选", fontSize = 12.5.sp, color = t.textSecondary) }
                    }
                    HorizontalDivider(color = t.hairline, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (state.busy) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = t.accent,
                        trackColor = t.hairline
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            state.failure?.let {
                item {
                    FailureCard(it)
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (state.note.isNotBlank()) {
                item {
                    Text(
                        state.note,
                        fontSize = 12.sp,
                        color = t.textMain,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth()
                            .noLineCard(RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            when (state.view) {
                BtFilesView.Browse -> browseContent(state, viewModel)
                BtFilesView.Recycle -> recycleContent(state, viewModel) { ask -> pending = ask }
                BtFilesView.History -> historyContent(state, viewModel)
            }

            if (state.view == BtFilesView.Browse) {
                state.tools?.let { tools ->
                    item {
                        Spacer(Modifier.height(14.dp))
                        ToolsCard(tools)
                    }
                }
            }
            item {
                Spacer(Modifier.height(14.dp))
                FilesLimitsCard()
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** 目录那一格的正文：面包屑 → 搜索 → 读数 → 快捷目录/收藏/最近 → 条目。 */
private fun LazyListScope.browseContent(
    state: BtFilesUiState,
    viewModel: BtFilesViewModel
) {
    val page = state.page

    item {
        val t = LocalNoLine.current
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CrumbText("/", "/", active = state.path.trimEnd('/') in setOf("", "/"), onPick = viewModel::goTo)
            btPathCrumbs(state.path).forEach { (label, full) ->
                Text(" / ", fontSize = 12.sp, color = t.textLight)
                CrumbText(label, full, active = full == state.path.trimEnd('/'), onPick = viewModel::goTo)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    item {
        val t = LocalNoLine.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = viewModel::setKeyword,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("在这一层按名字找") },
                trailingIcon = {
                    if (state.keyword.isNotBlank()) {
                        IconButton(onClick = viewModel::clearSearch, modifier = Modifier.size(30.dp)) {
                            Text("✕", fontSize = 14.sp, color = t.textSecondary)
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            IconButton(onClick = viewModel::search, enabled = !state.busy) {
                Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
            }
        }
        if (state.keyword.isNotBlank()) {
            Text(
                "筛是这一页按名字本地做的：实测带 `search` 发过去，面板不看这一格、照样回整层，" +
                    "却会把关键词记进它自己的搜索历史（回包里那个 `search_history`），所以这一页干脆不发那一格。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    item {
        val t = LocalNoLine.current
        Text(
            buildString {
                if (!state.configured) append("还没配好面板地址和密钥，回到宝塔那一页先保存自检")
                else {
                    append(page?.let { "目录 ${it.dirCount} · 文件 ${it.fileCount}" } ?: "还没读到这一层")
                    page?.total?.let { append(" · 面板报共 $it 条") }
                    val kw = state.keyword.trim()
                    if (kw.isNotBlank()) {
                        append(" · 这一页筛出 ${page?.entries?.count { it.name.contains(kw, true) } ?: 0} 条")
                    }
                    append(if (page?.recycleEnabled == false) " · 回收站是关着的" else " · 删除会进回收站")
                    if (page?.truncated == true) append(" · 这一层太长，面板只回了一部分")
                }
            },
            fontSize = 12.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
    }

    item {
        val t = LocalNoLine.current
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BtQuickRoots.forEach { (path, label) ->
                NoLineChip(
                    label = if (state.path.trimEnd('/') == path.trimEnd('/')) "$label ·在这里" else label,
                    selected = false,
                    onClick = { viewModel.goTo(path) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // 面板的 dir_history 实测会把同一个目录按访问次数记好几条，有的还差一个末尾斜杠，所以按归一化后的路径去重
    // （面板给的就是新→旧的顺序）。剩下的重名（实测同名目录散在 backup、回收站好几处）只能把整条路径摆出来才分得开。
    val recents = page?.recents.orEmpty().filter { it.value.isNotBlank() }.distinctBy { it.value.trimEnd('/') }
    val duplicated = recents.groupingBy { btPathLabel(it.value) }.eachCount().filterValues { it > 1 }.keys
    if (recents.isNotEmpty()) {
        item {
            val t = LocalNoLine.current
            Text("面板记住的最近目录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textSecondary)
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recents.take(12).forEach { record ->
                    val label = if (btPathLabel(record.value) in duplicated) record.value else btPathLabel(record.value)
                    NoLineChip(label = label, selected = false, onClick = { viewModel.goTo(record.value) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val favorites = page?.favorites.orEmpty().filter { it.isNotBlank() }
    if (favorites.isNotEmpty()) {
        item {
            val t = LocalNoLine.current
            Text("面板里的收藏目录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textSecondary)
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                favorites.forEach { path ->
                    NoLineChip(label = btPathLabel(path), selected = false, onClick = { viewModel.goTo(path) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // 实测发 `search=link` 面板照样把整层回给你（它不看这一格），所以筛是 App 在本地按名字做的。
    val keyword = state.keyword.trim()
    val entries = page?.entries.orEmpty().filter {
        keyword.isBlank() || it.name.contains(keyword, ignoreCase = true)
    }
    if (entries.isEmpty() && !state.busy && state.configured) {
        item {
            val t = LocalNoLine.current
            Text(
                when {
                    keyword.isNotBlank() -> "这一层没有名字对得上「$keyword」的东西。筛是这一页按名字筛的，不看路径。"
                    else -> "这个目录是空的（面板回了零条）。上一层看看，或者在这里新建。"
                },
                fontSize = 12.5.sp,
                color = t.textSecondary,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (state.batch) {
        items(entries, key = { "b-" + it.path }) { entry ->
            val t = LocalNoLine.current
            FileBatchRow(
                entry = entry,
                checked = entry.path in state.picked,
                onToggle = { viewModel.togglePick(entry) }
            )
        }
    } else {
        items(entries, key = { "f-" + it.path }) { entry ->
            val t = LocalNoLine.current
            FileRow(
                entry = entry,
                busy = state.busy,
                onOpen = { if (entry.isNavigable) viewModel.enter(entry) else viewModel.openEditor(entry) },
                onAction = { viewModel.showAction(entry) }
            )
            HorizontalDivider(color = t.hairline)
        }
    }
}

/** 回收站那一格。 */
private fun LazyListScope.recycleContent(
    state: BtFilesUiState,
    viewModel: BtFilesViewModel,
    askFor: (FilesPending) -> Unit
) {
    val bin = state.bin

    item {
        val t = LocalNoLine.current
        Text(
            when {
                bin == null -> if (state.busy) "正在问面板回收站里有什么…" else "还没读到回收站。"
                else -> buildString {
                    append("面板报这里共 ${bin.total ?: bin.counts.all ?: 0} 条")
                    bin.counts.breakdown.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    bin.counts.totalSizeText.takeIf { it.isNotBlank() }?.let { append(" · 一共 $it") }
                    if (!bin.enabled) append(" · 回收站功能是关着的，这些是历史遗留")
                }
            },
            fontSize = 12.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
    }

    item {
        val t = LocalNoLine.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.binKeyword,
                onValueChange = viewModel::setBinKeyword,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("按原始文件名找") },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            IconButton(onClick = { viewModel.loadBin(1) }, enabled = !state.busy) {
                Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
            }
        }
        Text(
            "这一格实测每页固定 10 条、目录在前、同类按时间新→旧，`limit` 参数面板不看；刚删掉的那个排在第一页第一行。" +
                "搜索只匹配条目的原始名，做不到「只看某个目录删进来的那些」。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
    }

    item {
        val t = LocalNoLine.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.loadBin(state.binPage - 1) }, enabled = state.binPage > 1 && !state.busy) {
                Text("‹ 上一页", fontSize = 12.5.sp)
            }
            Text("第 ${state.binPage} 页", fontSize = 12.5.sp, color = t.textSecondary)
            OutlinedButton(
                onClick = { viewModel.loadBin(state.binPage + 1) },
                enabled = (bin?.entries?.size ?: 0) >= 10 && !state.busy
            ) { Text("下一页 ›", fontSize = 12.5.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    askFor(
                        FilesPending(
                            label = "清空回收站？",
                            body = "这一发把回收站里所有东西一次性倒掉，倒完没有任何一格能放回来。" +
                                "这台面板的回收站里现在躺着 " + (bin?.counts?.all?.toString() ?: "不知道多少") + " 条，" +
                                "大部分不是这个 App 删进去的，倒了也说不清是谁什么时候删的。" +
                                "面板这条 `Close_Recycle_bin` 我们一次都没有真试过。",
                            keyword = "清空回收站",
                            mustType = true,
                            run = viewModel::clearBin
                        )
                    )
                },
                enabled = !state.busy
            ) { Text("清空回收站", fontSize = 12.sp, color = t.healthOver) }
        }
        Spacer(Modifier.height(8.dp))
    }

    val entries = bin?.entries.orEmpty()
    if (entries.isEmpty() && !state.busy) {
        item {
            val t = LocalNoLine.current
            Text(
                if (state.binKeyword.isNotBlank()) "这个关键词在回收站里没匹配到条目。"
                else "回收站是空的（第 ${state.binPage} 页没有东西）。",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    items(entries, key = { "r-" + it.rname }) { entry ->
        val t = LocalNoLine.current
        RecycleRow(
            entry = entry,
            busy = state.busy,
            onRestore = {
                askFor(
                    FilesPending(
                        label = "放回「${entry.name}」？",
                        body = "面板把它放回原来的位置 ${entry.originalPath}。" +
                            "那个位置已经有同名的东西时，实测面板会把原有的改名成 原名_时间戳.bak 留着，谁也不覆盖谁。\n" +
                            "放回之后这一页重新读目录才看得见它；数据库那一路的回收站这台是关着的。",
                        keyword = "",
                        mustType = false,
                        run = { viewModel.restore(entry) }
                    )
                )
            },
            onRestoreTo = { viewModel.openRestoreTo(entry) },
            onPurge = {
                askFor(
                    FilesPending(
                        label = "彻底删掉「${entry.name}」？",
                        body = "这一发是把回收盘里那坨直接 rm 掉：${entry.originalPath} 的这份副本没了，" +
                            "原来的位置也不会有东西回来。这一格面板的接口只认 `path` 那一格装 `rname`，" +
                            "参数名发错它回 HTTP 400 空 body。\n\n化名：${entry.rname}",
                        keyword = "删除",
                        mustType = true,
                        run = { viewModel.purge(entry) }
                    )
                )
            }
        )
        HorizontalDivider(color = t.hairline)
    }
}

/** 操作历史那一格：流水 + 有编辑版本的文件。 */
private fun LazyListScope.historyContent(
    state: BtFilesUiState,
    viewModel: BtFilesViewModel
) {
    item {
        val t = LocalNoLine.current
        Text(
            when {
                state.history == null -> if (state.busy) "正在问面板文件操作的流水…" else "还没读到流水。"
                else -> "面板这台的文件操作流水共 ${state.history.total ?: state.history.lines.size} 条 · " +
                    "第 ${state.historyPage} 页回了 ${state.history.lines.size} 条 · " +
                    "这是全站文件管理的记录，不只这个 App 发出去的那些"
            },
            fontSize = 12.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "这一口有两个实测脾气：带 `limit` 面板直接回 HTTP 400 空 body，所以只发页码；" +
                "而且它的 `status` 是个字符串 \"true\"，不是信封，整包得当数据读。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
    }

    item {
        val t = LocalNoLine.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.loadHistory(state.historyPage - 1) },
                enabled = state.historyPage > 1 && !state.busy
            ) { Text("‹ 上一页", fontSize = 12.5.sp) }
            Text("第 ${state.historyPage} 页", fontSize = 12.5.sp, color = t.textSecondary)
            OutlinedButton(
                onClick = { viewModel.loadHistory(state.historyPage + 1) },
                enabled = (state.history?.lines?.size ?: 0) >= 10 && !state.busy
            ) { Text("下一页 ›", fontSize = 12.5.sp) }
        }
        Spacer(Modifier.height(10.dp))
    }

    state.history?.lines.orEmpty().forEach { line ->
        item {
            val t = LocalNoLine.current
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.time, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.weight(1f))
                    if (line.type.isNotBlank()) StatPill(line.type, tint = t.accent)
                }
                Text(
                    line.log.ifBlank { "面板这一行没写内容" },
                    fontSize = 12.5.sp,
                    color = t.textMain,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (line.username.isNotBlank()) {
                    Text("操作者 ${line.username}", fontSize = 11.sp, color = t.textLight, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(color = t.hairline)
        }
    }

    val files = state.historyFiles
    if (files.isNotEmpty()) {
        item {
            val t = LocalNoLine.current
            Spacer(Modifier.height(8.dp))
            Text(
                "这些文件在面板里存着编辑版本（点一个就直接开编辑器）",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textSecondary
            )
            Spacer(Modifier.height(6.dp))
        }
        items(files, key = { "h-" + it.path }) { file ->
            val t = LocalNoLine.current
            HistoryFileRow(file) { viewModel.openPath(file.path) }
            HorizontalDivider(color = t.hairline)
        }
    }

    if ((state.history?.lines?.size ?: 0) == 0 && !state.busy && state.configured) {
        item {
            val t = LocalNoLine.current
            Text(
                "面板回了一条流水都没有。这台机器上的文件操作记录被清过，或者接口这一格没给数据。",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
            )
        }
    }
}

@Composable
private fun CrumbText(label: String, path: String, active: Boolean, onPick: (String) -> Unit) {
    val t = LocalNoLine.current
    Text(
        label,
        fontSize = if (active) 13.5.sp else 12.5.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) t.textStrong else t.accent,
        modifier = Modifier.clickable(enabled = !active) { onPick(path) }.padding(horizontal = 2.dp, vertical = 4.dp)
    )
}

@Composable
private fun FileRow(entry: BtFileEntry, busy: Boolean, onOpen: () -> Unit, onAction: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onOpen() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDir) Icons.Default.Folder else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = if (entry.isDir) t.accent else t.textLight
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 14.sp,
                fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                color = t.textStrong,
                maxLines = 2
            )
            if (entry.subtitle.isNotBlank()) {
                Text(entry.subtitle, fontSize = 11.5.sp, color = t.textLight, lineHeight = 16.sp, maxLines = 1)
            }
            if (entry.symlink.isNotBlank()) {
                Text(
                    "软链 → ${entry.symlink}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = t.textLight,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            if (entry.favorite) {
                Row(modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(11.dp), tint = t.accent)
                    Spacer(Modifier.width(3.dp))
                    Text("面板里收藏的这一层", fontSize = 11.sp, color = t.textLight)
                }
            }
        }
        if (entry.pinned) StatPill("置顶", tint = t.textLight)
        IconButton(onClick = onAction, enabled = !busy, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(19.dp), tint = t.textSecondary)
        }
    }
}

@Composable
private fun FileBatchRow(entry: BtFileEntry, checked: Boolean, onToggle: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, fontSize = 13.5.sp, color = t.textStrong, maxLines = 1)
            Text(
                listOfNotNull(if (entry.isDir) "目录" else entry.sizeText.takeIf { it.isNotBlank() }, entry.perm.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                fontSize = 11.sp,
                color = t.textLight,
                maxLines = 1
            )
        }
        if (btIsArchive(entry.name)) StatPill("压缩包", tint = t.accent)
    }
}

@Composable
private fun RecycleRow(
    entry: BtRecycleEntry,
    busy: Boolean,
    onRestore: () -> Unit,
    onRestoreTo: () -> Unit,
    onPurge: () -> Unit
) {
    val t = LocalNoLine.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
            if (entry.isDir) StatPill("目录", tint = t.accent)
        }
        Text(
            listOfNotNull(
                entry.timeText.takeIf { it.isNotBlank() }?.let { "删于 $it" },
                entry.sizeText.takeIf { it.isNotBlank() }?.let { "大小 $it" }
            ).joinToString(" · ").ifBlank { "面板这一行没给时间和大小" },
            fontSize = 11.5.sp,
            color = t.textLight,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            entry.originalPath,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = t.textSecondary,
            lineHeight = 16.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RowAction("放回原处", enabled = !busy, onClick = onRestore)
            RowAction("恢复到…", enabled = !busy, onClick = onRestoreTo)
            RowAction("彻底删掉", enabled = !busy, color = t.healthOver, onClick = onPurge)
        }
    }
}

@Composable
private fun HistoryFileRow(file: BtFileHistoryFile, onOpen: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.path,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = t.textStrong,
                lineHeight = 17.sp,
                maxLines = 2
            )
            if (file.time.isNotBlank()) {
                Text(file.time, fontSize = 11.sp, color = t.textLight, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (file.versions > 0) StatPill("${file.versions} 个版本", tint = t.accent)
    }
}

/** 一行条目能做的事。没有一颗按钮是猜的：每一颗都对应这台实测发得出去的那个 action。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    entry: BtFileEntry,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onLink: () -> Unit,
    onUnzip: () -> Unit,
    onAccess: () -> Unit,
    onSize: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                entry.path,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = t.textSecondary,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    if (entry.isDir) "目录" else entry.sizeText.takeIf { it.isNotBlank() }?.let { "大小 $it" },
                    entry.perm.takeIf { it.isNotBlank() }?.let { "权限 $it" },
                    entry.owner.takeIf { it.isNotBlank() }?.let { "属主 $it" },
                    entry.timeText.takeIf { it.isNotBlank() }?.let { "改于 $it" }
                ).joinToString(" · ").ifBlank { "面板这一行没给读数" },
                fontSize = 11.5.sp,
                color = t.textLight,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
            SheetAction(if (entry.isDir) "打开这个目录" else "编辑正文") { onDismiss(); onOpen() }
            SheetAction("改名字") { onDismiss(); onRename() }
            SheetAction("移动到别的目录") { onDismiss(); onMove() }
            SheetAction("复制到别的目录") { onDismiss(); onCopy() }
            SheetAction("在这里建软链") { onDismiss(); onLink() }
            if (!entry.isDir && btIsArchive(entry.name)) {
                SheetAction("解压") { onDismiss(); onUnzip() }
            }
            SheetAction("权限与属主") { onDismiss(); onAccess() }
            if (entry.isDir) SheetAction("问这一层的真实占用") { onDismiss(); onSize() }
            SheetAction("病毒查杀（只发指令，读不回结果）") { onDismiss(); onScan() }
            Spacer(Modifier.height(8.dp))
            SheetAction("删除", danger = true) { onDismiss(); onDelete() }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    entry.isDir && !entry.isNavigable ->
                        "这一行的名字不能当路径片段用（面板自己塞了 `..` 或者带斜杠），所以只能读，动不了。"
                    busy -> "上一发还没回，等它读完再按。"
                    else -> "改名和移动在这台面板上是同一个 `MvFile`：目标要给全路径，这一页替你把名字拼上。"
                },
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun SheetAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = if (danger) t.healthOver else t.textStrong, modifier = Modifier.weight(1f))
        if (danger) Text("会丢数据", fontSize = 11.sp, color = t.healthOver)
    }
    HorizontalDivider(color = t.hairline)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputSheet(
    dialog: BtFilesDialog,
    value: String,
    busy: Boolean,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val t = LocalNoLine.current
    val wantsName = dialog.kind in setOf(
        BtFilesDialog.Kind.NewDir,
        BtFilesDialog.Kind.NewFile,
        BtFilesDialog.Kind.Rename
    )
    val isError = (if (wantsName) BtFilesForm.nameError(value) else BtFilesForm.pathError(value)) != null && value.isNotBlank()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(dialog.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            dialog.entry?.let {
                Text(
                    "对象：" + it.path,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = t.textSecondary,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(dialog.label) },
                isError = isError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                dialog.hint,
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onSubmit, enabled = !busy && value.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "正在发给面板…" else "就按这个发出去", fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "发完之后这一页会重新读一遍当前视图，界面上看到的是重读回来的样子，不是面板那句话。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
        }
    }
}

/** 权限与属主：权限给三位八进制，属主从面板给的名单里挑。递归那一格不放。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessSheet(
    entry: BtFileEntry,
    access: BtFileAccess?,
    perm: String,
    owner: String,
    busy: Boolean,
    onPerm: (String) -> Unit,
    onOwner: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val t = LocalNoLine.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("权限与属主", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                entry.path,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = t.textSecondary,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = perm,
                onValueChange = onPerm,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("权限（三位八进制）") },
                isError = BtFilesForm.permError(perm) != null && perm.isNotBlank(),
                supportingText = { Text("常见：网站文件 644、脚本或目录 755；面板默认 umask 建出来是 755/644。", color = t.textLight) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("644", "755", "750", "700", "777").forEach { preset ->
                    NoLineChip(label = preset, selected = perm == preset, onClick = { onPerm(preset) })
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("属主", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            val users = access?.users.orEmpty()
            if (users.isEmpty()) {
                OutlinedTextField(
                    value = owner,
                    onValueChange = onOwner,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("系统用户名") },
                    supportingText = {
                        Text(
                            if (access == null) "正在问面板有哪些用户，先手写也行。"
                            else "这一格面板没给名单，只能自己填；填错面板会拒。",
                            color = t.textLight
                        )
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    users.forEach { user ->
                        NoLineChip(label = user, selected = owner == user, onClick = { onOwner(user) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = owner,
                    onValueChange = onOwner,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("或者自己写用户名") },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "面板给的这一层读数：权限 ${access?.perm?.ifBlank { "（没给）" } ?: "…"} · 属主 ${access?.owner?.ifBlank { "（没给）" } ?: "…"}。" +
                    "这一格面板叫 `SetFileAccess`，参数里那个 `filename` 装的其实是完整路径。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "「连同子文件一起改」那一格这一页故意不放：实测 `all=1` 发下去面板回「设置成功」，" +
                    "可里面的文件纹丝不动，那种假成功比报错更坑。要递归只能去网页端勾选。",
                fontSize = 11.5.sp,
                color = t.warnText,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onSubmit, enabled = !busy && perm.isNotBlank() && owner.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "正在发给面板…" else "把权限和属主发出去", fontSize = 14.sp)
            }
        }
    }
}

/** 正文编辑器。这一格只做到「读回来、改、发回去」，没有语法高亮，也没有多标签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(
    editor: BtEditorState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val t = LocalNoLine.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        btNameOf(editor.body.path),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong,
                        maxLines = 1
                    )
                    Text(
                        editor.body.path,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = t.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSave, enabled = !busy && editor.dirty && !editor.body.readOnly) {
                    Text(if (busy) "发送中…" else "保存", fontSize = 14.sp, color = if (editor.dirty) t.accent else t.textLight)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(editor.body.encodingLabel)
                    editor.body.sizeBytes?.let { append(" · ${it} 字节") }
                    if (editor.body.historyCount > 0) append(" · 面板里存了 ${editor.body.historyCount} 个版本")
                    if (editor.body.autoSave) append(" · 面板那边开着自动保存")
                },
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
            if (editor.loading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(color = t.accent, trackColor = t.hairline)
                Text("正在从面板读正文…", fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            if (editor.body.readOnly) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "面板认这个文件是只读的（`only_read`），保存会被它拒。要改得先在网页端放开，或者改权限。",
                    fontSize = 11.5.sp,
                    color = t.warnText,
                    lineHeight = 17.sp
                )
            }
            if (editor.body.tooLarge) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "这个文件超过 2MB，面板的编辑口大概率会拒（实测那句是「文件大小超出限制」）。",
                    fontSize = 11.5.sp,
                    color = t.warnText,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editor.content,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors(),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 19.sp,
                    color = t.textMain
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (editor.dirty) "改了 ${editor.content.length - editor.body.content.length} 个字符的样子，按「保存」才写盘。"
                else "还没改动。关掉这一格不会保存任何东西。",
                fontSize = 11.sp,
                color = if (editor.dirty) t.accent else t.textLight,
                lineHeight = 16.sp
            )
        }
    }
}

/** 旁路读数：Composer、备份方案、这台有没有开同步。都只是读数。 */
@Composable
private fun ToolsCard(tools: BtFilesTools) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("这一台的相关读数", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        ToolLine("Composer 版本", tools.composer.ifBlank { "面板没给（这台可能没装）" })
        ToolLine("面板配的备份方案", if (tools.backupCount == 0) "一个都没配（面板回的是空数组）" else "${tools.backupCount} 个")
        ToolLine(
            "文件同步",
            when {
                tools.syncFailed -> "同步工具的接口没答这一格"
                tools.syncOn -> "开着"
                else -> "没开"
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "这三格是从 `get_composer_version`、`GetBackupList`、`sync_status` 现读的，哪一格失败就写「没读到」，不拿 0 或「没配」顶上去。" +
                "装 RAR 支持、更新 Composer 这两颗在「＋」那个弹层里。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun ToolLine(label: String, value: String) {
    val t = LocalNoLine.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, color = t.textStrong, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RowAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = LocalNoLine.current.textSecondary
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, fontSize = 12.5.sp, color = if (enabled) color else LocalNoLine.current.textLight)
    }
}

/** 这台面板的 files 接口做不到的事，全部是实测撞过的，写在最底下而不是散在各处。 */
@Composable
private fun FilesLimitsCard() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("这一格面板接口做不到", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(4.dp))
        Text(
            "不是没做，是发过去被拒或者会误伤。每一条后面都跟着实测凭据。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        BtFilesLimits.forEach { (title, detail) ->
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text("· ", fontSize = 12.sp, color = t.textSecondary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
                    Text(detail, fontSize = 11.5.sp, color = t.textSecondary, lineHeight = 17.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "还有两件是这一页故意不放的：回收站开关（`Recycle_bin`，文档只给了 `db` 一个参数，猜错的后果是以后删除直接永久生效）" +
                "和把文件发给第三方（`send_baota`）。前者要改去网页端，后者本来就不该在手机上做。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
    }
}

private data class FilesPending(
    val label: String,
    val body: String,
    val keyword: String,
    val mustType: Boolean,
    val run: () -> Unit
)

/** 手机上选的那个文件：名字、字节数、MIME。SAF 不给长度就是发不了的。 */
private data class PickedFile(val name: String, val sizeBytes: Long, val mime: String)

private fun btPickedFile(context: Context, uri: Uri): PickedFile {
    val resolver = context.contentResolver
    var name = "uploaded.bin"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    if (size <= 0L) {
        runCatching { resolver.openFileDescriptor(uri, "r")?.use { fd -> if (fd.statSize > 0L) size = fd.statSize } }
    }
    return PickedFile(name, size, resolver.getType(uri).orEmpty())
}

/** 路径尾巴上那一段，用来给收藏/最近那些整路径当 chip 文案。 */
private fun btPathLabel(path: String): String {
    val base = path.trimEnd('/')
    return when {
        base.isBlank() -> "根目录"
        !base.contains("/") -> base
        else -> base.substringAfterLast('/')
    }
}

private fun btNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
