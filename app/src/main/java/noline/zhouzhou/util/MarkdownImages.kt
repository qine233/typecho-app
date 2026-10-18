package noline.zhouzhou.util

/** 正文里的一张 `![alt](url)`：半开区间 [start, end) 是它在原文里的位置。 */
data class InlineImage(val alt: String, val url: String, val start: Int, val end: Int)

/**
 * 正文中的插图。编辑器里的「拖拽排序」不动 Markdown 结构，只把一张图整块搬到
 * 另一张图的前/后，中间那段文字原样跟着各自的行走。
 */
object MarkdownImages {

    private val Token = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)")

    fun parse(text: String): List<InlineImage> =
        Token.findAll(text).map {
            InlineImage(
                alt = it.groupValues[1],
                url = it.groupValues[2],
                start = it.range.first,
                end = it.range.last + 1
            )
        }.toList()

    /**
     * 把第 [from] 张图挪到第 [to] 张图的位置上（下标按图在正文里出现的先后数）。
     * 往后挪 = 插到那张之后，往前挪 = 插到那张之前，和拖拽时的直觉一致。
     * 独占一行的图整行搬走，原地不留空行；下标越界或原地不动都原样返回。
     */
    fun move(text: String, from: Int, to: Int): String {
        val images = parse(text)
        if (from == to || from !in images.indices || to !in images.indices) return text

        val source = images[from]
        val token = text.substring(source.start, source.end)
        val lineStart = text.lastIndexOf('\n', source.start - 1) + 1
        val ownsLine = text.substring(lineStart, source.start).isBlank() &&
            (source.end == text.length || text[source.end] == '\n')

        var cutFrom: Int
        var cutTo: Int
        if (ownsLine) {
            cutFrom = lineStart
            cutTo = (source.end + 1).coerceAtMost(text.length)
            // 文档末尾的图行没有行尾换行可吃，就往前吃上一行的。
            if (source.end == text.length && lineStart > 0) cutFrom = lineStart - 1
            // 图行上下都空着一行（或者它就在文首）时，把多出来的那个空行一起剪掉。
            if ((cutFrom == 0 || text[cutFrom - 1] == '\n') &&
                cutTo < text.length && text[cutTo] == '\n'
            ) {
                cutTo++
            }
        } else {
            // 夹在段落里的图只能抠掉它自己，剩下的空格归那段文字。
            cutFrom = source.start
            cutTo = source.end
        }

        val rest = text.substring(0, cutFrom) + text.substring(cutTo)
        // 剪一刀之后偏移全变了，重新解析拿新坐标，比自己算补偿不容易错。
        val shifted = parse(rest)
        val at = if (to > from) shifted[to - 1].end else shifted[to].start
        val lead = if (at > 0 && rest[at - 1] != '\n') "\n" else ""
        val trail = if (at < rest.length && rest[at] != '\n') "\n" else ""
        return rest.substring(0, at) + lead + token + trail + rest.substring(at)
    }
}
