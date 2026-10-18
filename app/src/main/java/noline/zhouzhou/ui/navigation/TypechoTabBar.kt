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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
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
 * 底部导航：一颗真的悬浮胶囊。
 *
 * 「液态玻璃」靠 Haze 把底下的页面录成一层画面、再在这颗胶囊上做高斯模糊（Android 12+
 * 走 RenderEffect，12 以下拿不到模糊，就退化成 tint 更实的毛玻璃色块，形态不变）。
 * 所以页面必须能从胶囊底下滚过去 —— 外层已经不再是 Scaffold 占位，各 Tab 页要自己
 * 用 [rememberTabBarInset] 把列表底部让出来，否则最后一行会被压在玻璃下面。
 */
@Composable
fun TypechoTabBar(
    tabs: List<TabSpec>,
    currentRoute: String?,
    hazeState: HazeState,
    onTabSelected: (TabSpec) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val shape = CircleShape

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(top = 4.dp, bottom = TabBarLift)
            .shadow(
                14.dp, shape, clip = false,
                ambientColor = Color(0x1F2C2D30), spotColor = Color(0x402C2D30)
            )
            .clip(shape)
            .hazeEffect(hazeState) {
                blurRadius = 26.dp
                tints = listOf(HazeTint(t.glassBg))
                backgroundColor = Color.Transparent
                noiseFactor = 0.035f
                // 拿不到模糊时（Android 12 以下）只能靠这层实底撑住，别让内容糊在字上
                fallbackTint = HazeTint(t.glassBg)
            }
            .border(1.dp, t.cardBorder, shape)
            .height(TabBarHeight)
    ) {
        // 上缘那道反光：玻璃胶囊被光照到的那条边，暗色下用卡片色提亮，别用死白
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to t.cardBg.copy(alpha = 0.55f),
                        0.45f to t.cardBg.copy(alpha = 0.10f),
                        0.72f to Color.Transparent
                    )
                )
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
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
}

/** 胶囊抬起的高度：内容底部要让出的距离里也带着这一份。 */
private val TabBarLift = 12.dp
private val TabBarHeight = 54.dp

/**
 * 各 Tab 页列表底部该留多少：玻璃胶囊本身的高度 + 抬起的那截 + 系统导航栏。
 * 底栏现在是盖在内容上的，不再由外层 Scaffold 占位，所以这一份要页面自己算。
 */
@Composable
fun rememberTabBarInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
    TabBarHeight + TabBarLift

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
