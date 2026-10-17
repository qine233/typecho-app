package noline.zhouzhou.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard

// 封面高度比例池：让双列两列自然错落，一屏里的卡片不会齐成一条线
private val COVER_RATIOS = floatArrayOf(0.70f, 0.78f, 0.88f, 1.00f, 1.14f)

/**
 * 无图文章的文字封面底色。站点自己不给这类卡片铺色，这里按它的令牌派生：
 * 主色是靛蓝和蓝灰（--accent / --cover-fallback 那一族），掺一格暖金
 * （--heading-marker），免得整屏全是冷调。α 压在卡面上，深浅色都自动跟着走。
 */
private val CoverHues = listOf(
    Color(0xFF425AEF),
    Color(0xFF5A7FA8),
    Color(0xFF3F8F79),
    Color(0xFFC89A3C),
    Color(0xFF7A5AA8),
    Color(0xFF8A93A8)
)

private fun Int.wrap(size: Int): Int = ((this % size) + size) % size

/** 长按预览的空封面要和列表里的文字封面取同一个色，别各搞一套。 */
internal fun coverHue(seed: Int): Color = CoverHues[seed.wrap(CoverHues.size)]

/**
 * 封面图。外链图片常常挂在早已失效的图床上，请求既不成功也不报错，
 * 所以失败和超时都按「没有封面」处理，让卡片退回文字封面，不留一块空白坑。
 * 搜索结果的缩略图也复用它，坏图同样不该留白。
 */
@Composable
internal fun CoverImage(
    url: String,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    clipShape: Shape = RoundedCornerShape(0.dp)
) {
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val t = LocalNoLine.current

    LaunchedEffect(state) {
        if (state is AsyncImagePainter.State.Error) onUnavailable()
    }
    LaunchedEffect(url) {
        delay(CoverTimeoutMs)
        if (state !is AsyncImagePainter.State.Success) onUnavailable()
    }

    Box(
        modifier = modifier
            .clip(clipShape)
            .background(t.coverFallback)
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { state = it },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private const val CoverTimeoutMs = 6000L

/**
 * 文字封面：把标题排进一块按文章 id 稳定取色的淡底里，让没有配图的文章
 * 在双列里也有「图」，而不是一片空白卡片。
 */
@Composable
private fun TextCover(
    title: String,
    seed: Int,
    date: String? = null,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val hue = CoverHues[seed.wrap(CoverHues.size)]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(t.cardBg)
            .background(Brush.linearGradient(listOf(hue.copy(alpha = 0.13f), hue.copy(alpha = 0.26f))))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = t.textStrong,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        if (date != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = t.textSecondary
            )
        }
    }
}

/** 网站 .feed-cat 里那颗计数：跟在名字后面，压一点浓度。 */
@Composable
private fun CatTag(name: String) {
    val t = LocalNoLine.current
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = t.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(t.accentBg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/**
 * 双列卡：站点的 post-card 语言——白面 + 1px 蓝灰描边 + 一层浅投影，
 * 封面贴着卡顶铺满，标题两行、摘要两行，底部一排 stat-item 胶囊。
 * 长按弹预览，点封面那块和详情页共享。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaterfallPostCard(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val seed = post.postId.hashCode()
    val ratio = COVER_RATIOS[seed.wrap(COVER_RATIOS.size)]
    val shape = RoundedCornerShape(12.dp)
    val topShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    var coverGone by remember(post.postId) { mutableStateOf(false) }
    val cover = post.firstImage?.takeIf { !coverGone }

    // 无图文章一律排一块文字封面；标题是占位符时拿正文首句上封面，
    // 免得卡片退化成一片白，在双列里像没加载出来。
    val coverText = when {
        cover != null -> null
        post.hasMeaningfulTitle -> post.displayTitle
        else -> post.plainExcerpt.ifBlank { post.displayTitle }
    }
    val textCover = coverText != null && coverText.isNotBlank()

    // 封面上已经是标题，卡里就让正文说话。日期只归底部那行元信息，
    // 再拿它给空正文兜底会在同一张卡上印两遍。
    val bodyText = when {
        textCover && post.hasMeaningfulTitle -> post.plainExcerpt
        textCover -> ""
        cover != null -> if (post.hasMeaningfulTitle) post.displayTitle else ""
        else -> post.plainExcerpt
    }
    // 只剩一个日期的元信息不值得挂一条白带子，把它并进封面；
    // 有正文、分类或评论才留底部那一排。
    val showBand = bodyText.isNotBlank() ||
        post.categories.isNotEmpty() ||
        post.commentCount > 0
    val coverDate = if (textCover && !showBand) post.displayDate.takeIf { it.isNotBlank() } else null
    val hasMetaRow = post.categories.isNotEmpty() ||
        post.commentCount > 0 ||
        (post.displayDate.isNotBlank() && coverDate == null)

    PostPeekBox(post = post, onClick = onClick, modifier = modifier) { open, peek ->
        Column(
            Modifier
                .fillMaxWidth()
                .noLineCard(shape)
                .combinedClickable(onClick = open, onLongClick = peek)
        ) {
            if (cover != null) {
                CoverImage(
                    url = cover,
                    onUnavailable = { coverGone = true },
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio).sharedPostCover(post.postId),
                    clipShape = topShape
                )
            } else if (textCover) {
                TextCover(
                    title = coverText,
                    seed = seed,
                    date = coverDate,
                    modifier = Modifier.heightIn(min = 112.dp).sharedPostCover(post.postId)
                )
            }

            if (showBand) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 11.dp)
            ) {
                if (bodyText.isNotBlank()) {
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (textCover) FontWeight.Normal else FontWeight.ExtraBold,
                        maxLines = if (textCover || post.hasMeaningfulTitle || cover != null) 2 else 4,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = MaterialTheme.typography.titleSmall.lineHeight,
                        color = if (textCover) t.textSecondary else t.textStrong
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (hasMetaRow) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    post.categories.firstOrNull()?.let { CatTag(it) }
                    if (post.commentCount > 0) {
                        StatPill("${post.commentCount}", Icons.Default.ChatBubbleOutline)
                    }
                    if (post.displayDate.isNotBlank()) {
                        Text(
                            text = post.displayDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = t.textLight
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单列通栏卡：站点的 status-card 版式——头像独占左列，右列自上而下是
 * 作者名 → 相对时间 → 正文 → 配图 → 操作条，正文左边缘和名字对齐。
 * 和双列卡一样支持长按预览，配图和详情页首屏共享。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotePostCard(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val shape = RoundedCornerShape(12.dp)
    var coverGone by remember(post.postId) { mutableStateOf(false) }
    val cover = post.firstImage?.takeIf { !coverGone }

    PostPeekBox(post = post, onClick = onClick, modifier = modifier) { open, peek ->
        Row(
            Modifier
                .fillMaxWidth()
                .noLineCard(shape)
                .combinedClickable(onClick = open, onLongClick = peek)
                .padding(horizontal = 16.dp, vertical = 15.dp)
        ) {
            FeedAvatar(name = post.author.ifBlank { post.displayTitle }, size = 42.dp)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = post.author.ifBlank { "团子" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = t.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = post.displayDate.ifBlank { " " },
                    style = MaterialTheme.typography.labelSmall,
                    color = t.textLight
                )
                Spacer(Modifier.height(7.dp))

                if (post.hasMeaningfulTitle) {
                    Text(
                        text = post.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = t.textStrong
                    )
                    Spacer(Modifier.height(5.dp))
                }
                if (post.plainExcerpt.isNotBlank()) {
                    Text(
                        text = post.plainExcerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 24.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        color = t.textMain
                    )
                }
                if (cover != null) {
                    Spacer(Modifier.height(7.dp))
                    CoverImage(
                        url = cover,
                        onUnavailable = { coverGone = true },
                        modifier = Modifier.fillMaxWidth().height(180.dp).sharedPostCover(post.postId),
                        clipShape = RoundedCornerShape(8.dp)
                    )
                }

                Spacer(Modifier.height(11.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
                Spacer(Modifier.height(7.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    post.categories.firstOrNull()?.let { CatTag(it) }
                    if (post.commentCount > 0) {
                        StatPill("${post.commentCount}", Icons.Default.ChatBubbleOutline)
                    }
                }
            }
        }
    }
}
