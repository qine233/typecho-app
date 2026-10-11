package com.typecho.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typecho.app.data.model.Category
import com.typecho.app.ui.theme.LocalAccent

/**
 * 小红书式搜索药丸：平时只是占位文字，点击原地变成输入框。
 * 不用 Material TextField 外壳，避免下划线与色块容器。
 */
@Composable
fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    var active by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Row(
        modifier = modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(start = 11.dp, end = if (active) 5.dp else 11.dp)
            .then(
                if (active) Modifier
                else Modifier.clickable {
                    active = true
                    keyboard?.show()
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        if (active) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(LocalAccent.current),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 7.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Text(
                    text = "清除",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onQueryChange("") }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Icon(
                Icons.Default.Close,
                contentDescription = "收起搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable {
                        active = false
                        onQueryChange("")
                        keyboard?.hide()
                    }
            )
        } else {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 小红书式分类筛选条：选中为实心药丸，未选中为白底细描边。 */
@Composable
fun CategoryFilterRow(
    categories: List<Category>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip("全部", selected == null) { onSelect(null) }
        categories.forEach { cat ->
            FilterChip(
                text = cat.name,
                selected = selected == cat.name,
                onClick = { onSelect(if (selected == cat.name) null else cat.name) }
            )
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = LocalAccent.current
    val background = if (selected) accent else MaterialTheme.colorScheme.surface
    val content = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .then(
                if (selected) Modifier
                else Modifier.border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content
        )
    }
}

/** 瀑布流骨架屏：两列错落占位 + 呼吸透明度，替代转圈。 */
@Composable
fun WaterfallSkeleton(
    modifier: Modifier = Modifier,
    columnCount: Int = 2
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val base = MaterialTheme.colorScheme.outlineVariant
    val columnHeights = listOf(
        listOf(150.dp, 96.dp, 190.dp, 110.dp),
        listOf(110.dp, 180.dp, 92.dp, 160.dp)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(columnCount) { column ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                columnHeights[column % columnHeights.size].forEach { coverHeight ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(10.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(coverHeight)
                                .clip(RoundedCornerShape(10.dp))
                                .background(base.copy(alpha = pulse))
                        )
                        Spacer(Modifier.height(10.dp))
                        SkeletonBar(base, pulse, 0.9f)
                        Spacer(Modifier.height(6.dp))
                        SkeletonBar(base, pulse, 0.55f)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(base: Color, alpha: Float, widthFraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(11.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(base.copy(alpha = alpha))
    )
}

/** 列表项进场：淡入 + 轻微上移，按位置错落。 */
@Composable
fun XhsEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val offsetPx = with(density) { 18.dp.toPx() }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(280, delayMillis = (index % 4) * 45, easing = LinearEasing))
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * offsetPx
        }
    ) { content() }
}

/** 触底加载状态条。 */
@Composable
fun LoadMoreFooter(loading: Boolean, canLoadMore: Boolean, loadedCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            !canLoadMore -> Text(
                "· 已经到底了 ·",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Text(
                "上滑加载更多（已加载 $loadedCount 篇）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
