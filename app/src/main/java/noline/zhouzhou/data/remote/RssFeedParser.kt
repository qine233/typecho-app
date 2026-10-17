package noline.zhouzhou.data.remote

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** 一条 item 的原始字段，时间留着字符串形态，好让单测直接比对解析结果。 */
data class FeedItem(
    val title: String = "",
    val link: String = "",
    val guid: String = "",
    val pubDate: String = "",
    val author: String = "",
    val categories: List<String> = emptyList(),
    val excerpt: String = "",
    val content: String = "",
    val commentCount: Int = 0
)

/**
 * 站点搜索的唯一出口是公开的结果 RSS（见记忆 typecho-search-feed-live），
 * 所以这里只求「把 item 里那几个字段读出来」，不按 RSS 规范建模。
 *
 * 容错取向和 XML-RPC 那边一致：读到一半崩了就把已经读完的 item 交回去，
 * 宁可得不全，也别整页报错。
 */
object RssFeedParser {

    private val Roots = arrayOf("<rss", "<rdf", "<feed")

    fun parse(body: String): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(sanitize(body)))

        var event = parser.eventType
        try {
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    readItem(parser)?.let { items.add(it) }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // 截断或非法 XML：上面收到的那些就是全部了。
        }
        return items
    }

    /** 站点若把 PHP 告警印在 XML 前面，从第一个真正的 XML 起点读起。 */
    private fun sanitize(body: String): String {
        val text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val decl = text.indexOf("<?xml")
        if (decl >= 0) return text.substring(decl)
        val root = Roots.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return text
        return text.substring(root)
    }

    /** 调用时 parser 正停在 item 的 START_TAG 上，返回时停在它的 END_TAG 之后。 */
    private fun readItem(parser: XmlPullParser): FeedItem? {
        val categories = mutableListOf<String>()
        var title = ""
        var link = ""
        var guid = ""
        var pubDate = ""
        var author = ""
        var excerpt = ""
        var content = ""
        var comments = 0
        // 截断的响应会少掉尾部若干字节，而 link 里的 cid 正好可能被切在半截上
        // （archives/1082 只剩 archives/108 就会认成另一篇）。只有读到收尾标签的 item 才算数。
        var completed = false

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                val text = readElement(parser, name)
                when (name) {
                    "title" -> title = text
                    "link" -> link = text
                    "guid" -> guid = text
                    "pubDate" -> pubDate = text
                    "dc:creator" -> author = text
                    "category" -> if (text.isNotBlank()) categories.add(text)
                    "description" -> excerpt = text
                    "content:encoded" -> content = text
                    "slash:comments" -> comments = text.toIntOrNull() ?: 0
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                completed = true
                break
            }
            event = parser.next()
        }

        if (!completed || link.isBlank()) return null
        return FeedItem(
            title = title,
            link = link,
            guid = guid,
            pubDate = pubDate,
            author = author,
            categories = categories,
            excerpt = excerpt,
            content = content.ifBlank { excerpt },
            commentCount = comments
        )
    }

    /**
     * 不用 nextText()：它在元素里夹了子标签时直接抛，一条脏 item 会连带毁掉整份结果。
     * 这里把元素内的文本全拼起来，子标签原样略过。
     */
    private fun readElement(parser: XmlPullParser, name: String): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) {
                sb.append(parser.text ?: "")
            } else if (event == XmlPullParser.END_TAG && parser.name == name) {
                break
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }
}
