package noline.zhouzhou.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
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
import kotlinx.coroutines.launch
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.ui.components.PageTopBar
import noline.zhouzhou.ui.components.LoadMoreFooter
import noline.zhouzhou.ui.navigation.rememberTabBarInset
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    appContainer: AppContainer,
    onPostClick: (String) -> Unit,
    onNewPost: () -> Unit,
    onEditPost: (String) -> Unit,
    onViewComments: () -> Unit,
    onViewPages: () -> Unit,
    onViewCategories: () -> Unit,
    onViewDuplicates: () -> Unit,
    onViewDrive: () -> Unit
) {
    val viewModel = typechoViewModel<AdminViewModel>(appContainer) { AdminViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    val listState = rememberLazyListState()
    val tabBarInset = rememberTabBarInset()
    val scope = rememberCoroutineScope()

    // 快滑到底就自动取下一页
    // 卡片行/标题/空态也占 item，所以只在真有文章时才判定「滑到底」
    LaunchedEffect(listState, uiState.posts.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (uiState.posts.isNotEmpty() && lastVisible >= total - 1) viewModel.loadMore()
        }
    }

    postToDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = { Text(stringResource(R.string.admin_delete_post)) },
            text = { Text(stringResource(R.string.admin_delete_confirm, post.adminTitle())) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePost(post.postId) { postToDelete = null }
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    var barH by remember { mutableStateOf(0.dp) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = t.pageBg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.padding(bottom = tabBarInset),
                onClick = onNewPost,
                containerColor = t.accent,
                contentColor = t.onBrand,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_new_post)) }
            )
        }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = barH + 10.dp, bottom = 40.dp + tabBarInset),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            ActionCard(
                                icon = Icons.AutoMirrored.Filled.Article,
                                label = stringResource(R.string.admin_posts),
                                count = uiState.posts.size,
                                onClick = { scope.launch { listState.animateScrollToItem(0) } }
                            )
                        }
                        item {
                            ActionCard(
                                icon = Icons.Default.ChatBubble,
                                label = stringResource(R.string.admin_comments),
                                onClick = onViewComments
                            )
                        }
                        item {
                            ActionCard(
                                icon = Icons.Default.Description,
                                label = stringResource(R.string.admin_pages),
                                onClick = onViewPages
                            )
                        }
                        item {
                            ActionCard(
                                icon = Icons.Default.FolderOpen,
                                label = stringResource(R.string.admin_categories),
                                onClick = onViewCategories
                            )
                        }
                        item {
                            ActionCard(
                                icon = Icons.Default.ContentCopy,
                                label = stringResource(R.string.admin_duplicates),
                                onClick = onViewDuplicates
                            )
                        }
                        if (uiState.driveAvailable) {
                            item {
                                ActionCard(
                                    icon = Icons.Default.Cloud,
                                    label = stringResource(R.string.drive_title),
                                    onClick = onViewDrive
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        if (uiState.canLoadMore) "全部文章" else "全部文章 · 共 ${uiState.posts.size} 篇",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.textSecondary,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }

                when {
                    uiState.isLoading && uiState.posts.isEmpty() -> {
                        item {
                            Column { repeat(5) { RowSkeleton() } }
                        }
                    }
                    uiState.error != null && uiState.posts.isEmpty() -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 22.dp, bottom = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(uiState.error ?: "读不到文章", fontSize = 13.sp, color = t.textSecondary)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "重新加载",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = t.onBrand,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(t.accent)
                                        .clickable { viewModel.loadPosts() }
                                        .padding(horizontal = 16.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        items(uiState.posts, key = { it.postId }) { post ->
                            AdminPostItem(
                                post = post,
                                onClick = { onPostClick(post.postId) },
                                onEdit = { onEditPost(post.postId) },
                                onDelete = { postToDelete = post }
                            )
                        }
                        item(key = "footer") {
                            if (uiState.moreError != null) {
                                Text(
                                    "${uiState.moreError} · 点这里重试",
                                    fontSize = 12.5.sp,
                                    color = t.textLight,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(CircleShape)
                                        .clickable { viewModel.loadMore() }
                                        .padding(vertical = 14.dp),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                LoadMoreFooter(
                                    loading = uiState.isLoadingMore,
                                    canLoadMore = uiState.canLoadMore,
                                    loadedCount = uiState.posts.size
                                )
                            }
                        }
                    }
                }
            }

            PageTopBar(
                onBarHeight = { barH = it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                AdminHeader(loading = uiState.isLoading, onRefresh = { viewModel.loadPosts() })
            }
        }
    }
}

@Composable
private fun AdminHeader(loading: Boolean, onRefresh: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.admin_title),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = t.textStrong
        )
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = t.accent
            )
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = "刷新",
                    modifier = Modifier.size(19.dp),
                    tint = t.textSecondary
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int? = null,
    onClick: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .width(96.dp)
            .noLineCard()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(t.accentBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = t.textMain,
            maxLines = 1
        )
        // 计数那行永远占位，否则「文章管理」比别人高出一截，一排卡片参差不齐。
        Text(
            count?.let { "$it 篇" } ?: " ",
            fontSize = 11.5.sp,
            color = t.textLight
        )
    }
}

@Composable
private fun RowSkeleton() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .noLineCard()
            .padding(16.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(13.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(t.coverFallback)
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth(0.3f)
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(t.hairline)
        )
    }
}

@Composable
private fun AdminPostItem(
    post: Post,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    post.adminTitle(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textStrong,
                    maxLines = 1
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        post.displayDate,
                        fontSize = 11.5.sp,
                        color = t.textLight
                    )
                    post.categories.firstOrNull()?.let { cat ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            cat,
                            fontSize = 11.5.sp,
                            color = t.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(t.accentBg)
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                        )
                    }
                    StatusLabel(post.status)
                }
            }

            RowIconButton(Icons.Default.Edit, "编辑", onEdit, t.textSecondary)
            Spacer(Modifier.width(2.dp))
            RowIconButton(Icons.Default.Delete, "删除", onDelete,
                MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
        }
        // 正文摘要：列表里就能认出是哪一篇，不用点进去。
        val excerpt = post.plainExcerpt
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

/** 状态只认 Typecho 那几种，其余原样显示，别把未知状态说成「草稿」。 */
@Composable
fun StatusLabel(status: String) {
    val t = LocalNoLine.current
    val label = when (status) {
        "publish" -> return
        "draft" -> "草稿"
        "pending" -> "待审"
        "protected" -> "受保护"
        "private" -> "私密"
        else -> status
    }
    Spacer(Modifier.width(8.dp))
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = t.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(t.surfaceSunken)
            .padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

@Composable
fun RowIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    tint: Color
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(17.dp), tint = tint)
    }
}

/** 管理列表里空标题会塌成一条空白行，看不出是哪篇。 */
@Composable
fun Post.adminTitle(): String =
    displayTitle.ifBlank { stringResource(R.string.admin_untitled) }
