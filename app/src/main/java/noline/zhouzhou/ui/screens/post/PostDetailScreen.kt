package noline.zhouzhou.ui.screens.post

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.ceil
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.model.Comment
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.ui.components.CommentThreadItem
import noline.zhouzhou.ui.components.CommentInputSheet
import noline.zhouzhou.ui.components.FeedAvatar
import noline.zhouzhou.ui.components.FullscreenImageViewer
import noline.zhouzhou.ui.components.NoteImagePager
import noline.zhouzhou.ui.components.NoteInteractionBar
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel
import noline.zhouzhou.util.HtmlContent

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
    val t = LocalNoLine.current
    val accent = LocalAccent.current

    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uiState.commentSuccess) {
        if (uiState.commentSuccess) {
            android.widget.Toast.makeText(context, R.string.comment_success, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearCommentSuccess()
        }
    }

    Scaffold(
        // 首屏要通栏压到状态栏底下，所以内边距交给下面自己管。
        containerColor = t.pageBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
        val scroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when {
                uiState.isLoading && uiState.post == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = accent) }
                }

                uiState.error != null && uiState.post == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
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
                            .verticalScroll(scroll)
                    ) {
                        PostHero(
                            post = post,
                            commentCount = uiState.approvedCommentCount.takeIf { it > 0 } ?: post.commentCount,
                            onCategoryClick = onCategoryClick
                        )

                        Spacer(Modifier.height(12.dp))

                        // 正文卡：站点的 .article-card，白面 1px 描边，图片贴着卡顶。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .noLineCard(RoundedCornerShape(12.dp))
                        ) {
                            val images = post.feedImages
                            if (images.isNotEmpty()) {
                                NoteImagePager(
                                    images = images,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp),
                                    onImageClick = { viewerIndex = it }
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 18.dp)
                            ) {
                                HtmlContent(
                                    html = post.content,
                                    baseUrl = post.link.ifBlank { null },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (post.tags.isNotEmpty()) {
                                    Spacer(Modifier.height(14.dp))
                                    TagRow(tags = post.tags)
                                }

                                CopyrightCard(post = post)
                            }
                        }

                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(12.dp)
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

            DetailTopBar(
                post = uiState.post,
                isLoggedIn = uiState.isLoggedIn,
                scrolled = scroll.canScrollBackward,
                onCover = uiState.post?.firstImage != null,
                onBack = onBack,
                onEdit = onEditPost,
                onShare = { uiState.post?.let { shareText(context, it) } },
                onOpenBrowser = {
                    uiState.post?.link?.takeIf { it.isNotBlank() }?.let { uriHandler.openUri(it) }
                }
            )
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

/**
 * 站点文章页首屏：封面铺满后压一层深罩，标题、分类、元信息全浮在上面，
 * 底沿那道 16dp 圆角唇把首屏和下面的页底色缝起来。
 * 没有封面时换成强调色辉光 + 墨色文字，和主题的 .post-hero:not(.has-cover) 一致。
 */
@Composable
private fun PostHero(
    post: Post,
    commentCount: Int,
    onCategoryClick: (String) -> Unit
) {
    val t = LocalNoLine.current
    val cover = post.firstImage
    val onCover = cover != null
    val ink = if (onCover) t.onCover else t.textStrong

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 有封面时首屏按站点横幅高度撑开；没封面时让它随内容长，
            // 定高会把两三行的标题连同一整排元信息一起裁掉。
            .then(if (cover != null) Modifier.height(292.dp) else Modifier.heightIn(min = 196.dp))
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(t.heroVeilTop, t.heroVeilBottom.copy(alpha = t.heroVeilBottom.alpha * 0.75f))
                        )
                    )
            )
        } else {
            // .post-hero:not(.has-cover)：两层强调色辉光顶在上方，比纯底色有层次
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val w = with(density) { maxWidth.toPx() }
                val h = with(density) { maxHeight.toPx() }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(t.pageBg)
                        .background(
                            Brush.radialGradient(
                                listOf(t.accentBg, Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(w * 0.12f, -h * 0.1f),
                                radius = w * 0.95f
                            )
                        )
                        .background(
                            Brush.radialGradient(
                                listOf(t.accentContainer, Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(w * 0.92f, h * 0.06f),
                                radius = w * 0.8f
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                // 有封面时标题压在照片下沿；没封面时从顶部栏下面起排，别和返回键挤在一起
                .align(if (onCover) Alignment.BottomStart else Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = if (onCover) 0.dp else 50.dp,
                    bottom = 30.dp
                )
        ) {
            if (post.categories.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    post.categories.take(2).forEach { cat ->
                        Text(
                            text = cat,
                            fontSize = 13.sp,
                            color = if (onCover) ink else t.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onCategoryClick(cat) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                // 标题是「未命名文档」这类占位符时，首屏拿正文首句顶上，和卡片同一套规则
                text = if (post.hasMeaningfulTitle) post.displayTitle
                    else post.plainExcerpt.ifBlank { "未命名" },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 33.sp,
                color = ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (post.displayDate.isNotBlank()) {
                    HeroMetaPill(post.displayDate, Icons.Default.Schedule, ink, onCover)
                }
                HeroMetaPill("$commentCount 评论", Icons.Default.ChatBubbleOutline, ink, onCover, plain = true)
                HeroMetaPill(readTimeLabel(post.content), Icons.Default.MenuBook, ink, onCover, plain = true)
            }
        }

        // .hero-lip：一道 16dp 的圆角唇，向上投一层强调色的光
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), spotColor = t.accentBg)
                .height(16.dp)
                .background(
                    t.pageBg,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
        )
    }
}

/** 首屏元信息胶囊：只有「入口」那几颗给底，日期是信息不是入口。 */
@Composable
private fun HeroMetaPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ink: Color,
    onCover: Boolean,
    plain: Boolean = false
) {
    val t = LocalNoLine.current
    val bg = when {
        onCover -> ink.copy(alpha = 0.16f)
        plain -> Color.Transparent
        else -> t.surfaceSoft
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = ink)
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 13.sp, color = ink, maxLines = 1)
    }
}

/** 正文卡底部的版权块：头像骑在上沿，中间作者名 + 一句话，底下反色徽章。 */
@Composable
private fun CopyrightCard(post: Post) {
    val t = LocalNoLine.current
    Box(modifier = Modifier.fillMaxWidth().padding(top = 36.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(t.surfaceSoft)
                .border(1.dp, t.cardBorder, RoundedCornerShape(12.dp))
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = post.author.ifBlank { "团子" },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "种一棵树，等它长大",
                fontSize = 15.sp,
                lineHeight = 27.sp,
                color = t.textLight
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "转载请注明出处",
                fontSize = 11.sp,
                color = t.cardBg,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(t.textStrong)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 站点让头像骑在版权块上沿：一半悬在外面，一半压住块面
                .offset(y = (-33).dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(t.cardBg),
            contentAlignment = Alignment.Center
        ) {
            FeedAvatar(name = post.author.ifBlank { post.displayTitle }, size = 66.dp)
        }
    }
}

@Composable
private fun TagRow(tags: List<String>) {
    val t = LocalNoLine.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            Text(
                text = "#$tag",
                fontSize = 13.sp,
                color = t.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(t.accentBg)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

/** 站点按正文字数给阅读时长，中文约每分钟 400 字。 */
private fun readTimeLabel(html: String): String {
    val chars = html.replace(Regex("<[^>]*>"), "").count { !it.isWhitespace() }
    return "约 ${ceil(chars / 400f).toInt().coerceAtLeast(1)} 分钟"
}

@Composable
private fun DetailTopBar(
    post: Post?,
    isLoggedIn: Boolean,
    scrolled: Boolean,
    onCover: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val t = LocalNoLine.current
    val ink by animateColorAsState(
        targetValue = when {
            scrolled || !onCover -> t.textStrong
            else -> t.onCover
        },
        label = "barInk"
    )
    val barBg by animateColorAsState(
        targetValue = if (scrolled) t.glassBg else Color.Transparent,
        label = "barBg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = ink)
            }
            if (post != null && scrolled) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = post.author.ifBlank { "团子" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (post != null) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share),
                        modifier = Modifier.size(21.dp), tint = ink)
                }
                if (isLoggedIn) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit),
                            modifier = Modifier.size(21.dp), tint = ink)
                    }
                }
                IconButton(onClick = onOpenBrowser) {
                    Icon(Icons.Default.OpenInBrowser,
                        contentDescription = stringResource(R.string.action_open_browser),
                        modifier = Modifier.size(21.dp), tint = ink)
                }
            }
        }
        if (scrolled) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(t.hairline))
        }
    }
}

@Composable
private fun CommentsSection(
    uiState: PostDetailUiState,
    onReply: (Comment) -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (uiState.threads.isNotEmpty()) {
            Text(
                text = "共 ${uiState.approvedCommentCount} 条评论",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            uiState.threads.forEach { thread ->
                CommentThreadItem(thread = thread, onReply = onReply)
            }
        } else if (uiState.isLoadingComments) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = t.accent
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = t.textLight
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.comments_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textLight
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
