package noline.zhouzhou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import noline.zhouzhou.ui.theme.LocalNoLine

/** 玻璃条离屏幕边缘留的距离：底栏那颗胶囊四周都不贴边。 */
val GlassBarSideGap = 12.dp

/**
 * 站点那种「玻璃」在客户端的等价物：先把底下的页面录成一层可采样画面，在这块区域
 * 上做 46dp 高斯模糊，盖一层可调厚薄的 tint，再在玻璃上缘留一道反光。
 *
 * tint 的厚薄就是「透」和「磨砂」的分界：底栏要看得穿（42%），顶栏压到 74% 当磨砂，
 * 只留底下颜色化开的那点影子。模糊不能太浅，否则正文会以残影的形式透上来，比不透明还脏。
 */
@Composable
fun Modifier.noLineGlass(
    haze: HazeState,
    shape: Shape,
    tintAlpha: Float = 0.42f,
    noise: Float = 0.035f,
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
            tints = listOf(HazeTint(t.glassBg.copy(alpha = tintAlpha)))
            backgroundColor = Color.Transparent
            noiseFactor = noise
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
 * 磨砂顶栏：整条贴住屏幕顶部、左右通栏，不悬浮。
 *
 * 它仍然盖在内容上、内容从它底下滚过去，所以外层要铺满整屏，再用 [onBarHeight]
 * 回传的高度给自己让位 —— 各页头部行数不一样，写死迟早对不上。通栏也顺手去掉了
 * 留缝的麻烦：玻璃上方一旦有缝，滚上去的内容会从缝里钻出来，顶在状态栏时间旁边。
 *
 * 四条描边只有下缘那条看得见（其余三条被屏幕边切掉），正好当发丝分隔线。
 */
@Composable
fun GlassTopBar(
    haze: HazeState,
    onBarHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onBarHeight(with(density) { it.height.toDp() }) }
            .noLineGlass(haze, RectangleShape, tintAlpha = 0.74f, noise = 0.05f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            content = content
        )
    }
}
