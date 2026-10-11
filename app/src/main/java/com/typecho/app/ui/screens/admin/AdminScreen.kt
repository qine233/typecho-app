package com.typecho.app.ui.screens.admin

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typecho.app.AppContainer
import com.typecho.app.R
import com.typecho.app.data.model.Post
import com.typecho.app.ui.typechoViewModel

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewPost,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_new_post)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Quick action cards
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ActionCard(
                        icon = Icons.AutoMirrored.Filled.Article,
                        label = stringResource(R.string.admin_posts),
                        count = uiState.posts.size.toString(),
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

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Post list
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: String? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(100.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium, maxLines = 1)
            count?.let {
                Text(it, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AdminPostItem(
    post: Post,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(post.displayTitle, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.displayDate, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (post.status != "publish") {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(post.status, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑",
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
