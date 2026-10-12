package noline.zhouzhou.ui.screens.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel
import noline.zhouzhou.util.HtmlContent
import noline.zhouzhou.util.MarkdownRenderer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    appContainer: AppContainer,
    postId: String?,
    isPage: Boolean = false,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val viewModel = typechoViewModel<EditorViewModel>(appContainer) { EditorViewModel(it, postId, isPage) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }
    val pickImage: () -> Unit = {
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            // clearSaveSuccess() 会把提醒一起抹掉，所以先取值。
            val notice = uiState.saveNotice
            android.widget.Toast.makeText(
                context,
                notice ?: context.getString(R.string.admin_saved),
                android.widget.Toast.LENGTH_LONG
            ).show()
            viewModel.clearSaveSuccess()
            onSaved()
        }
    }

    // 沉浸模式隐藏系统状态栏/导航栏，退出时恢复。
    LaunchedEffect(uiState.isImmersive) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (uiState.isImmersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.editor_discard)) },
            text = { Text(stringResource(R.string.editor_discard_confirm)) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 本地稿和编辑器内容不一致时才弹；不给点外面逃逸，避免糊里糊涂丢了稿。
    uiState.pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发现本地草稿") },
            text = {
                Text(
                    buildString {
                        append("自动保存于 ${draftStamp(draft.updatedAt)}")
                        if (draft.content.isNotBlank()) append(" · 正文 ${draft.content.length} 字")
                        if (postId != null) append("\n\n编辑器里现在是站点上的版本，恢复会用本地这份盖掉它。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::restoreDraft) { Text("恢复本地稿") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dropDraft) { Text("丢弃") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (!uiState.isImmersive) TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(
                                when {
                                    isPage && postId != null -> R.string.admin_edit_page
                                    isPage -> R.string.admin_new_page
                                    postId != null -> R.string.admin_edit_post
                                    else -> R.string.admin_new_post
                                }
                            )
                        )
                        if (uiState.draftSavedAt > 0) {
                            Text(
                                "已自动保存 ${draftClock(uiState.draftSavedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalNoLine.current.textSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.hasChanges) showDiscardDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::togglePreview) {
                        Icon(
                            if (uiState.isPreview) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = if (uiState.isPreview) "编辑" else "预览"
                        )
                    }
                    IconButton(onClick = viewModel::toggleImmersive) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "沉浸写作")
                    }
                    IconButton(onClick = { showMoreOptions = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMoreOptions, onDismissRequest = { showMoreOptions = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.admin_publish)) },
                            leadingIcon = { Icon(Icons.Default.Publish, contentDescription = null) },
                            onClick = { showMoreOptions = false; viewModel.save(publish = true) }
                        )
                        DropdownMenuItem(
                            text = { Text("存为草稿") },
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                            onClick = { showMoreOptions = false; viewModel.save(publish = false) }
                        )
                    }
                    Button(
                        onClick = { viewModel.save(publish = uiState.status == "publish") },
                        modifier = Modifier.padding(end = 12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.admin_save))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalNoLine.current.pageBg,
                    titleContentColor = LocalNoLine.current.textStrong,
                    navigationIconContentColor = LocalNoLine.current.textStrong,
                    actionIconContentColor = LocalNoLine.current.textSecondary
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.isImmersive) {
            ImmersiveEditor(
                uiState = uiState,
                onContentValueChange = viewModel::onContentValueChange,
                onApply = viewModel::applyMarkdown,
                onPickImage = pickImage,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = { viewModel.save(publish = uiState.status == "publish") },
                onExit = viewModel::toggleImmersive
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                AnimatedVisibility(visible = !uiState.isPreview) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Title
                        OutlinedTextField(
                            value = uiState.title,
                            onValueChange = viewModel::onTitleChange,
                            placeholder = {
                                Text(stringResource(
                                    if (isPage) R.string.editor_page_title_hint else R.string.editor_title_hint
                                ))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            shape = MaterialTheme.shapes.medium,
                            colors = noLineFieldColors()
                        )

                        Spacer(Modifier.height(12.dp))

                        // Markdown toolbar
                        MarkdownToolbar(
                            onAction = viewModel::applyMarkdown,
                            onPickImage = pickImage,
                            isUploadingImage = uiState.isUploadingImage,
                            onUndo = viewModel::undo,
                            onRedo = viewModel::redo,
                            canUndo = uiState.canUndo,
                            canRedo = uiState.canRedo
                        )

                        Spacer(Modifier.height(8.dp))

                        // Content
                        OutlinedTextField(
                            value = TextFieldValue(uiState.content, uiState.contentSelection),
                            onValueChange = viewModel::onContentValueChange,
                            placeholder = { Text(stringResource(R.string.editor_content_hint)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = Int.MAX_VALUE,
                            shape = MaterialTheme.shapes.medium,
                            colors = noLineFieldColors()
                        )

                        if (uiState.isUploadingImage) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp), strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在压缩并上传到站点…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Expandable: publish time, excerpt, tags, categories, status
                        var showAdvanced by remember { mutableStateOf(false) }
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Icon(
                                if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("高级选项")
                        }

                        AnimatedVisibility(visible = showAdvanced) {
                            Column {
                                Spacer(Modifier.height(8.dp))

                                // 发布时间：不给站点 dateCreated 它就会自己推算成「现在」
                                Text("发布时间", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                PublishTimeRow(epochSeconds = uiState.publishAt,
                                    onChange = viewModel::onDateChange)

                                Spacer(Modifier.height(12.dp))

                                // Excerpt
                                OutlinedTextField(
                                    value = uiState.excerpt,
                                    onValueChange = viewModel::onExcerptChange,
                                    placeholder = { Text(stringResource(R.string.editor_excerpt_hint)) },
                                    label = { Text("摘要") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2, maxLines = 4,
                                    colors = noLineFieldColors()
                                )

                                Spacer(Modifier.height(8.dp))

                                // Tags
                                OutlinedTextField(
                                    value = uiState.tagsText,
                                    onValueChange = viewModel::onTagsChange,
                                    placeholder = { Text(stringResource(R.string.editor_tags_hint)) },
                                    label = { Text("标签") },
                                    leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    colors = noLineFieldColors()
                                )

                                Spacer(Modifier.height(8.dp))

                                // Categories（页面没有分类这一说，只在文章里出现）
                                if (!isPage) {
                                    Text(stringResource(R.string.editor_category),
                                        style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        uiState.categories.forEach { cat ->
                                            NoLineChip(
                                                label = cat.name,
                                                selected = uiState.selectedCategories.contains(cat.name),
                                                onClick = { viewModel.toggleCategory(cat.name) }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                }

                                // Status
                                Text("发布状态", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("publish" to "发布", "draft" to "草稿", "private" to "私密")
                                        .forEach { (value, label) ->
                                            NoLineChip(
                                                label = label,
                                                selected = uiState.status == value,
                                                onClick = { viewModel.onStatusChange(value) }
                                            )
                                        }
                                }
                            }
                        }

                        uiState.error?.let { error ->
                            Spacer(Modifier.height(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.isPreview) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .noLineCard(RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 20.dp)
                    ) {
                        Text(
                            text = uiState.title.ifBlank { "（无标题）" },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = LocalNoLine.current.textStrong
                        )
                        Spacer(Modifier.height(16.dp))
                        HtmlContent(html = MarkdownRenderer.toHtml(uiState.content))
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(
    onAction: (MarkdownAction) -> Unit,
    onPickImage: () -> Unit,
    isUploadingImage: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean
) {
    val groups = listOf(
        listOf(
            Triple(Icons.Default.FormatBold, "加粗", MarkdownAction.Bold),
            Triple(Icons.Default.FormatItalic, "斜体", MarkdownAction.Italic),
            Triple(Icons.Default.FormatStrikethrough, "删除线", MarkdownAction.Strike),
            Triple(Icons.Default.Title, "标题", MarkdownAction.Heading2)
        ),
        listOf(
            Triple(Icons.Default.FormatListBulleted, "无序列表", MarkdownAction.BulletList),
            Triple(Icons.Default.FormatListNumbered, "有序列表", MarkdownAction.OrderedList),
            Triple(Icons.Default.FormatQuote, "引用", MarkdownAction.Quote),
            Triple(Icons.Default.Code, "行内代码", MarkdownAction.InlineCode)
        ),
        listOf(
            Triple(Icons.Default.Link, "链接", MarkdownAction.Link),
            Triple(Icons.Default.TableChart, "表格", MarkdownAction.Table),
            Triple(Icons.Default.HorizontalRule, "分割线", MarkdownAction.Divider)
        )
    )
    Surface(
        modifier = Modifier.fillMaxWidth().noLineCard(MaterialTheme.shapes.medium),
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 撤销 / 重做
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Undo, contentDescription = "撤销",
                    modifier = Modifier.size(20.dp),
                    tint = if (canUndo) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Redo, contentDescription = "重做",
                    modifier = Modifier.size(20.dp),
                    tint = if (canRedo) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(24.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            // 图片：直接从相册挑，压缩后上传到站点再插进光标处
            IconButton(onClick = onPickImage, modifier = Modifier.size(40.dp)) {
                if (isUploadingImage) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Image, contentDescription = "插入图片",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(24.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            // 格式化按钮
            groups.forEachIndexed { gi, group ->
                group.forEach { (icon, label, action) ->
                    IconButton(onClick = { onAction(action) }, modifier = Modifier.size(40.dp)) {
                        Icon(icon, contentDescription = label,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (gi < groups.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(24.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
    }
}

/** 全屏沉浸写作：隐藏系统栏与多余控件，只留一个舒适的大输入区和极简顶栏。 */
@Composable
private fun ImmersiveEditor(
    uiState: EditorUiState,
    onContentValueChange: (TextFieldValue) -> Unit,
    onApply: (MarkdownAction) -> Unit,
    onPickImage: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "退出沉浸")
            }
            Spacer(Modifier.weight(1f))
            Text(
                uiState.title.ifBlank { stringResource(R.string.editor_title_hint) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onUndo, enabled = uiState.canUndo) {
                Icon(Icons.Default.Undo, contentDescription = "撤销")
            }
            IconButton(onClick = onRedo, enabled = uiState.canRedo) {
                Icon(Icons.Default.Redo, contentDescription = "重做")
            }
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(horizontal = 8.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onSave, modifier = Modifier.padding(end = 4.dp)) {
                    Text(stringResource(R.string.admin_save))
                }
            }
        }

        uiState.error?.let { error ->
            Text(
                error,
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            if (uiState.content.isEmpty()) {
                Text(
                    stringResource(R.string.editor_content_hint),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            BasicTextField(
                value = TextFieldValue(uiState.content, uiState.contentSelection),
                onValueChange = onContentValueChange,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    lineHeight = 32.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }

        MarkdownToolbar(
            onAction = onApply,
            onPickImage = onPickImage,
            isUploadingImage = uiState.isUploadingImage,
            onUndo = onUndo,
            onRedo = onRedo,
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo
        )
        Spacer(Modifier.navigationBarsPadding().height(4.dp))
    }
}

private val draftClockFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private val draftDayFormat = java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())

private fun draftClock(millis: Long): String = draftClockFormat.format(java.util.Date(millis))

/** 顶栏里只报时刻；隔了天的本地稿要在弹窗里带上日期。 */
private fun draftStamp(millis: Long): String {
    val date = java.util.Date(millis)
    return if (draftDayFormat.format(date) == draftDayFormat.format(java.util.Date())) draftClock(millis)
    else "${draftDayFormat.format(date)} ${draftClock(millis)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable FlowRowScope.() -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content
    )
}

private val publishDateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val publishTimeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/**
 * 两颗药丸分别开日历和时分盘，日期按设备时区算成 epoch 秒。
 * 日历控件内部用 UTC 毫秒表示「哪一天」，来回都走 UTC，别掺本地时区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublishTimeRow(epochSeconds: Long, onChange: (Long) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val zone = java.time.ZoneId.systemDefault()
    val moment = java.time.Instant.ofEpochSecond(epochSeconds).atZone(zone)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NoLineChip(label = moment.toLocalDate().format(publishDateFormat),
            selected = false, onClick = { showDate = true }, icon = Icons.Default.DateRange)
        NoLineChip(label = moment.toLocalTime().format(publishTimeFormat),
            selected = false, onClick = { showTime = true }, icon = Icons.Default.Schedule)
        if (kotlin.math.abs(System.currentTimeMillis() / 1000L - epochSeconds) > 60) {
            TextButton(onClick = { onChange(System.currentTimeMillis() / 1000L) }) { Text("改为此刻") }
        }
    }

    if (showDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = moment.toLocalDate()
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val picked = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        onChange(picked.atTime(moment.toLocalTime()).atZone(zone).toEpochSecond())
                    }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            }
        ) { DatePicker(state = dateState) }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = moment.hour,
            initialMinute = moment.minute,
            is24Hour = true
        )
        // 这版 BOM 还没有 TimePickerDialog，自己用 AlertDialog 装一个
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(moment.toLocalDate().atTime(timeState.hour, timeState.minute)
                        .atZone(zone).toEpochSecond())
                    showTime = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("取消") }
            }
        )
    }
}
