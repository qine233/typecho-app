package com.typecho.app.ui.screens.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typecho.app.AppContainer
import com.typecho.app.R
import com.typecho.app.data.model.Comment
import com.typecho.app.data.model.Post
import com.typecho.app.ui.components.CommentThreadItem
import com.typecho.app.ui.components.CommentInputSheet
import com.typecho.app.ui.components.FeedAvatar
import com.typecho.app.ui.components.FullscreenImageViewer
import com.typecho.app.ui.components.NoteImagePager
import com.typecho.app.ui.components.NoteInteractionBar
import com.typecho.app.ui.theme.LocalAccent
import com.typecho.app.ui.typechoViewModel
import com.typecho.app.util.HtmlContent

@Composable
fun PostDetailScreen(
    appContainer: AppContainer,
    postId: String,
    onBack: () -> Unit,
    onEditPost: () -> Unit,
    onLoginRequired: () -> Unit,
    onCategoryClick: (String) -> Unit
) {
    val viewModel = typechoViewModel<PostDetailViewModel>(appContainer) {
        PostDetailViewModel(it, postId)
    }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val accent = LocalAccent.current

    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uiState.commentSuccess) {
        if (uiState.commentSuccess) {
            android.widget.Toast.makeText(context, R.string.comment_success, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearCommentSuccess()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NoteTopBar(
                post = uiState.post,
                isLoggedIn = uiState.isLoggedIn,
                onBack = onBack,
                onEdit = onEditPost,
                onShare = { post -> shareText(context, post) },
                onOpenBrowser = { post -> post.link.takeIf { it.isNotBlank() }?.let { uriHandler.openUri(it) } }
            )
        },
        bottomBar = {
            uiState.post?.let { post ->
                NoteInteractionBar(
                    commentCount = uiState.approvedCommentCount.takeIf { it > 0 } ?: post.commentCount,
                    liked = uiState.liked,
                    collected = uiState.collected,
                    onLike = viewModel::toggleLike,
                    onCollect = viewModel::toggleCollect,
                    onOpenCommentInput = {
                        if (uiState.isLoggedIn) viewModel.openCommentInput() else onLoginRequired()
                    },
                    placeholder = if (uiState.isLoggedIn) "说点什么…" else "登录后评论",
                    modifier = Modifier
                )
            }
        }
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.post == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = accent) }
            }

            uiState.error != null && uiState.post == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null,
                        modifier = Modifier.size(52.dp), tint = accent)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        uiState.error ?: "加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            uiState.post != null -> {
                val post = uiState.post!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                ) {
                    val images = post.feedImages
                    if (images.isNotEmpty()) {
                        NoteImagePager(
                            images = images,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp),
                            onImageClick = { viewerIndex = it }
                        )
                    }

                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        // 作者已经在顶部栏出现，正文区只留标题和日期，避免重复。
                        if (post.hasMeaningfulTitle) {
                            Text(
                                text = post.displayTitle,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (post.displayDate.isNotBlank()) {
                            Text(
                                text = post.displayDate,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                        } else {
                            Spacer(Modifier.height(4.dp))
                        }
                        HtmlContent(
                            html = post.content,
                            darkTheme = isDark,
                            baseUrl = post.link.ifBlank { null },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (post.categories.isNotEmpty() || post.tags.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            TopicRow(
                                categories = post.categories,
                                tags = post.tags,
                                accent = accent,
                                onCategoryClick = onCategoryClick
                            )
                        }

                        Spacer(Modifier.height(22.dp))
                    }

                    // 评论区与正文之间留一段页面底色，和小红书一致
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.background)
                    )

                    CommentsSection(
                        uiState = uiState,
                        onReply = { comment ->
                            if (uiState.isLoggedIn) viewModel.openCommentInput(comment) else onLoginRequired()
                        }
                    )

                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    viewerIndex?.let { start ->
        uiState.post?.let { post ->
            FullscreenImageViewer(
                images = post.feedImages,
                startIndex = start,
                onDismiss = { viewerIndex = null }
            )
        }
    }

    if (uiState.showCommentInput) {
        CommentInputSheet(
            value = uiState.commentText,
            onValueChange = viewModel::onCommentTextChange,
            onSubmit = viewModel::submitComment,
            submitting = uiState.isSubmittingComment,
            replyToName = uiState.replyTo?.author,
            error = uiState.commentError,
            onDismiss = viewModel::closeCommentInput
        )
    }
}

@Composable
private fun NoteTopBar(
    post: Post?,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onShare: (Post) -> Unit,
    onOpenBrowser: (Post) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            post?.let { note ->
                Spacer(Modifier.width(4.dp))
                FeedAvatar(name = note.author.ifBlank { note.displayTitle }, size = 28.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = note.author.ifBlank { "团子" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onShare(note) }) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share),
                        modifier = Modifier.size(21.dp))
                }
                if (isLoggedIn) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit),
                            modifier = Modifier.size(21.dp))
                    }
                }
                IconButton(onClick = { onOpenBrowser(note) }) {
                    Icon(Icons.Default.OpenInBrowser,
                        contentDescription = stringResource(R.string.action_open_browser),
                        modifier = Modifier.size(21.dp))
                }
            } ?: Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        )
    }
}

@Composable
private fun TopicRow(
    categories: List<String>,
    tags: List<String>,
    accent: androidx.compose.ui.graphics.Color,
    onCategoryClick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        categories.forEach { cat ->
            Text(
                text = "#$cat",
                fontSize = 13.sp,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCategoryClick(cat) }
            )
        }
        tags.forEach { tag ->
            Text(text = "#$tag", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CommentsSection(
    uiState: PostDetailUiState,
    onReply: (Comment) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (uiState.threads.isNotEmpty()) {
            Text(
                text = "共 ${uiState.approvedCommentCount} 条评论",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            uiState.threads.forEach { thread ->
                CommentThreadItem(thread = thread, onReply = onReply)
            }
        } else if (uiState.isLoadingComments) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = LocalAccent.current
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.comments_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun shareText(context: android.content.Context, post: Post) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, post.displayTitle)
        putExtra(
            android.content.Intent.EXTRA_TEXT,
            listOf(post.displayTitle, post.link).filter { it.isNotBlank() }.joinToString("\n")
        )
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, post.displayTitle.ifBlank { "分享文章" })
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
