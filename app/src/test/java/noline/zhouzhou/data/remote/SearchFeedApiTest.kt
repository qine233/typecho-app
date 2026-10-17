package noline.zhouzhou.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关键词、URL 形态、状态码这些规矩全部对着 www.zhouzhou.work 的实测结果来（记忆 typecho-search-feed-live）。 */
class SearchFeedApiTest {

    private class FakeTransport(
        private val replies: Map<String, FeedRaw>,
        private val throwers: Set<String> = emptySet()
    ) : FeedTransport {
        val requested = mutableListOf<String>()

        override suspend fun get(url: String): FeedRaw {
            requested.add(url)
            if (url in throwers) throw java.io.IOException("connection reset")
            return replies[url] ?: FeedRaw(404, "")
        }
    }

    private fun item(cid: Int, title: String = "标题$cid") =
        "<item>" +
            "<title>$title</title>" +
            "<link>https://www.zhouzhou.work/index.php/archives/$cid/</link>" +
            "<pubDate>Wed, 14 Oct 2026 16:20:25 +0800</pubDate>" +
            "<dc:creator>粥粥</dc:creator>" +
            "<category><![CDATA[网页编程]]></category>" +
            "<description><![CDATA[摘要]]></description>" +
            "<content:encoded><![CDATA[<p>正文</p>]]></content:encoded>" +
            "<slash:comments>2</slash:comments>" +
            "</item>"

    private fun feed(items: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
            "<title>团子笔记 - 博客</title>$items</channel></rss>"

    private val rewritten = "https://www.zhouzhou.work/feed/search/%E5%8D%9A%E5%AE%A2/"
    private val indexed = "https://www.zhouzhou.work/index.php/feed/search/%E5%8D%9A%E5%AE%A2/"
    private val site = "https://www.zhouzhou.work"

    private fun api(transport: FeedTransport) = SearchFeedApi(transport)

    @Test
    fun `asks the rewritten path first and never touches the fallback`() = runBlocking {
        val transport = FakeTransport(mapOf(rewritten to FeedRaw(200, feed(item(900)))))

        val posts = api(transport).search(site, "博客").getOrThrow()

        assertEquals(listOf(rewritten), transport.requested)
        assertEquals(listOf("900"), posts.map { it.postId })
    }

    @Test
    fun `falls back to the index path when the rewrite is 404`() = runBlocking {
        val transport = FakeTransport(mapOf(indexed to FeedRaw(200, feed(item(1)))))

        val posts = api(transport).search(site, "博客").getOrThrow()

        assertEquals(listOf(rewritten, indexed), transport.requested)
        assertEquals(listOf("1"), posts.map { it.postId })
    }

    @Test
    fun `keywords are percent-encoded into one path segment`() {
        val searchApi = api(FakeTransport(emptyMap()))

        assertEquals("%E5%8D%9A%E5%AE%A2", searchApi.encode("博客"))
        // 空格必须留成 %20：写成 + 会被站点的搜索路由当成分隔，「C++」那种词会被劈掉后半截。
        assertEquals("hello%20world", searchApi.encode("hello world"))
    }

    @Test
    fun `a keyword with a slash is refused before any request`() = runBlocking {
        val transport = FakeTransport(emptyMap())

        val failure = failureOf(api(transport).search(site, "a/b"))

        assertEquals(SearchFailure.KeywordUnusable, failure)
        assertTrue(transport.requested.isEmpty())
    }

    @Test
    fun `no hits is a success with an empty list`() = runBlocking {
        val transport = FakeTransport(mapOf(rewritten to FeedRaw(200, feed(""))))

        assertTrue(api(transport).search(site, "博客").getOrThrow().isEmpty())
    }

    @Test
    fun `a 200 that is not a feed is reported as no endpoint, not as no results`() = runBlocking {
        val body = "<html><body>Search results</body></html>"
        val transport = FakeTransport(
            mapOf(rewritten to FeedRaw(200, body), indexed to FeedRaw(200, body))
        )

        assertEquals(SearchFailure.NoEndpoint, failureOf(api(transport).search(site, "博客")))
    }

    @Test
    fun `transport blowing up on both forms is a network failure`() = runBlocking {
        val transport = FakeTransport(emptyMap(), throwers = setOf(rewritten, indexed))

        assertEquals(SearchFailure.Network, failureOf(api(transport).search(site, "博客")))
    }

    @Test
    fun `blank site and blank keyword need no request`() = runBlocking {
        val transport = FakeTransport(emptyMap())

        assertEquals(SearchFailure.NoSite, failureOf(api(transport).search("", "博客")))
        assertTrue(api(transport).search(site, "   ").getOrThrow().isEmpty())
        assertTrue(transport.requested.isEmpty())
    }

    @Test
    fun `items without an archive link are dropped because they cannot be opened`() = runBlocking {
        val transport = FakeTransport(
            mapOf(rewritten to FeedRaw(
                200,
                feed(item(5) + "<item><title>没有链接</title><description>x</description></item>")
            ))
        )

        val posts = api(transport).search(site, "博客").getOrThrow()

        assertEquals(listOf("5"), posts.map { it.postId })
    }

    @Test
    fun `maps a feed item onto the shape the detail page reads`() {
        val parsed = RssFeedParser.parse(feed(item(900, "TYPECHO 的那些事"))).first()

        val post = api(FakeTransport(emptyMap())).toItemPost(parsed)!!

        assertEquals("900", post.postId)
        assertEquals("TYPECHO 的那些事", post.title)
        assertEquals("<p>正文</p>", post.content)
        assertEquals("摘要", post.excerpt)
        assertEquals("粥粥", post.author)
        assertEquals(2, post.commentCount)
        assertEquals(listOf("网页编程"), post.categories)
        // Wed, 14 Oct 2026 16:20:25 +0800 = 08:20:25 UTC
        assertEquals(1791966025L, post.dateCreated)
    }

    @Test
    fun `an unparseable pubDate leaves zero rather than a wrong day`() {
        val parsed = RssFeedParser.parse(
            feed("<item><title>x</title>" +
                "<link>https://www.zhouzhou.work/index.php/archives/9/</link>" +
                "<pubDate>not a date</pubDate></item>")
        ).first()

        assertEquals(0L, api(FakeTransport(emptyMap())).toItemPost(parsed)!!.dateCreated)
    }

    @Test
    fun `cidOf understands the link shapes the site uses`() {
        assertEquals("1082", SearchFeedApi.cidOf("https://www.zhouzhou.work/index.php/archives/1082/"))
        assertEquals("7", SearchFeedApi.cidOf("https://www.zhouzhou.work/archives/7.html"))
        assertEquals("12", SearchFeedApi.cidOf("https://www.zhouzhou.work/?page_type=1&cid=12"))
        assertEquals("", SearchFeedApi.cidOf("https://www.zhouzhou.work/about/"))
    }

    private suspend fun failureOf(outcome: Result<List<noline.zhouzhou.data.model.Post>>): SearchFailure =
        (outcome.exceptionOrNull() as SearchException).failure
}
