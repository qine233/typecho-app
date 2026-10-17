package noline.zhouzhou.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.data.model.Post
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class FeedRaw(val status: Int, val body: String)

/** HTTP 传输缝，单测里塞一个假的进来就能覆盖全部站点形态与状态码。 */
fun interface FeedTransport {
    suspend fun get(url: String): FeedRaw
}

class OkHttpFeedTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : FeedTransport {
    override suspend fun get(url: String): FeedRaw = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("Accept", "application/rss+xml").get().build())
            .execute().use { response ->
                FeedRaw(response.code, response.body?.string() ?: "")
            }
    }
}

enum class SearchFailure {
    /** 搜索词里有 / 或 :，站点的搜索路由会把这种路径段直接判 404。 */
    KeywordUnusable,
    NoSite,
    Network,
    /** 两种 URL 形态都没拿到 feed：插件关了、站点改版、或者被 WAF 拦了。 */
    NoEndpoint
}

/**
 * 全站搜索。Typecho 的 XML-RPC 里没有 wp.getPosts、也没有任何搜索方法，
 * 唯一的全文检索出口是主题那条公开的搜索结果 RSS：GET <site>/feed/search/<kw>/。
 * 实测硬上限 10 条、没有翻页（见记忆 typecho-search-feed-live）。
 */
class SearchFeedApi(private val transport: FeedTransport = OkHttpFeedTransport()) {

    /** @return 命中的文章；失败时把原因交给上层，由界面决定是退回本地结果还是报错。 */
    suspend fun search(siteUrl: String, keyword: String): Result<List<Post>> {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())
        if (siteUrl.isBlank()) return Result.failure(SearchException(SearchFailure.NoSite, "站点还没配置"))
        val breaking = BREAKING.firstOrNull { trimmed.contains(it) }
        if (breaking != null) {
            return Result.failure(
                SearchException(SearchFailure.KeywordUnusable, "搜索词里有「$breaking」，站点的搜索接口不认")
            )
        }

        val segment = encode(trimmed)
        var lastBody = ""
        for (path in listOf("/feed/search/$segment/", "/index.php/feed/search/$segment/")) {
            val url = siteUrl.trimEnd('/') + path
            val raw = try {
                transport.get(url)
            } catch (e: Exception) {
                continue
            }
            if (raw.status != 200) continue
            lastBody = raw.body
            val items = RssFeedParser.parse(raw.body)
            // 拿到 200 却一个 item 都读不出来，只有两种可能：真没结果，或者那根本不是 feed。
            // 空 feed 的 channel 头一定在，认它就能把「查无此词」和「接口不通」分开。
            if (items.isNotEmpty() || isEmptyFeed(raw.body)) {
                return Result.success(items.mapNotNull { toItemPost(it) })
            }
        }
        return Result.failure(
            SearchException(
                // 两种形态都通了、body 也拿到了，却还是读不出 feed —— 按「没有搜索接口」报，
                // 比谎报「网络不通」更接近用户实际撞上的东西。
                if (lastBody.isBlank()) SearchFailure.Network else SearchFailure.NoEndpoint,
                if (lastBody.isBlank()) "连不上站点，搜索没能发出去"
                else "站点没有提供搜索接口，只在已加载的笔记里找"
            )
        )
    }

    /** 零结果的 feed 只有 channel 头，没有 item。 */
    private fun isEmptyFeed(body: String): Boolean =
        body.contains("<channel>") && body.contains("</channel>") && !body.contains("<item>")

    internal fun encode(keyword: String): String =
        URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")

    /**
     * 结果只给 cid、标题、发布时间和正文。正文是编译好的 HTML，和列表接口同一个形态，
     * 所以塞进 PostCache 之后详情页能直接用，不必再打那个在 PHP 8.4 下 500 的 getPost。
     */
    internal fun toItemPost(item: FeedItem): Post? {
        val cid = cidOf(item.link).ifBlank { cidOf(item.guid) }
        if (cid.isBlank()) return null
        return Post(
            postId = cid,
            title = item.title,
            content = item.content,
            excerpt = item.excerpt,
            dateCreated = parseRfc822(item.pubDate),
            link = item.link,
            categories = item.categories,
            author = item.author,
            commentCount = item.commentCount
        )
    }

    companion object {
        /** 这些字符会让站点的搜索路由整段失配，与其拿一个 404 不如当场说清楚。 */
        private val BREAKING = charArrayOf('/', ':', '#', '?')

        /** 一次查询最多回这么多条，拿满就该怀疑还有结果没显示。 */
        const val ServerCap = 10

        private val Rfc822Pattern = "EEE, dd MMM yyyy HH:mm:ss Z"

        fun cidOf(link: String): String {
            Regex("/archives/(\\d+)").find(link)?.let { return it.groupValues[1] }
            Regex("[?&]cid=(\\d+)").find(link)?.let { return it.groupValues[1] }
            return ""
        }

        /** RSS 的时间是 RFC 822（「Wed, 14 Oct 2026 16:20:25 +0800」），换不成就当时没有时间。 */
        fun parseRfc822(text: String): Long = try {
            // SimpleDateFormat 不线程安全，而两次搜索可能重叠，所以每次现建。
            SimpleDateFormat(Rfc822Pattern, Locale.US).parse(text)?.time?.div(1000) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

class SearchException(val failure: SearchFailure, override val message: String) : Exception(message)
