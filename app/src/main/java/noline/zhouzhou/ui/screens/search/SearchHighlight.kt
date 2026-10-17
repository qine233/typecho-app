package noline.zhouzhou.ui.screens.search

/** 文字里的一段，matched 表示它命中了搜索词。 */
data class TextSegment(val text: String, val matched: Boolean)

/**
 * 关键词高亮只做「切段」这一件事，交给 Composable 去上色，
 * 这样这段逻辑能在纯 JVM 单测里跑。
 */
object SearchHighlight {

    fun terms(query: String): List<String> =
        query.split(termsSplit).filter { it.isNotBlank() }

    fun segments(text: String, query: String): List<TextSegment> {
        if (text.isEmpty()) return emptyList()
        val terms = terms(query)
        if (terms.isEmpty()) return listOf(TextSegment(text, false))

        val hits = BooleanArray(text.length)
        terms.forEach { term ->
            var from = 0
            while (from <= text.length - term.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                for (i in at until at + term.length) hits[i] = true
                from = at + term.length
            }
        }

        val segments = mutableListOf<TextSegment>()
        var start = 0
        while (start < text.length) {
            val matched = hits[start]
            var end = start + 1
            while (end < text.length && hits[end] == matched) end++
            segments.add(TextSegment(text.substring(start, end), matched))
            start = end
        }
        return segments
    }

    private val termsSplit = Regex("\\s+")
}
