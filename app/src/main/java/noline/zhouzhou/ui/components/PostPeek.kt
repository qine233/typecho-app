package noline.zhouzhou.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard

/** 预览卡的固定尺寸：定位要按它算，所以内容也必须被压进这个框里。 */
val PeekWidth = 248.dp
val PeekHeight = 320.dp

/**
 * 长按卡片就地弹一张预览，不用先跳进去看一眼再退回来。
 * 点预览 = 打开原文；点外面或返回键 = 收起来。
 */
@Composable
fun PostPeekBox(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (onClick: () -> Unit, onLongClick: () -> Unit) -> Unit
) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var peeking by remember(post.postId) { mutableStateOf(false) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val viewport = with(density) {
        IntSize(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx())
    }
    val peekSize = with(density) { IntSize(PeekWidth.roundToPx(), PeekHeight.roundToPx()) }
    val open = {
        peeking = false
        onClick()
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { anchor = it.boundsInWindow() }
    ) {
        content(open, { peeking = true })
        if (peeking) {
            Popup(
                alignment = Alignment.TopStart,
                offset = peekOffset(anchor, viewport, peekSize),
                onDismissRequest = { peeking = false },
                properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false)
            ) {
                PostPeekCard(post = post, onOpen = open)
            }
        }
    }
}

/**
 * 预览尽量压在卡片正中，但绝不越出可视区。
 * 卡片自己已经滚出屏幕时（anchor 在视口外），预览贴到对侧，别弹在看不见的地方。
 */
fun peekOffset(anchor: Rect, viewport: IntSize, peek: IntSize): IntOffset {
    val centeredX = (anchor.left + (anchor.width - peek.width) / 2f).toInt()
    val centeredY = (anchor.top + (anchor.height - peek.height) / 2f).toInt()
    val maxLeft = (viewport.width - peek.width).coerceAtLeast(0)
    val maxTop = (viewport.height - peek.height).coerceAtLeast(0)
    return IntOffset(
        x = centeredX.coerceIn(0, maxLeft),
        y = centeredY.coerceIn(0, maxTop)
    )
}

@Composable
private fun PostPeekCard(post: Post, onOpen: () -> Unit) {
    val t = LocalNoLine.current
    val shape = RoundedCornerShape(14.dp)
    val hue = coverHue(post.postId.hashCode())
    var coverGone by remember(post.postId) { mutableStateOf(false) }
    val cover = post.firstImage?.takeIf { !coverGone }
    val scale = remember { Animatable(0.9f) }

    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 900f)) }

    Column(
        modifier = Modifier
            .size(PeekWidth, PeekHeight)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .noLineCard(shape)
            .clip(shape)
            .clickable(onClick = onOpen)
    ) {
        // 没有配图就铺一块和列表同源的淡色。列表里那层淡底是压在标题文字下面的，
        // 预览这块是空板子，同样的 α 看着就是一格灰，所以这里要压得更实。
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .background(t.cardBg)
                .background(Brush.linearGradient(listOf(hue.copy(alpha = 0.20f), hue.copy(alpha = 0.42f))))
        ) {
            cover?.let { url ->
                CoverImage(
                    url = url,
                    onUnavailable = { coverGone = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
            post.categories.firstOrNull()?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (post.hasMeaningfulTitle) {
                Text(
                    text = post.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = t.textStrong
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = post.plainExcerpt.ifBlank { post.displayTitle },
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 19.sp,
                maxLines = if (post.hasMeaningfulTitle) 6 else 8,
                overflow = TextOverflow.Ellipsis,
                color = t.textMain
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.displayDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = t.textLight
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (post.commentCount > 0) "${post.commentCount} 条评论" else "点击查看全文",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.accent
                )
            }
        }
    }
}
