package noline.zhouzhou.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHighlightTest {

    private fun joined(text: String, query: String) =
        SearchHighlight.segments(text, query).joinToString("|") { (piece, matched) ->
            if (matched) "[$piece]" else piece
        }

    @Test
    fun `wraps the matched run and keeps the rest plain`() {
        assertEquals("今天去|[番茄]|炒蛋", joined("今天去番茄炒蛋", "番茄"))
    }

    @Test
    fun `every occurrence gets wrapped`() {
        assertEquals("[a]|bcd|[a]", joined("abcda", "a"))
    }

    @Test
    fun `matching ignores case but keeps the original letters`() {
        assertEquals("用|[TYPECHO]|建站", joined("用TYPECHO建站", "typecho"))
    }

    @Test
    fun `each word of a multi-word query highlights on its own`() {
        // 「博客」和「建」挨在一起，就并成一段，而不是切出两个相邻的标记。
        assertEquals("[博客建]|设", joined("博客建设", "博客 建"))
    }

    @Test
    fun `terms covering the whole text merge into one run`() {
        assertEquals("[Typecho]", joined("Typecho", "Type cho"))
    }

    @Test
    fun `no query means no marks`() {
        assertEquals("随便一句", joined("随便一句", ""))
        assertEquals("随便一句", joined("随便一句", "   "))
    }

    @Test
    fun `a term that is not there leaves the text untouched`() {
        assertEquals("没有命中", joined("没有命中", "番茄"))
    }

    @Test
    fun `empty text yields no segments at all`() {
        assertEquals(emptyList<TextSegment>(), SearchHighlight.segments("", "番茄"))
    }

    @Test
    fun `terms split on whitespace only`() {
        assertEquals(listOf("博客", "建设"), SearchHighlight.terms("  博客\t建设 \n"))
    }
}
