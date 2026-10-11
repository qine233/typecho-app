package com.typecho.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.*
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import com.typecho.app.data.model.Post

// 封面高度比例池：让瀑布流两列自然错落，模拟小红书的不规则网格
private val COVER_RATIOS = floatArrayOf(0.70f, 0.78f, 0.88f, 1.00f, 1.14f)

// 无图文章的「文字封面」底色，按 postId 稳定取色，同一篇文章永远同一个色
private val COVER_PALETTES = listOf(
    listOf(Color(0xFFFFE3D3), Color(0xFFFFD0B6)),
    listOf(Color(0xFFFDF1D7), Color(0xFFF8E1B2)),
    listOf(Color(0xFFE9F1DE), Color(0xFFD3E4C4)),
    listOf(Color(0xFFF7E3E7), Color(0xFFEECBD4)),
    listOf(Color(0xFFE5EDF6), Color(0xFFD2DFEE)),
    listOf(Color(0xFFF0E7F7), Color(0xFFE0D2EF))
)
private val CoverInk = Color(0xFF5B4436)

private fun Int.wrap(size: Int): Int = ((this % size) + size) % size

/**
 * 封面图。外链图片常常挂在早已失效的图床上，请求既不成功也不报错，
 * 所以失败和超时都按「没有封面」处理，让卡片退回文字封面，不留一块空白坑。
 */
@Composable
private fun CoverImage(
    url: String,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    clipShape: Shape = RoundedCornerShape(0.dp)
) {
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

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
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
 * 小红书式文字封面：把标题排进一块暖色渐变里，让没有配图的文章
 * 在瀑布流里也有「图」，而不是一片空白卡片。
 */
@Composable
private fun TextCover(
    title: String,
    seed: Int,
    modifier: Modifier = Modifier
) {
    val colors = COVER_PALETTES[seed.wrap(COVER_PALETTES.size)]
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = CoverInk,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
        )
    }
}

/** 小红书风瀑布流卡片：有图走大图封面，无图则为纯文字卡。 */
@Composable
fun WaterfallPostCard(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val seed = post.postId.hashCode()
    val ratio = COVER_RATIOS[seed.wrap(COVER_RATIOS.size)]
    val topShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    var coverGone by remember(post.postId) { mutableStateOf(false) }
    val cover = post.firstImage?.takeIf { !coverGone }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 无图但有真标题时改用文字封面；标题已经排在封面上，正文就只留一段说明。
            val textCover = cover == null && post.hasMeaningfulTitle

            if (cover != null) {
                CoverImage(
                    url = cover,
                    onUnavailable = { coverGone = true },
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                    clipShape = topShape
                )
            } else if (textCover) {
                TextCover(
                    title = post.displayTitle,
                    seed = seed,
                    modifier = Modifier.heightIn(min = 112.dp).clip(topShape)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            ) {
                val primaryText = when {
                    textCover -> post.plainExcerpt.ifBlank { post.displayDate }
                    cover != null -> if (post.hasMeaningfulTitle) post.displayTitle else post.displayDate
                    else -> post.plainExcerpt.ifBlank { post.displayDate }
                }
                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (textCover) FontWeight.Normal else FontWeight.Medium,
                    maxLines = if (textCover || post.hasMeaningfulTitle || cover != null) 2 else 4,
                    overflow = TextOverflow.Ellipsis,
                    color = if (textCover) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeedAvatar(name = post.author.ifBlank { post.displayTitle }, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = post.author.ifBlank { "团子" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "${post.commentCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 小红书风通栏笔记卡（单列）：有图走大图封面，无图则为纯文字卡。 */
@Composable
fun NotePostCard(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    var coverGone by remember(post.postId) { mutableStateOf(false) }
    val cover = post.firstImage?.takeIf { !coverGone }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            val textCover = cover == null && post.hasMeaningfulTitle

            if (cover != null) {
                CoverImage(
                    url = cover,
                    onUnavailable = { coverGone = true },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    clipShape = topShape
                )
            } else if (textCover) {
                TextCover(
                    title = post.displayTitle,
                    seed = post.postId.hashCode(),
                    modifier = Modifier.heightIn(min = 128.dp).clip(topShape)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                if (post.hasMeaningfulTitle && !textCover) {
                    Text(
                        text = post.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (post.plainExcerpt.isNotBlank()) {
                    Text(
                        text = post.plainExcerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (cover != null) 3 else 6,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeedAvatar(name = post.author.ifBlank { post.displayTitle }, size = 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = post.author.ifBlank { "团子" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "${post.commentCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
