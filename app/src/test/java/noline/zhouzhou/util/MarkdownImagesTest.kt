package noline.zhouzhou.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拖拽排序最后落到 MarkdownImages.move 这一个纯函数上：它直接改写用户正文，
 * 所以「搬完之后段落不能被粘住」必须逐条钉死，而不是在 UI 里肉眼看着办。
 */
class MarkdownImagesTest {

    private val doc = "前段\n\n![a](ua)\n\n中段\n\n![b](ub)\n\n尾段"

    @Test
    fun `按出现顺序解析出插图`() {
        assertEquals(
            listOf("ua" to "a", "ub" to "b"),
            MarkdownImages.parse(doc).map { it.url to it.alt }
        )
    }

    @Test
    fun `往后挪是插到那张之后`() {
        assertEquals(
            "前段\n\n中段\n\n![b](ub)\n![a](ua)\n\n尾段",
            MarkdownImages.move(doc, from = 0, to = 1)
        )
    }

    @Test
    fun `往前挪是插到那张之前`() {
        assertEquals(
            "前段\n\n![b](ub)\n![a](ua)\n\n中段\n\n尾段",
            MarkdownImages.move(doc, from = 1, to = 0)
        )
    }

    @Test
    fun `搬走的图不会在原地留下空行`() {
        val text = "![a](ua)\n\n第一段\n\n![b](ub)\n\n第二段"
        assertEquals(
            "第一段\n\n![b](ub)\n![a](ua)\n\n第二段",
            MarkdownImages.move(text, from = 0, to = 1)
        )
    }

    @Test
    fun `行内插图搬完不把两张图粘成一行`() {
        val text = "看这里 ![a](ua) 继续写\n\n![b](ub)"
        assertEquals(
            "看这里  继续写\n\n![b](ub)\n![a](ua)",
            MarkdownImages.move(text, from = 0, to = 1)
        )
    }

    @Test
    fun `挪到末位就是排到最后`() {
        val text = "![a](ua)\n\n![b](ub)\n\n![c](uc)\n\n![d](ud)"
        assertEquals(
            listOf("ub", "uc", "ud", "ua"),
            MarkdownImages.parse(MarkdownImages.move(text, from = 0, to = 3)).map { it.url }
        )
    }

    @Test
    fun `原地不动和下标越界都原样返回`() {
        assertEquals(doc, MarkdownImages.move(doc, from = 2, to = 2))
        assertEquals(doc, MarkdownImages.move(doc, from = 0, to = 5))
        assertEquals(doc, MarkdownImages.move(doc, from = 5, to = 0))
        assertEquals("没有图", MarkdownImages.move("没有图", from = 0, to = 1))
    }
}
