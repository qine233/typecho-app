package com.typecho.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.typecho.app.AppContainer
import com.typecho.app.R
import com.typecho.app.ui.components.CommentItem
import com.typecho.app.ui.typechoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<CommentsViewModel>(appContainer) { CommentsViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()

    val tabs = listOf(
        null to "全部",
        "approve" to "已通过",
        "hold" to "待审核",
        "spam" to "垃圾"
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.admin_comments)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == uiState.filterStatus }) {
                    tabs.forEach { (status, label) ->
                        Tab(
                            selected = uiState.filterStatus == status,
                            onClick = { viewModel.loadComments(status) },
                            text = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.comments.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text("暂无评论", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(uiState.comments, key = { it.commentId }) { comment ->
                    CommentItem(
                        comment = comment,
                        showActions = true,
                        onReply = { },
                        onDelete = { viewModel.deleteComment(comment.commentId) },
                        onApprove = { viewModel.approveComment(comment.commentId) }
                    )
                }
            }
        }
    }
}
