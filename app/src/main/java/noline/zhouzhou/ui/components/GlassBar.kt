package noline.zhouzhou.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import noline.zhouzhou.ui.theme.LocalNoLine

/** 玻璃条离屏幕边缘留的距离：底栏那颗胶囊四周都不贴边。 */
val GlassBarSideGap = 12.dp

/**
 * 这块玻璃的哪几段边露在外面。
 *
 * iOS 26 的液态玻璃看着「厚」，靠的全是边缘那几道：外圈一道镜面高光、内圈一道更细的
 * 反光、上缘内侧一条亮带、下缘内侧一条暗带。通栏顶栏只有下缘在屏幕上看得见，硬画四边
 * 就成了贴住状态栏的一条白框，所以那一圈要按边形态关掉。
 */
enum class GlassEdge {
    /** 悬浮胶囊：四条边都露着，镜面沿左上到右下走。 */
    Floating,

    /** 通栏：只有下缘看得见，那就只在下缘留内阴影 + 一道发丝线。 */
    BottomOnly
}

/**
 * 站点那种「玻璃」在客户端的等价物，材质按 iOS 26 的液态玻璃分四层叠：
 * 底下页面录成一层可采样画面 → 高斯模糊 + tint → 边缘镜面描边（对角渐变，光从左上打）
 * → 上缘内高光带 / 下缘内阴影带撑出厚度。
 *
 * tint 的厚薄是「透」和「磨砂」的分界：底栏要看得穿（42%），顶栏压到 74% 当磨砂，
 * 只留底下颜色化开的那点影子。模糊不能太浅，否则正文会以残影的形式透上来，比不透明还脏。
 */
@Composable
fun Modifier.noLineGlass(
    haze: HazeState,
    shape: Shape,
    tintAlpha: Float = 0.42f,
    noise: Float = 0.035f,
    elevation: Dp = 14.dp,
    edge: GlassEdge = GlassEdge.Floating
): Modifier {
    val t = LocalNoLine.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
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
        .drawWithGlassEdge(t.glassHighlight, t.glassInnerShadow, t.hairline, shape, edge, density, layoutDirection)
}

/**
 * 边缘那几道光的强弱：令牌里的 [NoLineTokens.glassHighlight] 是这块
 * 玻璃「满亮度」长什么样，具体某一道光按倍数压。直接 `copy(alpha = …)` 会把两套主题拉平到
 * 同一个数，暗色主题的玻璃本来就更暗、需要更亮的高光，那份差异会被抹掉。
 */
internal fun Color.glassLight(scale: Float): Color = copy(alpha = alpha * scale)

/**
 * 在已经 clip 过的玻璃面上补边缘那几道。用 `drawWithCache` 是为了让描边跟着尺寸变化重建，
 * 顶栏高度是各页回传的，每帧重建 path 太浪费。
 */
@Composable
private fun Modifier.drawWithGlassEdge(
    highlight: Color,
    innerShadow: Color,
    hairline: Color,
    shape: Shape,
    edge: GlassEdge,
    density: Density,
    layoutDirection: LayoutDirection
): Modifier = this.drawWithCache {
    val rimPx = with(density) { 1.1.dp.toPx() }
    val bandPx = with(density) { 3.5.dp.toPx() }
    val outline = shape.createOutline(size, layoutDirection, density)
    // 描边是压在路径正中的，外面那半会被 clip 掉，所以线宽给两倍。
    val outerStroke = Stroke(width = rimPx * 2f)
    val innerOutline = if (edge == GlassEdge.Floating) {
        val inset = rimPx * 1.6f
        Path().apply {
            addOutline(
                shape.createOutline(
                    Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f)),
                    layoutDirection,
                    density
                )
            )
            translate(Offset(inset, inset))
        }
    } else {
        null
    }

    onDrawWithContent {
        drawContent()

        // 上缘内侧那条亮带：玻璃被上面打过来的光照亮
        drawRect(
            brush = Brush.verticalGradient(
                0f to highlight.glassLight(0.45f),
                1f to Color.Transparent,
                startY = 0f,
                endY = bandPx
            ),
            size = Size(size.width, bandPx)
        )
        // 下缘内侧那条暗带：厚度掉在自己身上的影子，比亮带宽，收得更缓
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to innerShadow.glassLight(0.6f),
                startY = size.height - bandPx * 2.4f,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - bandPx * 2.4f),
            size = Size(size.width, bandPx * 2.4f)
        )

        when (edge) {
            GlassEdge.Floating -> {
                // 外圈镜面：光从左上打过来，所以左下和右上最弱、两端最强
                drawOutline(
                    outline,
                    brush = Brush.linearGradient(
                        0f to highlight.glassLight(0.95f),
                        0.3f to highlight.glassLight(0.16f),
                        0.62f to highlight.glassLight(0.3f),
                        1f to highlight.glassLight(1f),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    style = outerStroke
                )
                innerOutline?.let {
                    drawPath(
                        it,
                        brush = Brush.linearGradient(
                            0f to highlight.glassLight(0.35f),
                            1f to highlight.glassLight(0.08f),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        ),
                        style = Stroke(width = with(density) { 0.7.dp.toPx() })
                    )
                }
            }

            GlassEdge.BottomOnly -> {
                // 只有下缘那道发丝线，左右和上缘贴着屏幕边，画了也看不见。
                // 这里不能用镜面高光：亮色主题下高光是白的，压在白玻璃上等于没画。
                drawRect(
                    color = hairline,
                    topLeft = Offset(0f, size.height - with(density) { 0.7.dp.toPx() }),
                    size = Size(size.width, with(density) { 0.7.dp.toPx() })
                )
            }
        }
    }
}

/**
 * 磨砂顶栏：整条贴住屏幕顶部、左右通栏，不悬浮。
 *
 * 它仍然盖在内容上、内容从它底下滚过去，所以外层要铺满整屏，再用 [onBarHeight]
 * 回传的高度给自己让位 —— 各页头部行数不一样，写死迟早对不上。通栏也顺手去掉了
 * 留缝的麻烦：玻璃上方一旦有缝，滚上去的内容会从缝里钻出来，顶在状态栏时间旁边。
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
            .noLineGlass(haze, RectangleShape, tintAlpha = 0.74f, noise = 0.05f, edge = GlassEdge.BottomOnly)
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
