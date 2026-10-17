@file:OptIn(ExperimentalSharedTransitionApi::class)

package noline.zhouzhou.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 首页/日记卡片和详情页共用同一张封面，跳转时让那块封面自己飞过去，
 * 而不是旧页淡出、新页淡入 —— 用户追的是同一张图，不是两个页面。
 *
 * 两个 scope 都得在场才成立：SharedTransitionScope 由最外层的
 * SharedTransitionLayout 提供，AnimatedVisibilityScope 是导航目的地的作用域。
 * 缺任何一个就当没这回事，卡片照常显示。
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 封面按文章 id 认领，别按图片 url —— 同一篇文章换图也得是同一个元素。 */
fun coverTransitionKey(postId: String) = "post-cover-$postId"

/** 换封面尺寸时按位置插值，不要先缩到占位大小再弹回来。 */
val CoverBoundsTransform = BoundsTransform { _, _ ->
    androidx.compose.animation.core.tween(360)
}

@Composable
fun Modifier.sharedPostCover(postId: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    val state = shared.rememberSharedContentState(key = coverTransitionKey(postId))
    return with(shared) {
        this@sharedPostCover.sharedElement(
            state = state,
            animatedVisibilityScope = animated,
            boundsTransform = CoverBoundsTransform
        )
    }
}
