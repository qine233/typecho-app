package noline.zhouzhou.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
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
import noline.zhouzhou.ui.components.BarEdge
import noline.zhouzhou.ui.components.noLineHairline
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
 * 底部导航：通栏实底，上缘一道发丝线，不悬浮、不模糊、不投影。
 *
 * 它是盖在内容上的，不是 Scaffold 占位，所以各 Tab 页要自己用 [rememberTabBarInset]
 * 把列表底部让出来，否则最后一行会被压在栏底下。
 *
 * 选中那一格仍然是一颗会跑过去的胶囊，但它只是一块纯色底：左右两条边各追各的目标位置，
 * 前沿快、后沿慢，路上被拉成一条，到站再收回 —— 形变、高光、压扁都撤了，动效只留位移。
 *
 * 按下去的那块高亮和选中那颗是**同一个形状**：同样是槽位外扩一圈的圆。默认 `clickable` 的水波纹
 * 是按节点矩形铺的（一格有 50 多 dp 宽），方形波纹从圆形指示底下冒出来，看着就是两套东西，
 * 所以这里把 indication 关掉，改成在 Row 底下画一颗按下的圆。
 */
@Composable
fun TypechoTabBar(
    tabs: List<TabSpec>,
    currentRoute: String?,
    onTabSelected: (TabSpec) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val slots: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() }
    val selected = tabs.firstOrNull { it.route == currentRoute }
    // 按下的那一格：高亮要和选中那颗同形，所以坐标也走同一套槽位 + 外扩
    var pressedRoute by remember { mutableStateOf<String?>(null) }
    // 槽位量到的是图标和字的外接框，色块要比它大一圈才不像贴上去的纸片
    val slot = selected?.let { slots[it.route] }
    val padX = with(density) { PillPadX.toPx() }
    val padY = with(density) { PillPadY.toPx() }
    val target = if (slot == null) Rect.Zero
    else Rect(slot.left - padX, slot.top - padY, slot.right + padX, slot.bottom + padY)
    val pressedSlot = pressedRoute?.let { slots[it] }
    val pressed = pressedSlot?.let {
        Rect(it.left - padX, it.top - padY, it.right + padX, it.bottom + padY)
    }

    val leading by animateFloatAsState(target.left, tween(220, easing = FastOutSlowInEasing), label = "pillLeading")
    val trailing by animateFloatAsState(target.right, tween(220, easing = FastOutSlowInEasing), label = "pillTrailing")
    val top by animateFloatAsState(target.top, tween(220, easing = FastOutSlowInEasing), label = "pillTop")
    val bottom by animateFloatAsState(target.bottom, tween(220, easing = FastOutSlowInEasing), label = "pillBottom")
    // 第一次布局之前槽位还没有，先别把一颗 0×0 的色块画出来
    val shown by animateFloatAsState(
        if (slot != null) 1f else 0f,
        tween(160),
        label = "pillShown"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 实底挂在 navigationBarsPadding 外面：系统导航条那一条也要盖住，
            // 不然内容会从手势条底下露出来（见 noLineHairline）。
            .background(LocalNoLine.current.cardBg)
            .navigationBarsPadding()
            .noLineHairline(BarEdge.Top)
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
                    .graphicsLayer { alpha = shown }
                    .flatPill(CircleShape)
            )
        }
        // 按下的高亮走同一套槽位 + 外扩，所以它和选中那颗是同一个圆，只是淡一档
        if (pressed != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset((pressed.left + rowPadPx).toInt(), pressed.top.toInt()) }
                    .size(
                        width = with(density) { pressed.width.toDp() },
                        height = with(density) { pressed.height.toDp() }
                    )
                    .background(LocalNoLine.current.accent.copy(alpha = PressAlpha), CircleShape)
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
                    onPressChanged = { down ->
                        if (down) pressedRoute = tab.route
                        else if (pressedRoute == tab.route) pressedRoute = null
                    },
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
                    onPressChanged = { down ->
                        if (down) pressedRoute = tab.route
                        else if (pressedRoute == tab.route) pressedRoute = null
                    },
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

private val TabBarHeight = 54.dp

/** 标签行离栏边缘的内缩：选中色块的坐标要跟着它走。 */
private val TabRowSideGap = 6.dp

/** 选中色块包住图标和字之后，再往外撑多少：槽位量到的就是这两样的边界。 */
private val PillPadX = 3.dp
private val PillPadY = 2.dp

/** 按下那一圈比选中的底再淡一点：选中格上按下去只会稍深，不会跳成另一颗颜色。 */
private const val PressAlpha = 0.10f

/**
 * 各 Tab 页列表底部该留多少：实底栏的高度 + 系统导航栏。
 * 栏是盖在内容上的，不再由外层 Scaffold 占位，所以这一份要页面自己算。
 */
@Composable
fun rememberTabBarInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
    TabBarHeight

@Composable
private fun TabButton(
    tab: TabSpec,
    selected: Boolean,
    onSlot: (Rect) -> Unit,
    onPressChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        // Release 和 Cancel 都不是「按着」，一起报 false
        interactionSource.interactions.collect { onPressChanged(it is PressInteraction.Press) }
    }
    val tint by animateColorAsState(
        targetValue = if (selected) t.accent else t.textSecondary,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "tabTint"
    )
    // 选中时图标抬到原尺寸，替代 Material 的高亮药丸
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tabScale"
    )

    Column(
        modifier = modifier
            .padding(horizontal = 3.dp)
            // 默认水波纹按节点矩形铺（一格 50 多 dp 宽），会从圆形指示底下冒出方的；
            // 按下的高亮改由栏自己画一颗同形的圆，这里把 indication 关掉。
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
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

/** 选中色块：一块纯色底，没有描边、没有内外那几道光。 */
@Composable
private fun Modifier.flatPill(shape: Shape): Modifier =
    this.graphicsLayer(shape = shape, clip = true).background(LocalNoLine.current.accentContainer)

@Composable
private fun PublishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 32.dp)
                .graphicsLayer(shape = RoundedCornerShape(999.dp), clip = true)
                .background(t.brand)
                .clickable(onClick = onClick),
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
