package noline.zhouzhou.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全部照着 www.zhouzhou.work 的 /feed/search/… 真实响应形状写（见记忆 typecho-search-feed-live）：
 * 属性挂在 content:encoded 上、分类是多个 CDATA、标题在 XML 层又转义了一遍。
 */
class RssFeedParserTest {

    private val twoItems = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
        xmlns:content="http://purl.org/rss/1.0/modules/content/"
        xmlns:dc="http://purl.org/dc/elements/1.1/"
        xmlns:slash="http://purl.org/rss/1.0/modules/slash/">
        <channel>
        <title>团子笔记 - 博客</title>
        <link>https://www.zhouzhou.work/index.php/search/博客/</link>
        <item>
        <title>TYPECHO转Vuepress保留数据，退而求其次的方法</title>
        <link>https://www.zhouzhou.work/index.php/archives/900/</link>
        <guid>https://www.zhouzhou.work/index.php/archives/900/</guid>
        <pubDate>Wed, 14 Oct 2026 16:20:25 +0800</pubDate>
        <dc:creator>粥粥</dc:creator>
        <category><![CDATA[网页编程]]></category>
        <category><![CDATA[闲言碎语]]></category>
        <description><![CDATA[不管在生活的哪个方面，真的总会有人比你更优秀。但是]]></description>
        <content:encoded xml:lang="zh-CN"><![CDATA[
        <p>不管在生活的哪个方面。</p>
        <p>&lt;TEST&gt;</p>
        ]]></content:encoded>
        <slash:comments>3</slash:comments>
        </item>
        <item>
        <title>日记</title>
        <link>https://www.zhouzhou.work/index.php/archives/1082/</link>
        <pubDate>Wed, 14 Oct 2026 16:20:25 +0800</pubDate>
        <dc:creator>粥粥</dc:creator>
        <category><![CDATA[日记]]></category>
        <description><![CDATA[做你自己。]]></description>
        <content:encoded><![CDATA[<p>做你自己。</p>]]></content:encoded>
        <slash:comments>0</slash:comments>
        </item>
        </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `reads every field the site actually emits`() {
        val items = RssFeedParser.parse(twoItems)

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("TYPECHO转Vuepress保留数据，退而求其次的方法", first.title)
        assertEquals("https://www.zhouzhou.work/index.php/archives/900/", first.link)
        assertEquals("粥粥", first.author)
        assertEquals("Wed, 14 Oct 2026 16:20:25 +0800", first.pubDate)
        assertEquals(listOf("网页编程", "闲言碎语"), first.categories)
        assertEquals(3, first.commentCount)
        assertTrue(first.content.contains("不管在生活的哪个方面。"))
    }

    @Test
    fun `decodes exactly one XML layer so displayTitle still works`() {
        val content = RssFeedParser.parse(twoItems).first().content
        // CDATA 里那层 &amp;lt; 不该被二次展开：Post.displayTitle 还要再解一遍。
        assertTrue(content.contains("&lt;TEST&gt;"))
    }

    @Test
    fun `missing comments and guid default cleanly`() {
        val second = RssFeedParser.parse(twoItems)[1]
        assertEquals(0, second.commentCount)
        assertEquals("", second.guid)
    }

    @Test
    fun `zero hits is an empty list, not an error`() {
        val empty = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
            <title>团子笔记 - zzzznotakeyword</title>
            <lastBuildDate>Thu, 01 Jan 1970 08:00:00 +0800</lastBuildDate>
            </channel></rss>
        """.trimIndent()

        assertTrue(RssFeedParser.parse(empty).isEmpty())
    }

    @Test
    fun `php warnings before the xml are skipped`() {
        val polluted = "<br /><b>Deprecated</b>: xml_set_object() <br /><?xml version=\"1.0\"?>\n" +
            "<rss version=\"2.0\"><channel><item>" +
            "<title>带前缀</title><link>https://www.zhouzhou.work/index.php/archives/7/</link>" +
            "</item></channel></rss>"

        val items = RssFeedParser.parse(polluted)
        assertEquals(1, items.size)
        assertEquals("带前缀", items[0].title)
    }

    @Test
    fun `a response cut mid-item keeps the whole ones`() {
        val cut = twoItems.substringBeforeLast("</item>", "") + "</it"
        val items = RssFeedParser.parse(cut)

        // 第一条读到了收尾标签，第二条没有 —— 半截 link 上的 cid 可能指向另一篇文章，只能整条丢掉。
        assertEquals(1, items.size)
        assertEquals("900", SearchFeedApi.cidOf(items[0].link))
    }

    @Test
    fun `garbage that is not a feed yields nothing instead of crashing`() {
        assertTrue(RssFeedParser.parse("<html><body>500 Internal Server Error</body></html>").isEmpty())
        assertTrue(RssFeedParser.parse("").isEmpty())
    }
}
