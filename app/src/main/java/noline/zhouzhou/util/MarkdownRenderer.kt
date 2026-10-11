package noline.zhouzhou.util

/**
 * 轻量 Markdown → HTML 转换，用于编辑器预览。
 * 站点接口返回的是已编译 HTML，遇到块级 HTML 标签时直接透传；
 * 用户手写的 Markdown 才走这里转换。
 */
object MarkdownRenderer {

    private val blockHtml = Regex(
        "<\\s*(p|div|h[1-6]|ul|ol|li|blockquote|pre|table|thead|tbody|figure|section|article|br|hr|img|iframe|code)\\b",
        RegexOption.IGNORE_CASE
    )

    fun toHtml(markdown: String): String {
        val text = markdown.trim()
        if (text.isEmpty()) return ""
        if (blockHtml.containsMatchIn(text)) return markdown
        return renderBlocks(text)
    }

    private fun renderBlocks(md: String): String {
        val lines = md.replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        sb.append(escape(lines[i])).append('\n'); i++
                    }
                    if (i < lines.size) i++ // skip closing fence
                    out.append("<pre><code>").append(sb).append("</code></pre>")
                }

                line.matches(Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")) -> {
                    out.append("<hr>"); i++
                }

                Regex("^#{1,6}\\s").containsMatchIn(line) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val content = inline(line.drop(level).trim())
                    out.append("<h$level>").append(content).append("</h$level>"); i++
                }

                line.startsWith(">") -> {
                    val sb = StringBuilder()
                    while (i < lines.size && lines[i].startsWith(">")) {
                        sb.append(lines[i].drop(1).removePrefix(" ")).append("<br>")
                        i++
                    }
                    out.append("<blockquote>").append(inline(sb.toString())).append("</blockquote>")
                }

                Regex("^\\s*[-*]\\s+").containsMatchIn(line) -> {
                    val sb = StringBuilder("<ul>")
                    while (i < lines.size && Regex("^\\s*[-*]\\s+").containsMatchIn(lines[i])) {
                        sb.append("<li>").append(inline(lines[i].replaceFirst(Regex("^\\s*[-*]\\s+"), ""))).append("</li>")
                        i++
                    }
                    out.append(sb).append("</ul>")
                }

                Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line) -> {
                    val sb = StringBuilder("<ol>")
                    while (i < lines.size && Regex("^\\s*\\d+\\.\\s+").containsMatchIn(lines[i])) {
                        sb.append("<li>").append(inline(lines[i].replaceFirst(Regex("^\\s*\\d+\\.\\s+"), ""))).append("</li>")
                        i++
                    }
                    out.append(sb).append("</ol>")
                }

                line.contains("|") && i + 1 < lines.size && lines[i + 1].contains(Regex("-")) && lines[i].trim().startsWith("|") -> {
                    i = renderTable(lines, i, out)
                }

                line.isBlank() -> i++

                else -> {
                    val para = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !lines[i].startsWith("#") && !lines[i].startsWith(">") &&
                        !lines[i].startsWith("```") && !Regex("^\\s*[-*]\\s+").containsMatchIn(lines[i]) &&
                        !Regex("^\\s*\\d+\\.\\s+").containsMatchIn(lines[i])
                    ) {
                        para.append(inline(lines[i])).append("<br>")
                        i++
                    }
                    out.append("<p>").append(para.toString().removeSuffix("<br>")).append("</p>")
                }
            }
        }
        return out.toString()
    }

    private fun renderTable(lines: List<String>, start: Int, out: StringBuilder): Int {
        var i = start
        val header = lines[i].trim().trim('|').split("|").map { it.trim() }
        i += 2 // header + separator
        val body = mutableListOf<List<String>>()
        while (i < lines.size && lines[i].contains("|")) {
            body.add(lines[i].trim().trim('|').split("|").map { it.trim() })
            i++
        }
        val sb = StringBuilder("<table><thead><tr>")
        header.forEach { sb.append("<th>").append(inline(it)).append("</th>") }
        sb.append("</tr></thead><tbody>")
        body.forEach { row ->
            sb.append("<tr>")
            row.forEach { sb.append("<td>").append(inline(it)).append("</td>") }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        out.append(sb)
        return i
    }

    private fun inline(s: String): String {
        var r = s
        r = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)").replace(r) { "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">" }
        r = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(r) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        r = Regex("`([^`]+)`").replace(r) { "<code>${it.groupValues[1]}</code>" }
        r = Regex("\\*\\*([^*]+)\\*\\*").replace(r) { "<strong>${it.groupValues[1]}</strong>" }
        r = Regex("__([^_]+)__").replace(r) { "<strong>${it.groupValues[1]}</strong>" }
        r = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(r) { "<em>${it.groupValues[1]}</em>" }
        r = Regex("~~([^~]+)~~").replace(r) { "<del>${it.groupValues[1]}</del>" }
        return r
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
