package noline.zhouzhou.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.repository.DuplicateGroup
import noline.zhouzhou.data.repository.DuplicateScan
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

/**
 * 重复文章清理：把整站文章翻一遍，正文一模一样的列成一组，
 * 每组留最早的那篇、多出来的默认勾上，一次删干净。
 *
 * 同名但内容不同的另开一栏，只给看不给勾——那多半是自己写的续篇。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onPostClick: (String) -> Unit
) {
    val viewModel = typechoViewModel<DuplicatesViewModel>(appContainer) { DuplicatesViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var confirmOpen by remember { mutableStateOf(false) }

    val scan = uiState.scan
    val selectedCount = uiState.selected.size

    if (confirmOpen && scan != null) {
        val titles = scan.deletable
            .filter { it.postId in uiState.selected }
            .take(4)
            .map { it.displayTitle }
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("删掉这 $selectedCount 篇？") },
            text = {
                Column {
                    Text(
                        "正文和保留的那篇一模一样，删掉不会丢内容。每篇最早的那一份都会留着。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    if (titles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            titles.joinToString("\n"),
                            fontSize = 12.sp,
                            color = t.textSecondary,
                            maxLines = 4
                        )
                    }
                    Text(
                        "删除会直接发到站点上，删掉的在 Typecho 回收站里还能捞回来。",
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = t.textLight,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    viewModel.deleteSelected()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("取消") }
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
                    Text("重复文章", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    if (!uiState.scanning) {
                        IconButton(onClick = viewModel::scan, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "重新扫描",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (scan != null && selectedCount > 0 && !uiState.scanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(t.cardBg)
                        .border(width = 1.dp, color = t.hairline)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已勾 $selectedCount 篇重复副本",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = t.textStrong
                        )
                        Text(
                            if (uiState.deletedCount > 0) "这次已经删掉 ${uiState.deletedCount} 篇"
                            else "每组最早的那篇会留着",
                            fontSize = 11.5.sp,
                            color = t.textLight
                        )
                    }
                    Text(
                        if (uiState.deleting) "删除中…" else "删除选中",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (uiState.deleting) t.textLight else t.onBrand,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (uiState.deleting) t.surfaceSunken else MaterialTheme.colorScheme.error)
                            .clickable(enabled = !uiState.deleting) { confirmOpen = true }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    )
                }
            } else {
                Spacer(Modifier.navigationBarsPadding().height(6.dp))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            uiState.error?.let { ErrorStrip(message = it, onDismiss = { }) }
            uiState.deleteError?.let {
                ErrorStrip(message = "有几点没删掉：$it", onDismiss = { })
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { ScanSummary(uiState.scanning, uiState.loaded, scan) }

                if (scan == null && !uiState.scanning && uiState.error != null) {
                    item {
                        Text(
                            "没取到文章列表，换个网络再点右上角重新扫描。",
                            fontSize = 12.5.sp,
                            color = t.textSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }

                if (scan != null) {
                    if (scan.isEmpty) {
                        item { CleanBillboard() }
                    }

                    if (scan.identical.isNotEmpty()) {
                        item {
                            SectionHead(
                                "正文完全相同 · ${scan.identical.size} 组，多出 ${scan.redundant} 篇"
                            )
                        }
                        scan.identical.forEach { group ->
                            item(key = "same-${group.kept.postId}") {
                                IdenticalGroupCard(
                                    group = group,
                                    selected = uiState.selected,
                                    deleting = uiState.deleting,
                                    onToggle = viewModel::toggleSelected,
                                    onOpen = onPostClick
                                )
                            }
                        }
                    }

                    if (scan.sameTitle.isNotEmpty()) {
                        item {
                            SectionHead("只是同名 · ${scan.sameTitle.size} 组")
                        }
                        item {
                            Text(
                                "标题一样、正文不同，多半是自己写的续篇或改版。这些只列出来给你对照，不勾也不删。",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = t.textSecondary
                            )
                        }
                        scan.sameTitle.forEach { group ->
                            item(key = "title-${group.kept.postId}") {
                                SameTitleGroupCard(group = group, onOpen = onPostClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanSummary(scanning: Boolean, loaded: Int, scan: DuplicateScan?) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    scanning -> "正在翻站点上的文章"
                    scan == null -> "还没扫"
                    else -> "翻了 ${scan.scanned} 篇"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            if (scanning) {
                Text("$loaded 篇", fontSize = 12.sp, color = t.accent)
            }
        }
        if (scanning) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = t.accent,
                trackColor = t.hairline
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "一页 20 篇地翻，文章多就稍等一下。",
                fontSize = 11.5.sp,
                color = t.textLight
            )
        } else if (scan != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("完全相同的 ${scan.identical.size} 组")
                    append(" · 同名 ${scan.sameTitle.size} 组")
                    if (scan.truncated) append(" · 只扫到了前 ${scan.scanned} 篇")
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = t.textSecondary
            )
        }
    }
}

@Composable
private fun SectionHead(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalNoLine.current.textSecondary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** 一组完全相同的文章：留着的那篇压在顶上，多出来的每行一个勾。 */
@Composable
private fun IdenticalGroupCard(
    group: DuplicateGroup,
    selected: Set<String>,
    deleting: Boolean,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.title.ifBlank { "无标题" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "×${group.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = t.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(t.accentBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "留",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = t.onBrand,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(t.accent)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "#${group.kept.postId} · ${group.kept.displayDate.ifBlank { "没时间" }}",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                "查看",
                fontSize = 12.sp,
                color = t.accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onOpen(group.kept.postId) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        if (group.kept.plainExcerpt.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                group.kept.plainExcerpt,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = t.textLight,
                maxLines = 2
            )
        }

        group.extras.forEach { extra ->
            val checked = extra.postId in selected
            Spacer(Modifier.height(9.dp))
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (checked) t.warnBg else Color.Transparent)
                    .clickable(enabled = !deleting) { onToggle(extra.postId) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle(extra.postId) },
                    enabled = !deleting,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.error,
                        checkmarkColor = MaterialTheme.colorScheme.onError
                    )
                )
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "删 · #${extra.postId} · ${extra.displayDate.ifBlank { "没时间" }}",
                        fontSize = 11.5.sp,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (checked) t.warnText else t.textSecondary
                    )
                    val excerpt = extra.plainExcerpt
                    if (excerpt.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            excerpt,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = t.textLight,
                            maxLines = 2
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "评论 ${extra.commentCount} 条 · ${statusLabel(extra.status)}",
                        fontSize = 11.sp,
                        color = t.textLight
                    )
                }
            }
        }
    }
}

/** 同名不同内容：只列出来对照，点进去自己看。 */
@Composable
private fun SameTitleGroupCard(group: DuplicateGroup, onOpen: (String) -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            group.title.ifBlank { "无标题" },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = t.textStrong,
            maxLines = 1
        )
        Spacer(Modifier.height(7.dp))
        // 「日记」这种一组能有十几篇，全列出来这页就没法看了；先给前 6 篇，其余收进一句话。
        val members = listOf(group.kept) + group.extras
        members.take(6).forEach { post ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(post.postId) }
                    .padding(vertical = 5.dp)
            ) {
                Text(
                    "#${post.postId}",
                    fontSize = 11.5.sp,
                    color = t.textSecondary,
                    modifier = Modifier.width(52.dp)
                )
                Text(
                    post.displayDate.ifBlank { "没时间" },
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    modifier = Modifier.width(96.dp)
                )
                Text(
                    post.plainExcerpt.ifBlank { "（没有正文）" },
                    fontSize = 12.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (members.size > 6) {
            Spacer(Modifier.height(4.dp))
            Text(
                "这一组还有 ${members.size - 6} 篇，点上面任意一篇从列表里接着翻。",
                fontSize = 11.5.sp,
                color = t.textLight
            )
        }
    }
}

/** 一篇文章都没重复——这页最好的结果，值得给句人话。 */
@Composable
private fun CleanBillboard() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(t.accentBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = t.accent
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("没有重复的文章", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.textStrong)
        Spacer(Modifier.height(5.dp))
        Text(
            "整站翻了一遍，正文没有撞车的。当年那批 cid 被吞掉时发重了的文章，要是已经清完了，这里就会一直干净着。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = t.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "publish" -> "已发布"
    "draft" -> "草稿"
    "pending" -> "待审"
    else -> status
}
