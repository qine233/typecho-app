package noline.zhouzhou.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.typechoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    appContainer: AppContainer,
    onPostClick: (String) -> Unit,
    onNewPost: () -> Unit,
    onEditPost: (String) -> Unit,
    onViewComments: () -> Unit
) {
    val viewModel = typechoViewModel<AdminViewModel>(appContainer) { AdminViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    var postToDelete by remember { mutableStateOf<Post?>(null) }

    postToDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = { Text(stringResource(R.string.admin_delete_post)) },
            text = { Text(stringResource(R.string.admin_delete_confirm, post.displayTitle)) },
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

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AdminHeader(loading = uiState.isLoading, onRefresh = { viewModel.loadPosts() })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewPost,
                containerColor = LocalAccent.current,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_new_post)) }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ActionCard(
                        icon = Icons.AutoMirrored.Filled.Article,
                        label = stringResource(R.string.admin_posts),
                        count = uiState.posts.size,
                        onClick = {}
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
                        onClick = {}
                    )
                }
                item {
                    ActionCard(
                        icon = Icons.Default.FolderOpen,
                        label = stringResource(R.string.admin_categories),
                        onClick = {}
                    )
                }
            }

            Text(
                "最近文章",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp)
            )

            when {
                uiState.isLoading && uiState.posts.isEmpty() -> {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        repeat(5) { RowSkeleton() }
                    }
                }
                uiState.error != null && uiState.posts.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(uiState.error ?: "读不到文章", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "重新加载",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(LocalAccent.current)
                                .clickable { viewModel.loadPosts() }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.posts, key = { it.postId }) { post ->
                        AdminPostItem(
                            post = post,
                            onClick = { onPostClick(post.postId) },
                            onEdit = { onEditPost(post.postId) },
                            onDelete = { postToDelete = post }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminHeader(loading: Boolean, onRefresh: () -> Unit) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Spacer(Modifier.statusBarsPadding().height(0.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.admin_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "刷新",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        // 计数那行永远占位，否则「文章管理」比别人高出一截，一排卡片参差不齐。
        Text(
            count?.let { "$it 篇" } ?: " ",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RowSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(13.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth(0.3f)
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    post.displayTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        post.displayDate,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    post.categories.firstOrNull()?.let { cat ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            cat,
                            fontSize = 11.5.sp,
                            color = LocalAccent.current,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(LocalAccent.current.copy(alpha = 0.1f))
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                        )
                    }
                    StatusLabel(post.status)
                }
            }

            RowIconButton(Icons.Default.Edit, "编辑", onEdit, MaterialTheme.colorScheme.onSurfaceVariant)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                lineHeight = 18.sp
            )
        }
    }
}

/** 状态只认 Typecho 那几种，其余原样显示，别把未知状态说成「草稿」。 */
@Composable
private fun StatusLabel(status: String) {
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
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

@Composable
private fun RowIconButton(
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
