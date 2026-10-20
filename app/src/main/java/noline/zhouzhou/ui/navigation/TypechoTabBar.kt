package noline.zhouzhou.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import noline.zhouzhou.ui.components.GlassBarSideGap
import noline.zhouzhou.ui.components.glassLight
import noline.zhouzhou.ui.components.noLineGlass
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
 * 底部导航：一颗真的悬浮胶囊，材质和顶栏是同一块（见 [noLineGlass]）。
 *
 * 「液态玻璃」靠 Haze 把底下的页面录成一层画面、再在这颗胶囊上做高斯模糊（Android 12+
 * 走 RenderEffect，12 以下拿不到模糊，就退化成 tint 更实的毛玻璃色块，形态不变）。
 * 所以页面必须能从胶囊底下滚过去 —— 外层已经不再是 Scaffold 占位，各 Tab 页要自己
 * 用 [rememberTabBarInset] 把列表底部让出来，否则最后一行会被压在玻璃下面。
 *
 * 选中那一格的高亮不是每格自己画一个背景，而是**一颗会从上一格跑到下一格的胶囊**：
 * 左右两条边各自用一套弹簧追目标位置，前沿到得快、后沿跟得慢，路上就被拉成一条，到站
 * 再收回来 —— iOS 26 那个「液体」感全部来自这一下一快，不需要真的形变网格。按住时整颗
 * 压扁一点（横向胀、纵向缩），松手回弹。
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
    val density = LocalDensity.current
    val slots: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() }
    var pressedRoute by remember { mutableStateOf<String?>(null) }
    val selected = tabs.firstOrNull { it.route == currentRoute }
    // 槽位量到的是图标和字的外接框，胶囊要比它大一圈才不像贴上去的纸片
    val slot = selected?.let { slots[it.route] }
    val padX = with(density) { PillPadX.toPx() }
    val padY = with(density) { PillPadY.toPx() }
    val target = if (slot == null) Rect.Zero
    else Rect(slot.left - padX, slot.top - padY, slot.right + padX, slot.bottom + padY)

    val leading by animateFloatAsState(target.left, spring(stiffness = 2800f, dampingRatio = 0.78f), label = "pillLeading")
    val trailing by animateFloatAsState(target.right, spring(stiffness = 1500f, dampingRatio = 0.92f), label = "pillTrailing")
    val top by animateFloatAsState(target.top, spring(stiffness = 1800f, dampingRatio = 0.85f), label = "pillTop")
    val bottom by animateFloatAsState(target.bottom, spring(stiffness = 1800f, dampingRatio = 0.85f), label = "pillBottom")
    // 第一次布局之前槽位还没有，先别把一颗 0×0 的胶囊画出来
    val shown by animateFloatAsState(
        if (slot != null) 1f else 0f,
        tween(160),
        label = "pillShown"
    )
    val squished = pressedRoute != null && pressedRoute == selected?.route
    val scaleX by animateFloatAsState(
        if (squished) 1.06f else 1f,
        spring(stiffness = 900f, dampingRatio = 0.42f),
        label = "pillSquishX"
    )
    val scaleY by animateFloatAsState(
        if (squished) 0.88f else 1f,
        spring(stiffness = 900f, dampingRatio = 0.42f),
        label = "pillSquishY"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = GlassBarSideGap)
            .padding(top = 4.dp, bottom = TabBarLift)
            .noLineGlass(hazeState, CircleShape)
            .height(TabBarHeight)
    ) {
        val rowPadPx = with(density) { TabRowSideGap.toPx() }
        val w = (trailing - leading).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)
        if (shown > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // 槽位坐标是相对那一行量的，行本身左右各缩进 TabRowSideGap
                    .offset { IntOffset((leading + rowPadPx).toInt(), top.toInt()) }
                    .size(width = with(density) { w.toDp() }, height = with(density) { h.toDp() })
                    .graphicsLayer {
                        alpha = shown
                        this.scaleX = scaleX
                        this.scaleY = scaleY
                    }
                    .liquidPill(CircleShape)
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = TabRowSideGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val left = tabs.take((tabs.size + 1) / 2)
            val right = tabs.drop((tabs.size + 1) / 2)
            left.forEach { tab ->
                TabButton(
                    tab = tab,
                    selected = currentRoute == tab.route,
                    onSlot = { slots[tab.route] = it },
                    onPress = { pressed -> pressedRoute = if (pressed) tab.route else null },
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
                    onSlot = { slots[tab.route] = it },
                    onPress = { pressed -> pressedRoute = if (pressed) tab.route else null },
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

/** 标签行离玻璃胶囊的内缩：选中胶囊的坐标要跟着它走。 */
private val TabRowSideGap = 6.dp

/** 选中胶囊包住图标和字之后，再往外撑多少：槽位量到的就是这两样的边界。 */
private val PillPadX = 3.dp
private val PillPadY = 2.dp

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
    onSlot: (Rect) -> Unit,
    onPress: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPress(pressed) }
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 5.dp)
            .onGloballyPositioned { onSlot(it.boundsInParent()) },
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

/**
 * 那颗跑动的选中胶囊：自己不模糊（它泡在玻璃里），只把 iOS 26 的「厚」画出来 ——
 * 一层强调色底、上缘一条内高光、下缘一条内阴影、外圈一道从左上到右下的镜面描边。
 */
@Composable
private fun Modifier.liquidPill(shape: Shape): Modifier {
    val t = LocalNoLine.current
    val density = LocalDensity.current
    return this
        .graphicsLayer(shape = shape, clip = true)
        .background(t.accentContainer)
        .drawWithCache {
            val rimPx = with(density) { 0.8.dp.toPx() }
            val bandPx = with(density) { 2.4.dp.toPx() }
            val corner = CornerRadius(size.height / 2f)
            onDrawWithContent {
                drawContent()
                // 已经被 shape 剪过，亮带直接铺满宽度就行，边缘自然跟着胶囊收
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to t.glassHighlight.glassLight(0.4f),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = bandPx
                    ),
                    size = Size(size.width, bandPx)
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to t.glassInnerShadow.glassLight(0.5f),
                        startY = size.height - bandPx * 1.6f,
                        endY = size.height
                    ),
                    topLeft = Offset(0f, size.height - bandPx * 1.6f),
                    size = Size(size.width, bandPx * 1.6f)
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        0f to t.glassHighlight.glassLight(0.55f),
                        0.35f to t.accent.copy(alpha = 0.10f),
                        1f to t.glassHighlight.glassLight(0.45f),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    style = Stroke(width = rimPx * 2f),
                    cornerRadius = corner
                )
            }
        }
}

@Composable
private fun PublishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 按住时胀一下横向、缩一下纵向，和选中胶囊同一套形变语言
    val scaleX by animateFloatAsState(
        targetValue = if (pressed) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "publishScaleX"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "publishScaleY"
    )
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
                .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = t.accentRing, spotColor = t.accentRing)
                .size(width = 50.dp, height = 32.dp)
                .background(t.brand, RoundedCornerShape(999.dp))
                .drawWithCache {
                    val bandPx = with(density) { 2.2.dp.toPx() }
                    val corner = CornerRadius(size.height / 2f)
                    onDrawWithContent {
                        drawContent()
                        // 实心品牌色上也留一道上缘高光，玻璃那一套语言要统一
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.34f),
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = bandPx
                            ),
                            cornerRadius = corner
                        )
                    }
                }
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
