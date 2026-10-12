package noline.zhouzhou.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.ui.components.CommentItem
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

@Composable
fun CommentsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<CommentsViewModel>(appContainer) { CommentsViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current

    val tabs = listOf(
        null to "全部",
        "approve" to "已通过",
        "hold" to "待审核",
        "spam" to "垃圾"
    )

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
                        stringResource(R.string.admin_comments),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong
                    )
                }
                // 站点没有下划线式页签，筛选一律用分类药丸。
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEach { (status, label) ->
                        NoLineChip(label = label, selected = uiState.filterStatus == status,
                            onClick = { viewModel.loadComments(status) })
                    }
                }
            }
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = t.accent)
            }
            uiState.comments.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
                    modifier = Modifier.size(56.dp), tint = t.textLight)
                Spacer(Modifier.height(14.dp))
                Text("这一档还没有评论", fontSize = 13.sp, color = t.textSecondary)
            }
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.comments, key = { it.commentId }) { comment ->
                    Column(modifier = Modifier.fillMaxWidth().noLineCard().padding(horizontal = 14.dp)) {
                        CommentItem(
                            comment = comment,
                            showActions = true,
                            divider = false,
                            onReply = { },
                            onDelete = { viewModel.deleteComment(comment.commentId) },
                            onApprove = { viewModel.approveComment(comment.commentId) }
                        )
                    }
                }
            }
        }
    }
}
