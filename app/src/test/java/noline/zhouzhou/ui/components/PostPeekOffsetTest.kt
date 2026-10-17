package noline.zhouzhou.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预览卡尺寸固定，位置全靠 peekOffset 算，所以越界这一条必须在纯函数上锁住：
 * 卡片贴在屏幕边缘或已经滚出一半时，预览不能弹到看不见的地方。
 */
class PostPeekOffsetTest {
    private val viewport = IntSize(1080, 2400)
    private val peek = IntSize(744, 960)

    @Test
    fun `居中落在卡片正中`() {
        val anchor = Rect(120f, 600f, 640f, 1200f)
        assertEquals(
            IntOffset(120 + (520 - 744) / 2, 600 + (600 - 960) / 2),
            peekOffset(anchor, viewport, peek)
        )
    }

    @Test
    fun `靠左的卡片不会把预览推到屏幕外`() {
        val anchor = Rect(0f, 300f, 520f, 900f)
        assertEquals(0, peekOffset(anchor, viewport, peek).x)
    }

    @Test
    fun `靠右靠下的卡片被夹回可视区`() {
        val anchor = Rect(560f, 1900f, 1080f, 2400f)
        val offset = peekOffset(anchor, viewport, peek)
        assertEquals(viewport.width - peek.width, offset.x)
        assertEquals(viewport.height - peek.height, offset.y)
    }

    @Test
    fun `已经滚出顶部的卡片把预览贴到屏幕顶端`() {
        val anchor = Rect(100f, -800f, 620f, -200f)
        assertEquals(0, peekOffset(anchor, viewport, peek).y)
    }
}
