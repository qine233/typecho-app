package com.typecho.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.typecho.app.ui.theme.LocalAccent
import com.typecho.app.ui.theme.LocalAccentOn

/** 笔记详情的配图轮播；点击图片进入全屏查看。 */
@Composable
fun NoteImagePager(
    images: List<String>,
    modifier: Modifier = Modifier,
    onImageClick: (Int) -> Unit = {}
) {
    if (images.isEmpty()) return
    val state = rememberPagerState(pageCount = { images.size })
    val pageBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Box(modifier = modifier.background(pageBackground)) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onImageClick(page) },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = images[page],
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (images.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "${state.currentPage + 1}/${images.size}",
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
        }
    }
}

/** 黑底全屏查看器：左右翻页，点任意处关闭。 */
@Composable
fun FullscreenImageViewer(
    images: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val state = rememberPagerState(initialPage = startIndex, pageCount = { images.size })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF0000000))
        ) {
            HorizontalPager(
                state = state,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = images[page],
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(0.96f)
                    )
                }
            }
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(14.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
            )
            if (images.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 26.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 11.dp, vertical = 4.dp)
                ) {
                    Text("${state.currentPage + 1} / ${images.size}", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/** 小红书笔记页底部的固定互动栏：评论入口 + 点赞 + 收藏。 */
@Composable
fun NoteInteractionBar(
    commentCount: Int,
    liked: Boolean,
    collected: Boolean,
    onLike: () -> Unit,
    onCollect: () -> Unit,
    onOpenCommentInput: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDividerLine()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onOpenCommentInput)
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = placeholder,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            BounceAction(
                icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = if (liked) "已赞" else "赞",
                active = liked,
                onClick = onLike
            )
            Spacer(Modifier.width(16.dp))
            BounceAction(
                icon = if (collected) Icons.Default.Star else Icons.Outlined.StarBorder,
                label = if (collected) "已收藏" else "收藏",
                active = collected,
                onClick = onCollect
            )
            Spacer(Modifier.width(16.dp))
            BounceAction(
                icon = Icons.Default.ChatBubbleOutline,
                label = if (commentCount > 0) "$commentCount" else "评论",
                active = false,
                onClick = onOpenCommentInput
            )
        }
    }
}

@Composable
private fun BounceAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val accent = LocalAccent.current
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            pop.snapTo(0.7f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) accent else idle,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = if (active) accent else idle,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun HorizontalDividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
    )
}

/** 评论输入弹层：替代正文里那块常驻输入框。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentInputSheet(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    submitting: Boolean,
    replyToName: String?,
    error: String?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            replyToName?.let {
                Text(
                    text = "回复 @$it",
                    fontSize = 12.sp,
                    color = LocalAccent.current,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(LocalAccent.current),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(12.dp)
                    .heightIn(min = 72.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                "写下你的评论…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val accent = LocalAccent.current
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (value.isBlank() || submitting) accent.copy(alpha = 0.35f) else accent)
                        .clickable(enabled = value.isNotBlank() && !submitting, onClick = onSubmit)
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalAccentOn.current
                        )
                    } else {
                        Text("发表", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LocalAccentOn.current)
                    }
                }
            }
        }
    }
}
