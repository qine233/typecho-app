package noline.zhouzhou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import noline.zhouzhou.ui.theme.LocalNoLine

/** 玻璃条离屏幕边缘留的距离：顶栏和底栏共用一套几何。 */
val GlassBarSideGap = 12.dp

/**
 * 站点那种「玻璃胶囊」在客户端的等价物：先把底下的页面录成一层可采样画面，
 * 在这块区域上做 46dp 高斯模糊，只盖 42% 的薄 tint，再在玻璃上缘留一道反光。
 *
 * tint 不能再厚：模糊之上多蒙一层白就不是玻璃而是磨砂塑料；模糊也不能太浅，
 * 否则底下的正文会以残影的形式透上来，比不透明还脏。
 */
@Composable
fun Modifier.noLineGlass(
    haze: HazeState,
    shape: Shape,
    elevation: Dp = 14.dp
): Modifier {
    val t = LocalNoLine.current
    return this
        .shadow(
            elevation, shape, clip = false,
            ambientColor = Color(0x1F2C2D30), spotColor = Color(0x402C2D30)
        )
        .clip(shape)
        .hazeEffect(haze) {
            blurRadius = 46.dp
            tints = listOf(HazeTint(t.glassBg.copy(alpha = 0.42f)))
            backgroundColor = Color.Transparent
            noiseFactor = 0.035f
            // 拿不到模糊时（Android 12 以下）只能靠这层实底撑住，别让内容糊在字上
            fallbackTint = HazeTint(t.glassBg)
        }
        .background(
            // 上缘那道反光：只留薄薄一条，铺宽了就成了第二层蒙版
            Brush.verticalGradient(
                0f to t.cardBg.copy(alpha = 0.26f),
                0.22f to t.cardBg.copy(alpha = 0.05f),
                0.4f to Color.Transparent
            )
        )
        .border(1.dp, t.cardBorder, shape)
}

/**
 * 悬浮玻璃顶栏。
 *
 * 它盖在内容上，所以内容必须真的能从它底下滚过去：外层要铺满整屏，再用
 * [onBarHeight] 回传的高度给自己让位。传了高度才知道列表顶部该留多少 ——
 * 各页头部行数不一样，写死迟早对不上。
 *
 * 和底栏比，它把上缘两个圆角收成了直角、并一直铺到屏幕顶：留缝的话列表滚上去的
 * 内容会从缝隙里钻出来顶在时间旁边，比不悬浮还乱；下缘仍是圆角，浮着的感觉保留。
 */
@Composable
fun GlassTopBar(
    haze: HazeState,
    onBarHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    // 量的是最外层，包含状态栏高度，否则内容会往上顶进玻璃里
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onBarHeight(with(density) { it.height.toDp() }) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassBarSideGap)
                .noLineGlass(
                    haze,
                    RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
                )
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            content = content
        )
    }
}
