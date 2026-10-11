package noline.zhouzhou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 微博信息流风格的圆形首字母头像。 */
@Composable
fun FeedAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    fallback: String = "文"
) {
    val initial = name.trim().firstOrNull()?.toString() ?: fallback
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = when {
                size >= 40.dp -> MaterialTheme.typography.titleMedium
                size >= 28.dp -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.labelMedium
            },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 微博信息流风格的「转发 · 评论 · 赞」操作行。赞为本地微交互开关。 */
@Composable
fun FeedActionBar(
    commentCount: Int,
    modifier: Modifier = Modifier,
    repostCount: Int? = null,
    onRepost: (() -> Unit)? = null,
    onComment: (() -> Unit)? = null,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(40.dp)
) {
    var liked by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FeedAction(Icons.Default.Repeat, repostCount, "转发", active = false, onClick = onRepost)
        FeedAction(Icons.Default.ChatBubble, commentCount.takeIf { it > 0 }, "评论",
            active = false, onClick = onComment)
        FeedAction(
            icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            count = if (liked) 1 else null,
            label = "赞",
            active = liked,
            onClick = { liked = !liked }
        )
    }
}

@Composable
private fun FeedAction(
    icon: ImageVector,
    count: Int?,
    label: String,
    active: Boolean,
    onClick: (() -> Unit)?
) {
    val tint = if (active) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = onClick)
    } else Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = clickableModifier.padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(
            text = count?.toString() ?: label,
            style = MaterialTheme.typography.bodySmall,
            color = tint
        )
    }
}
