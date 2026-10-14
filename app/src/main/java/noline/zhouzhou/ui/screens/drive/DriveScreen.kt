package noline.zhouzhou.ui.screens.drive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 提示条停留多久：够看完一句话，又不至于挡住下一次操作。分享页也用同一个时长。 */
const val NoticeLingerMs = 3_500L

/** 分享有效期给的几档，单位是 Cloudreve 要的秒数，0 表示不过期。 */
private val ShareExpireOptions = listOf(
    "不过期" to 0,
    "1 天" to 86_400,
    "7 天" to 604_800,
    "30 天" to 2_592_000
)

/**
 * Cloudreve 网盘管理：浏览、上传、下载、建目录、改名、移动、丢回收站，
 * 以及给单个文件生成永久直链或一条分享。
 * 只做软删除 —— 彻底删除要额外的权限，Cloudreve 会给普通账号 403。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(appContainer: AppContainer, onBack: () -> Unit, onOpenShares: () -> Unit) {
    val viewModel = typechoViewModel<DriveViewModel>(appContainer) { DriveViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    val clipboard = LocalClipboardManager.current

    var folderName by remember { mutableStateOf<String?>(null) }
    var nodeToRename by remember { mutableStateOf<CloudreveFileNode?>(null) }
    var nodeToMove by remember { mutableStateOf<CloudreveFileNode?>(null) }
    var nodeToDelete by remember { mutableStateOf<CloudreveFileNode?>(null) }
    var nodeToShare by remember { mutableStateOf<CloudreveFileNode?>(null) }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::upload) }

    LaunchedEffect(uiState.notice) {
        if (uiState.notice != null) {
            delay(NoticeLingerMs)
            viewModel.clearNotice()
        }
    }

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

    nodeToShare?.let { node ->
        CreateShareDialog(
            node = node,
            onDismiss = { nodeToShare = null },
            onCreate = { password, expireSeconds ->
                viewModel.createShare(node, password, expireSeconds)
                nodeToShare = null
            }
        )
    }

    uiState.link?.let { link ->
        AlertDialog(
            onDismissRequest = viewModel::clearLink,
            title = { Text(stringResource(R.string.drive_link_ready)) },
            text = {
                Column {
                    Text(
                        link.name,
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
                        link.hint,
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
                        // 上传中的进度条自己会说话，这里就不让再点第二次。
                        IconButton(
                            onClick = { uploadPicker.launch(arrayOf("*/*")) },
                            enabled = uiState.upload == null,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, "上传文件",
                                modifier = Modifier.size(19.dp),
                                tint = if (uiState.upload == null) t.textSecondary else t.textLight)
                        }
                        IconButton(
                            onClick = { folderName = "" },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, "新建文件夹",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                        IconButton(onClick = onOpenShares, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Share, "分享管理",
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
            uiState.upload?.let { UploadProgress(it) }
            uiState.notice?.let { message ->
                Text(
                    message,
                    fontSize = 12.5.sp,
                    color = t.accent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
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
                            onDownload = { viewModel.download(node) },
                            onLink = { viewModel.fetchLink(node) },
                            onShare = { nodeToShare = node },
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
    onDownload: () -> Unit,
    onLink: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    var menuOpen by remember { mutableStateOf(false) }
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
        // 目录不能取下载地址（Cloudreve 会把子项一个个塞进 zip，实测直接 40081），所以只有文件给下载。
        if (!node.isFolder) {
            RowIconButton(Icons.Default.Download, "下载到本机", onDownload, t.accent)
        }
        Box {
            RowIconButton(Icons.Default.MoreVert, "更多操作", { menuOpen = true }, t.textSecondary)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!node.isFolder) {
                    DriveMenuItem(R.string.drive_direct_link, Icons.Default.Link) {
                        menuOpen = false
                        onLink()
                    }
                }
                DriveMenuItem(R.string.share_create, Icons.Default.Share) {
                    menuOpen = false
                    onShare()
                }
                DriveMenuItem(R.string.drive_rename, Icons.Default.DriveFileRenameOutline) {
                    menuOpen = false
                    onRename()
                }
                DriveMenuItem(R.string.drive_move, Icons.Default.DriveFileMove) {
                    menuOpen = false
                    onMove()
                }
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.drive_delete), color = MaterialTheme.colorScheme.error)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun DriveMenuItem(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes), fontSize = 14.sp) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        onClick = onClick
    )
}

/** 顶部那条上传进度：名字 + 已送/共 + 一根进度条，传完就自己消失。 */
@Composable
private fun UploadProgress(upload: DriveUpload) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(t.surfaceSunken)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.drive_uploading, upload.name),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = t.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${capacityText(upload.done)} / ${capacityText(upload.total)}",
                fontSize = 11.5.sp,
                color = t.textSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { upload.fraction },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = t.accent,
            trackColor = t.cardBg
        )
    }
}

/**
 * 建分享：提取码可选（Cloudreve 只收字母数字、最长 32 位），有效期给四档。
 * 链接本身是公开还是私密由服务端决定，这里只负责把规格说清楚。
 */
@Composable
private fun CreateShareDialog(
    node: CloudreveFileNode,
    onDismiss: () -> Unit,
    onCreate: (String, Int) -> Unit
) {
    val t = LocalNoLine.current
    var password by remember { mutableStateOf("") }
    var expireSeconds by remember { mutableStateOf(0) }

    val trimmed = password.trim()
    // 服务端是 alphanum 校验，这里先挡一道，免得点完只拿到一句笼统的 40001。
    val passwordOk = trimmed.isBlank() ||
        (trimmed.length <= 32 && trimmed.all { it.isLetterOrDigit() })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_create)) },
        text = {
            Column {
                Text(
                    node.name + if (node.isFolder) "（整个目录）" else "（${node.sizeText()}）",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.share_password_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = { Text(stringResource(R.string.share_password_rule)) },
                    isError = !passwordOk
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.share_expire_label), fontSize = 12.sp, color = t.textMain)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ShareExpireOptions.forEach { (label, seconds) ->
                        val selected = seconds == expireSeconds
                        Text(
                            label,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) t.onBrand else t.textSecondary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) t.accent else t.surfaceSunken)
                                .clickable { expireSeconds = seconds }
                                .padding(horizontal = 11.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(trimmed, expireSeconds) },
                enabled = passwordOk
            ) { Text(stringResource(R.string.action_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
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
