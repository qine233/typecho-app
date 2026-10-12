package noline.zhouzhou.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

/**
 * 页面（Typecho 的独立页面，不是文章）管理。
 * 详情接口只认文章 cid，所以这里不进详情页，点卡片直接进编辑器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onNewPage: () -> Unit,
    onEditPage: (String) -> Unit
) {
    val viewModel = typechoViewModel<PagesViewModel>(appContainer) { PagesViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pageToDelete by remember { mutableStateOf<Post?>(null) }

    pageToDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = { Text(stringResource(R.string.admin_delete_page)) },
            text = { Text(stringResource(R.string.admin_delete_confirm, page.adminTitle())) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePage(page.postId)
                    pageToDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pageToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
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
                        stringResource(R.string.admin_pages),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong
                    )
                    Spacer(Modifier.weight(1f))
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp).size(17.dp),
                            strokeWidth = 2.dp,
                            color = t.accent
                        )
                    } else {
                        IconButton(onClick = { viewModel.load() }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "刷新",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewPage,
                containerColor = t.accent,
                contentColor = t.onBrand,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_new_page)) }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // 删除失败、拉取失败都只压一条横条，别把已有列表换成错误页。
            uiState.error?.let { message ->
                ErrorStrip(message = message, onDismiss = viewModel::clearError)
            }

            when {
                uiState.isLoading && uiState.pages.isEmpty() -> {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        repeat(4) { PageSkeleton() }
                    }
                }
                uiState.pages.isEmpty() && !uiState.isLoading -> {
                    EmptyPages(onNewPage)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.pages, key = { it.postId }) { page ->
                        PageCard(
                            page = page,
                            onClick = { onEditPage(page.postId) },
                            onDelete = { pageToDelete = page }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCard(page: Post, onClick: () -> Unit, onDelete: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                page.adminTitle(),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            RowIconButton(Icons.Default.Delete, "删除", onDelete,
                MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
        }
        val hasMeta = page.slug.isNotBlank() || page.status != "publish" || page.dateCreated != 0L
        if (hasMeta) {
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page.slug.isNotBlank()) {
                    Text(
                        page.slug,
                        fontSize = 11.5.sp,
                        color = t.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(t.accentBg)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
                StatusLabel(page.status)
                if (page.dateCreated != 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        page.displayDate,
                        fontSize = 11.5.sp,
                        color = t.textLight
                    )
                }
            }
        }
        val excerpt = page.plainExcerpt
        if (excerpt.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                excerpt,
                fontSize = 12.5.sp,
                color = t.textSecondary,
                maxLines = 2,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.Close, contentDescription = "关闭",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun PageSkeleton() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .noLineCard()
            .padding(16.dp)
    ) {
        Box(
            Modifier.fillMaxWidth(0.5f).height(13.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(t.coverFallback)
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier.fillMaxWidth(0.25f).height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(t.hairline)
        )
    }
}

@Composable
private fun EmptyPages(onNewPage: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("还没有独立页面", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "页面不进时间线，适合放「关于」「友情链接」这类常驻内容。",
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = t.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.admin_new_page),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = t.onBrand,
            modifier = Modifier
                .clip(CircleShape)
                .background(t.accent)
                .clickable(onClick = onNewPage)
                .padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}
