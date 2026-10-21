package noline.zhouzhou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import noline.zhouzhou.ui.theme.LocalNoLine

/** 条状容器只剩一种分层手段：一条发丝线，所以得说清它画在哪一缘。 */
enum class BarEdge {
    /** 顶栏：内容从下面滚过来，线画在下缘。 */
    Bottom,

    /** 底栏：内容从上面滚过来，线画在上缘。 */
    Top
}

/**
 * 扁平的条：一层不透明的实底 + 一条发丝线，不模糊、不投影、不描高光。
 *
 * 实底用的是卡片色 cardBg 而不是页面底色 pageBg：底栏压住滚过来的内容时，半透明的底会让
 * 上一行字残影似的留在下面，那就又变回玻璃了。分层交给那条线。
 */
@Composable
fun Modifier.noLineBar(edge: BarEdge = BarEdge.Bottom): Modifier {
    val t = LocalNoLine.current
    return this.background(t.cardBg).noLineHairline(edge)
}

/**
 * 只要那条线，实底自己铺。
 *
 * 底栏需要它：实底得连系统导航条那一条一起盖住，所以 background 要挂在
 * navigationBarsPadding **外面**，而线要画在栏的上缘 —— 两个东西不在同一个节点上，
 * 用 [noLineBar] 一步到位就会把线画到屏幕最底下。
 */
@Composable
fun Modifier.noLineHairline(edge: BarEdge = BarEdge.Bottom): Modifier {
    val t = LocalNoLine.current
    val density = LocalDensity.current
    return this.drawWithContent {
        drawContent()
        val thickness = with(density) { 0.7.dp.toPx() }
        drawRect(
            color = t.hairline,
            topLeft = Offset(0f, if (edge == BarEdge.Bottom) size.height - thickness else 0f),
            size = Size(size.width, thickness)
        )
    }
}

/**
 * 顶栏：贴住屏幕顶部、左右通栏，实底，只在下缘留一道发丝线。
 *
 * 它照样盖在内容上，所以外层要铺满整屏，再用 [onBarHeight] 回传的高度给自己让位 ——
 * 各页头部行数不一样，写死迟早对不上。
 */
@Composable
fun PageTopBar(
    onBarHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onBarHeight(with(density) { it.height.toDp() }) }
            .noLineBar()
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
