package noline.zhouzhou.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalAccentOn

/** 自绘的底栏条目，替代 Material NavigationBar 的 pill + tone 容器造型。 */
data class TabSpec(
    val route: String,
    val label: String,
    val outlineIcon: ImageVector,
    val filledIcon: ImageVector,
    /** 带可选参数的路由需要显式给出跳转目标。 */
    val navRoute: String = route
)

/**
 * 小红书式底部导航：纯白扁条、图标+文字、选中用点缀色，
 * 正中是「+ 发布」方块按钮。
 */
@Composable
fun TypechoTabBar(
    tabs: List<TabSpec>,
    currentRoute: String?,
    onTabSelected: (TabSpec) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalAccent.current

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 先让开系统导航条，再给内容 52dp；反过来的话这部分高度会被扣掉，图标和文字全被压扁。
                .navigationBarsPadding()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val left = tabs.take((tabs.size + 1) / 2)
            val right = tabs.drop((tabs.size + 1) / 2)
            left.forEach { tab ->
                TabButton(
                    tab = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
            PublishButton(
                onClick = onCreateClick,
                accent = accent,
                modifier = Modifier.weight(1.4f)
            )
            right.forEach { tab ->
                TabButton(
                    tab = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
            // 非管理员只有三个标签，右侧补位保持居中
            repeat(tabs.size % 2) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalAccent.current
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(
        targetValue = if (selected) accent else inactive,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "tabTint"
    )
    // 选中时轻微回弹，替代 Material 的高亮药丸
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tabScale"
    )

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) tab.filledIcon else tab.outlineIcon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint
        )
    }
}

@Composable
private fun PublishButton(
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "publishScale"
    )
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(width = 48.dp, height = 30.dp)
                .background(accent, RoundedCornerShape(9.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "发布",
                tint = LocalAccentOn.current,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
