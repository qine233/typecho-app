package noline.zhouzhou.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.remote.CloudreveCapacity
import noline.zhouzhou.data.remote.CloudreveFileNode
import noline.zhouzhou.ui.screens.admin.ErrorStrip
import noline.zhouzhou.ui.screens.admin.RowIconButton
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel
import kotlin.math.roundToInt
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Cloudreve 网盘管理：浏览、建目录、改名、移动、丢回收站，以及给单个文件手动生成永久直链。
 * 只做软删除 —— 彻底删除要额外的权限，Cloudreve 会给普通账号 403。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(appContainer: AppContainer, onBack: () -> Unit) {
    val viewModel = typechoViewModel<DriveViewModel>(appContainer) { DriveViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    val clipboard = LocalClipboardManager.current

    var folderName by remember { mutableStateOf<String?>(null) }
    var nodeToRename by remember { mutableStateOf<CloudreveFileNode?>(null) }
    var nodeToMove by remember { mutableStateOf<CloudreveFileNode?>(null) }
    var nodeToDelete by remember { mutableStateOf<CloudreveFileNode?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, uiState.nodes.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 1 && lastVisible >= total - 2) viewModel.loadMore()
        }
    }

    when {
        folderName != null -> TextInputDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { folderName = null },
            onConfirm = { name ->
                viewModel.createFolder(name)
                folderName = null
            }
        )
        nodeToRename != null -> TextInputDialog(
            title = stringResource(R.string.drive_rename),
            initial = nodeToRename?.name.orEmpty(),
            confirmLabel = stringResource(R.string.drive_rename),
            onDismiss = { nodeToRename = null },
            onConfirm = { name ->
                nodeToRename?.let { viewModel.rename(it, name) }
                nodeToRename = null
            }
        )
    }

    nodeToMove?.let { node ->
        MoveTargetDialog(
            viewModel = viewModel,
            node = node,
            onDismiss = { nodeToMove = null },
            onConfirm = { dst ->
                viewModel.move(node, dst)
                nodeToMove = null
            }
        )
    }

    nodeToDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { nodeToDelete = null },
            title = { Text(stringResource(R.string.drive_delete)) },
            text = { Text(stringResource(R.string.drive_delete_confirm, node.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.softDelete(node)
                    nodeToDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { nodeToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    uiState.link?.let { link ->
        AlertDialog(
            onDismissRequest = viewModel::clearLink,
            title = { Text(stringResource(R.string.drive_direct_link)) },
            text = {
                Column {
                    Text(
                        link.node.name,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        link.url,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "这个地址永久有效，游客也能打开，可以直接贴进文章。",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = t.textLight
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(link.url))
                    viewModel.clearLink()
                }) { Text(stringResource(R.string.action_copy_link)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::clearLink) { Text(stringResource(R.string.action_close)) }
            }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong)
                    }
                    Text(
                        stringResource(R.string.drive_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong
                    )
                    Spacer(Modifier.weight(1f))
                    if (uiState.isLoading || uiState.isFetchingLink) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp).size(17.dp),
                            strokeWidth = 2.dp,
                            color = t.accent
                        )
                    } else {
                        IconButton(
                            onClick = { folderName = "" },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, "新建文件夹",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                        IconButton(onClick = viewModel::reload, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "刷新",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                    }
                }
                Breadcrumbs(trail = driveCrumbTrail(uiState.dirUri), onOpen = viewModel::open)
                uiState.capacity?.let { CapacityLine(it) }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // 改名/移动/删除都在当前页面上就地生效，只给一条细线表示在跑，别把列表换成骨架屏。
            if (uiState.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = t.accent,
                    trackColor = t.hairline
                )
            }
            uiState.error?.let { message ->
                ErrorStrip(message = message, onDismiss = viewModel::clearError)
            }

            when {
                uiState.isLoading && uiState.nodes.isEmpty() -> {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        repeat(5) { DriveSkeleton() }
                    }
                }
                uiState.nodes.isEmpty() && !uiState.isLoading -> {
                    EmptyDrive(onNewFolder = { folderName = "" })
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)
                ) {
                    items(uiState.nodes, key = { it.path }) { node ->
                        DriveRow(
                            node = node,
                            onClick = {
                                when {
                                    node.isFolder -> viewModel.open(node.path)
                                    else -> viewModel.fetchLink(node)
                                }
                            },
                            onRename = { nodeToRename = node },
                            onMove = { nodeToMove = node },
                            onDelete = { nodeToDelete = node }
                        )
                    }
                    item(key = "footer") {
                        if (uiState.isLoadingMore) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                color = t.accent
                            )
                        } else if (uiState.nextToken.isBlank()) {
                            Text(
                                "共 ${uiState.nodes.size} 项",
                                fontSize = 12.sp,
                                color = t.textLight,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(trail: List<DriveCrumb>, onOpen: (String) -> Unit) {
    val t = LocalNoLine.current
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(trail) { crumb ->
            Text(
                crumb.label,
                fontSize = 12.5.sp,
                fontWeight = if (crumb == trail.last()) FontWeight.SemiBold else FontWeight.Normal,
                color = if (crumb == trail.last()) t.textStrong else t.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = crumb != trail.last()) { onOpen(crumb.uri) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            if (crumb != trail.last()) {
                Text("›", fontSize = 12.sp, color = t.textLight)
            }
        }
    }
}

/**
 * Cloudreve 记账的容量。只能当「已用多少」看：本站实测这行显示只用了 1%，OneDrive 那头其实早写满了
 * —— 它记的是配额账，不是存储端剩余，所以满了也不会由这行提前预警。
 */
@Composable
private fun CapacityLine(capacity: CloudreveCapacity) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(t.surfaceSunken)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已用 ${capacityText(capacity.used)}" +
                    if (capacity.hasQuota) " / ${capacityText(capacity.total)}" else "",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textMain
            )
            Spacer(Modifier.weight(1f))
            if (capacity.hasQuota) {
                Text(
                    "${(capacity.usedFraction * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    color = t.textSecondary
                )
            }
        }
        if (capacity.hasQuota) {
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(t.cardBg)
            ) {
                Box(
                    Modifier
                        // 用量太小时留一小截头，否则整条看起来是灰的、像没加载出来。
                        .fillMaxWidth(capacity.usedFraction.coerceIn(0.03f, 1f))
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (capacity.usedFraction >= 0.9f) t.warnText else t.accent)
                )
            }
        }
    }
}

@Composable
private fun DriveRow(
    node: CloudreveFileNode,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (node.isFolder) t.accentBg else t.surfaceSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (node.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (node.isFolder) t.accent else t.textSecondary
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.name.ifBlank { "未命名" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = t.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            val meta = if (node.isFolder) node.timeText() else "${node.sizeText()} · ${node.timeText()}"
            Text(meta, fontSize = 11.5.sp, color = t.textLight, maxLines = 1)
        }
        if (!node.isFolder) {
            RowIconButton(Icons.Default.Link, "生成直链", onClick, t.accent)
        }
        RowIconButton(Icons.Default.DriveFileRenameOutline, "重命名", onRename, t.textSecondary)
        RowIconButton(Icons.Default.DriveFileMove, "移动", onMove, t.textSecondary)
        RowIconButton(Icons.Default.Delete, "删除", onDelete,
            MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val name = text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.drive_name_hint)) },
                supportingText = { Text(stringResource(R.string.drive_name_rule)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotEmpty() && '/' !in name
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** 「移动到」挑目标目录：只能一层层进，挑好后按「移到此处」确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTargetDialog(
    viewModel: DriveViewModel,
    node: CloudreveFileNode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val t = LocalNoLine.current
    val state = rememberSaveable { mutableStateOf(DriveRootUri) }
    var folders by remember { mutableStateOf<List<CloudreveFileNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.value) {
        loading = true
        failure = null
        viewModel.foldersIn(state.value)
            .onSuccess { folders = it; loading = false }
            .onFailure { e -> failure = e.message; loading = false }
    }

    // 不能把目录移进自己，也不能移进自己的子孙里 —— Cloudreve 会直接 400。
    val isSelfOrChild = state.value == node.path ||
        (node.isFolder && state.value.startsWith(node.path + "/"))
    val canConfirm = !isSelfOrChild && state.value != node.path.substringBeforeLast('/')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_move)) },
        text = {
            Column {
                Text(
                    driveCrumbTrail(state.value).joinToString(" › ") { it.label },
                    fontSize = 12.5.sp,
                    color = t.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp, color = t.accent)
                    failure != null -> Text(failure ?: "", fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.error)
                    folders.isEmpty() -> Text("这一层没有子目录", fontSize = 12.5.sp, color = t.textLight)
                    else -> Column(
                        // 根目录能有几十个文件夹，不给自己限高就会把「移到此处」顶出屏幕。
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        folders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { state.value = folder.path }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, modifier = Modifier.size(17.dp), tint = t.accent)
                                Spacer(Modifier.width(9.dp))
                                Text(folder.name, fontSize = 14.sp, color = t.textStrong, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (isSelfOrChild) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "不能把「${node.name}」放进它自己里面。",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.value) }, enabled = canConfirm) {
                Text(stringResource(R.string.drive_move_here))
            }
        },
        dismissButton = {
            Row {
                if (state.value != DriveRootUri) {
                    TextButton(onClick = {
                        state.value = state.value.substringBeforeLast('/')
                    }) { Text("上一级") }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

@Composable
private fun DriveSkeleton() {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .noLineCard()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(t.coverFallback)
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Box(
                Modifier.fillMaxWidth(0.45f).height(12.dp)
                    .clip(RoundedCornerShape(6.dp)).background(t.coverFallback)
            )
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier.fillMaxWidth(0.22f).height(10.dp)
                    .clip(RoundedCornerShape(6.dp)).background(t.hairline)
            )
        }
    }
}

@Composable
private fun EmptyDrive(onNewFolder: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("这个目录是空的", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.drive_empty_hint),
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = t.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.drive_new_folder),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = t.onBrand,
            modifier = Modifier
                .clip(CircleShape)
                .background(t.accent)
                .clickable(onClick = onNewFolder)
                .padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}

// ── 展示用的小工具 ──

private val DriveTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun CloudreveFileNode.sizeText(): String = when {
    isFolder -> ""
    size >= 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
    size >= 1024 -> "%.0f KB".format(size / 1024.0)
    else -> "$size B"
}

/** 容量这种大数用 GB/TB，用 MB 会变成一长串七位数。 */
private fun capacityText(bytes: Long): String = when {
    bytes >= 1L shl 40 -> "%.1f TB".format(bytes / 1024.0 / 1024 / 1024 / 1024)
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "$bytes B"
}

/** Cloudreve 给的是带时区的 RFC3339，且小数位长度不固定；解析不了就干脆不显示，别说假时间。 */
fun CloudreveFileNode.timeText(): String {
    val raw = updatedAt.ifBlank { createdAt }
    if (raw.isBlank()) return ""
    return runCatching { OffsetDateTime.parse(raw).format(DriveTimeFormat) }.getOrDefault("")
}
