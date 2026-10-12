package noline.zhouzhou.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.ui.theme.LocalNoLine

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
 * 底部导航照站点顶栏的「玻璃胶囊」那一态做：一颗浮在页底的胶囊，
 * 1px 蓝灰描边 + 半透明白底，选中项垫一块强调色淡底、字换成强调色。
 *
 * 没有真高斯模糊（minSdk 26 上 RenderEffect 用不了），所以底色给足不透明度，
 * 只留一点通透感，别让滚动的卡片把栏面糊脏。
 */
@Composable
fun TypechoTabBar(
    tabs: List<TabSpec>,
    currentRoute: String?,
    onTabSelected: (TabSpec) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
            .padding(bottom = 8.dp, top = 2.dp)
            .shadow(6.dp, CircleShape, ambientColor = Color(0x0F2C2D30), spotColor = Color(0x1A2C2D30))
            .clip(CircleShape)
            .background(t.glassBg)
            .border(1.dp, t.cardBorder, CircleShape)
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

@Composable
private fun TabButton(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val tint by animateColorAsState(
        targetValue = if (selected) t.accent else t.textSecondary,
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
        modifier = modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) t.accentBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) tab.filledIcon else tab.outlineIcon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = tab.label,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tint
        )
    }
}

@Composable
private fun PublishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
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
                .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = t.accentRing, spotColor = t.accentRing)
                .size(width = 50.dp, height = 32.dp)
                .background(t.brand, RoundedCornerShape(999.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "发布",
                tint = t.onBrand,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
