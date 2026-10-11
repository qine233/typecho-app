package com.typecho.app.util

/**
 * 把 Typecho 接口返回的编译后 HTML 反向转换为 Markdown，供编辑器编辑。
 * 覆盖常见块级/行内标签；遇到已是 Markdown（无块级标签）的内容直接透传。
 */
object HtmlToMarkdown {

    private val blockTag = Regex(
        "<\\s*(p|div|h[1-6]|ul|ol|li|blockquote|pre|table|thead|tbody|br|hr|img|strong|em|a|code|del|s|strike)\\b",
        RegexOption.IGNORE_CASE
    )

    fun convert(html: String): String {
        val src = html.replace("\r\n", "\n").trim()
        if (src.isEmpty()) return ""
        if (!blockTag.containsMatchIn(src)) return html // 已经是 Markdown/纯文本

        var s = src

        // 1. 代码块（先处理，保护内部内容）
        s = Regex("(?is)<pre[^>]*>\\s*<code[^>]*>(.*?)</code>\\s*</pre>").replace(s) {
            "```\n" + decode(stripTags(it.groupValues[1])).trim() + "\n```"
        }
        s = Regex("(?is)<pre[^>]*>(.*?)</pre>").replace(s) {
            "```\n" + decode(stripTags(it.groupValues[1])).trim() + "\n```"
        }
        // 2. 行内代码
        s = Regex("(?is)<code[^>]*>(.*?)</code>").replace(s) {
            "`" + decode(stripTags(it.groupValues[1])).trim() + "`"
        }
        // 3. 图片
        s = Regex("(?is)<img\\b[^>]*>").replace(s) { m ->
            val tag = m.value
            val s2 = Regex("src\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: ""
            val alt = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: ""
            "![$alt]($s2)"
        }
        // 4. 链接（空文字的锚点直接丢弃，Typecho 会给标题自动生成 <a></a>）
        s = Regex("(?is)<a\\b[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>").replace(s) {
            val text = decode(stripTags(it.groupValues[2])).trim()
            if (text.isEmpty()) "" else "[$text](${it.groupValues[1]})"
        }
        // 5. 粗体 / 斜体 / 删除线
        s = Regex("(?is)<(strong|b)>(.*?)</\\1>").replace(s) {
            "**" + decode(stripTags(it.groupValues[2])).trim() + "**"
        }
        s = Regex("(?is)<(em|i)>(.*?)</\\1>").replace(s) {
            "*" + decode(stripTags(it.groupValues[2])).trim() + "*"
        }
        s = Regex("(?is)<(del|s|strike)>(.*?)</\\1>").replace(s) {
            "~~" + decode(stripTags(it.groupValues[2])).trim() + "~~"
        }
        // 6. 标题
        s = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").replace(s) { m ->
            "#".repeat(m.groupValues[1].toInt()) + " " + decode(stripTags(m.groupValues[2])).trim() + "\n\n"
        }
        // 7. 分割线 / 换行
        s = Regex("(?is)<hr[^>]*>").replace(s, "\n\n---\n\n")
        s = Regex("(?is)<br\\s*/?>").replace(s, "\n")
        // 8. 引用（递归处理内部）
        s = Regex("(?is)<blockquote[^>]*>(.*?)</blockquote>").replace(s) { m ->
            val inner = convert(m.groupValues[1]).trim()
            inner.split("\n").joinToString("\n") { "> $it" } + "\n\n"
        }
        // 9. 无序列表
        s = Regex("(?is)<ul[^>]*>(.*?)</ul>").replace(s) { m ->
            Regex("(?is)<li[^>]*>(.*?)</li>").findAll(m.groupValues[1])
                .joinToString("\n") { "- " + oneLine(decode(stripTags(it.groupValues[1]))) } + "\n\n"
        }
        // 10. 有序列表
        s = Regex("(?is)<ol[^>]*>(.*?)</ol>").replace(s) { m ->
            Regex("(?is)<li[^>]*>(.*?)</li>").findAll(m.groupValues[1]).toList()
                .mapIndexed { i, it -> "${i + 1}. " + oneLine(decode(stripTags(it.groupValues[1]))) }
                .joinToString("\n") + "\n\n"
        }
        // 11. 表格
        s = Regex("(?is)<table[^>]*>(.*?)</table>").replace(s) { tableToMd(it.groupValues[1]) }
        // 12. 段落
        s = Regex("(?is)<p[^>]*>(.*?)</p>").replace(s) {
            decode(stripTags(it.groupValues[1])).trim() + "\n\n"
        }

        // 13. 去掉残余标签并反转义
        s = decode(stripTags(s))
        // 14. 规整空白
        s = s.replace(Regex("[ \\t]+\\n"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun tableToMd(inner: String): String {
        val rows = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(inner).toList()
        if (rows.isEmpty()) return ""
        val cellsOf = fun(row: String): List<String> =
            Regex("(?is)<t[hd][^>]*>(.*?)</t[hd]>").findAll(row)
                .map { oneLine(decode(stripTags(it.groupValues[1]))) }.toList()

        val header = cellsOf(rows[0].groupValues[1])
        if (header.isEmpty()) return ""
        val sb = StringBuilder("\n")
        sb.append("| ").append(header.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(header.joinToString(" | ") { "---" }).append(" |\n")
        rows.drop(1).forEach { r ->
            val cells = cellsOf(r.groupValues[1])
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
        }
        return sb.toString()
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun decode(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
