package noline.zhouzhou.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.repository.DraftEntry
import noline.zhouzhou.data.repository.StorageEntry
import noline.zhouzhou.data.repository.StorageRepository
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel
import java.text.DateFormat
import java.util.Date

/** 存储占用 + App 内清理 + 草稿与待同步队列的可见入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<StorageViewModel>(appContainer) { StorageViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var dropConfirm by remember { mutableStateOf(false) }
    val message = state.message

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3200)
            viewModel.consumeMessage()
        }
    }

    if (dropConfirm) {
        AlertDialog(
            onDismissRequest = { dropConfirm = false },
            title = { Text("丢弃待同步记录？") },
            text = { Text("这 ${state.pendingCount} 条还没提交到服务器，丢弃之后本地也不会再留副本。") },
            confirmButton = {
                TextButton(onClick = {
                    dropConfirm = false
                    viewModel.dropAllPending()
                }) { Text("丢弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dropConfirm = false }) { Text("取消") } }
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
                    Text("数据与存储", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = viewModel::refresh, enabled = !state.isBusy) {
                        Icon(
                            Icons.Default.Refresh, contentDescription = "重新统计",
                            modifier = Modifier.size(20.dp), tint = t.textSecondary
                        )
                    }
                }
            }
        }
    ) { padding ->
        val usage = state.usage
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(visible = message != null) {
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textMain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .noLineCard(RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                }
            }

            if (usage == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = t.accent)
                }
            } else {
                SummaryCard(usage.totalBytes, usage.freeBytes, usage.entries)

                Spacer(Modifier.height(14.dp))
                SectionTitle("缓存 · 可清理")
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.cardBg)) {
                    Column {
                        usage.entries.filter { it.clearable }.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = t.hairline)
                            StorageRow(entry) { viewModel.clear(setOf(entry.key)) }
                        }
                        HorizontalDivider(color = t.hairline)
                        TextButton(
                            onClick = { viewModel.clear(StorageRepository.CACHE_KEYS) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text("全部清理", color = t.accent, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Text(
                    "清理缓存不会动草稿和待同步记录，图片、文章列表下次打开时重新拉取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textLight,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                )

                Spacer(Modifier.height(14.dp))
                SectionTitle("本地草稿 · ${state.drafts.size} 份")
                if (state.drafts.isEmpty()) {
                    EmptyCard("编辑器会自动存稿，目前还没有留下的稿子。")
                } else {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.cardBg)) {
                        Column {
                            state.drafts.forEachIndexed { index, draft ->
                                if (index > 0) HorizontalDivider(color = t.hairline)
                                DraftRow(draft) { viewModel.discardDraft(draft.key) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle("待同步记录 · ${state.pendingCount} 条")
                if (state.pendingCount == 0) {
                    EmptyCard("离线记的东西都已经提交到服务器了。")
                } else {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.cardBg)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            state.healthPending.forEach {
                                QueueLine(
                                    if (it.record == null) "删除 ${it.date}" else "体重 ${it.date} · ${
                                        String.format(
                                            "%.1f", it.record?.weight
                                        )
                                    } kg"
                                )
                            }
                            state.billsPending.forEach {
                                val title = it.input?.title ?: it.serverId.ifBlank { "未命名" }
                                QueueLine(
                                    when (it.op) {
                                        noline.zhouzhou.data.repository.BillOp.Delete -> "删除 $title"
                                        else -> "${it.input?.date ?: ""} $title ${
                                            String.format(
                                                "%.2f", it.input?.amount ?: 0.0
                                            )
                                        }"
                                    }
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = viewModel::syncAll,
                                    enabled = !state.isSyncing,
                                    colors = ButtonDefaults.buttonColors(containerColor = t.accent)
                                ) {
                                    Text(if (state.isSyncing) "同步中…" else "立即同步")
                                }
                                OutlinedButton(onClick = { dropConfirm = true }) {
                                    Text("全部丢弃", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle("其他占用")
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.cardBg)) {
                    Column {
                        listOf(StorageRepository.KEY_APK, StorageRepository.KEY_DATA_OTHER).forEachIndexed { index, key ->
                            usage.entry(key)?.let {
                                if (index > 0) HorizontalDivider(color = t.hairline)
                                StorageRow(it, null)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "统计口径：安装包取 APK 本体，其余按 App 私有目录里的实际文件相加，不含服务器上的内容。",
                style = MaterialTheme.typography.bodySmall,
                color = t.textLight,
                modifier = Modifier.padding(bottom = 28.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val t = LocalNoLine.current
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = t.textSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
    )
}

/** 总量 + 一条按占比切分的色带。 */
@Composable
private fun SummaryCard(total: Long, free: Long, entries: List<StorageEntry>) {
    val t = LocalNoLine.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = t.cardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("App 占用", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            Text(
                formatBytes(total),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(t.surfaceSoft)
            ) {
                val groups = listOf(
                    "安装包" to entries.filter { it.key == StorageRepository.KEY_APK }.sumOf { it.bytes },
                    "缓存" to entries.filter { it.clearable }.sumOf { it.bytes },
                    "数据" to entries.filter { !it.clearable && it.key != StorageRepository.KEY_APK }.sumOf { it.bytes }
                )
                val sum = groups.sumOf { it.second }.coerceAtLeast(1L)
                val palette = listOf(t.accent, t.headingMarker, t.healthNormal)
                groups.forEachIndexed { index, (_, bytes) ->
                    val weight = (bytes.toDouble() / sum * 100).toFloat()
                    if (weight >= 1f) {
                        Box(
                            Modifier
                                .weight(weight)
                                .fillMaxHeight()
                                .background(palette[index % palette.size])
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val palette = listOf(t.accent, t.headingMarker, t.healthNormal)
                listOf("安装包", "缓存", "数据").forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette[index])
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall, color = t.textLight)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "设备剩余 ${formatBytes(free)}",
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary
            )
        }
    }
}

@Composable
private fun StorageRow(entry: StorageEntry, onClear: (() -> Unit)?) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = t.textMain)
        }
        Text(formatBytes(entry.bytes), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        if (onClear != null && entry.bytes > 0L) {
            Spacer(Modifier.width(10.dp))
            Text(
                "清理",
                style = MaterialTheme.typography.labelLarge,
                color = t.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DraftRow(draft: DraftEntry, onDelete: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(draft.title)
                    if (draft.isPage) append(" · 页面")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = t.textMain,
                maxLines = 1
            )
            if (draft.excerpt.isNotBlank()) {
                Text(
                    draft.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textLight,
                    maxLines = 1
                )
            }
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.updatedAt))}" +
                        " · ${formatBytes(draft.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = t.textLight
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "删除草稿", tint = t.textSecondary)
        }
    }
}

@Composable
private fun QueueLine(text: String) {
    val t = LocalNoLine.current
    Text("· $text", style = MaterialTheme.typography.bodySmall, color = t.textMain, maxLines = 1)
}

@Composable
private fun EmptyCard(text: String) {
    val t = LocalNoLine.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = t.cardBg)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = t.textLight,
            modifier = Modifier.padding(14.dp)
        )
    }
}
