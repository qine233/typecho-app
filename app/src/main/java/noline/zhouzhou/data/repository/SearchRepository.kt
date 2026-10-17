package noline.zhouzhou.data.repository

import kotlinx.coroutines.flow.first
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.data.remote.SearchException
import noline.zhouzhou.data.remote.SearchFailure
import noline.zhouzhou.data.remote.SearchFeedApi

/**
 * 一次搜索的两条来源：站点全文（最多 10 条）+ 本机已加载的笔记。
 * 后者不是装饰 —— 站点那边一次只回 10 条，而首页/日记早就把上百篇正文拉进缓存了，
 * 把这两份并起来，用户才不至于「明明看过却搜不到」。
 */
data class SearchResults(
    val query: String = "",
    val server: List<Post> = emptyList(),
    /** 只有本地命中的那些（已与站点结果按 cid 去重）。 */
    val localOnly: List<Post> = emptyList(),
    val failure: SearchFailure? = null,
    val failureMessage: String = ""
) {
    val all: List<Post> get() = server + localOnly
    val isEmpty: Boolean get() = all.isEmpty()

    /** 站点给满了 10 条，后面多半还有结果它没吐出来。 */
    val probablyCapped: Boolean get() = server.size >= SearchFeedApi.ServerCap
}

class SearchRepository(
    private val api: SearchFeedApi,
    private val settings: SettingsRepository,
    private val postCache: PostCache,
    private val cacheStore: PostCacheStore
) {

    suspend fun search(keyword: String): SearchResults {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return SearchResults(query = trimmed)

        val config = settings.siteConfig.first()
        val outcome = api.search(config.siteUrl, trimmed)
        val server = outcome.getOrDefault(emptyList())
        // 详情页靠 PostCache 取正文（站点的 getPost 在 PHP 8.4 下必 500），
        // 搜出来的这些不塞进去，点开就是一句「文章加载失败」。
        postCache.putAll(server)

        val failure = outcome.exceptionOrNull() as? SearchException
        // 落盘那批可能没进内存缓存（上个进程留下的），一并补进去才点得开。
        val local = combine(server, cacheStore.load(), trimmed)
        postCache.putAll(local)

        return SearchResults(
            query = trimmed,
            server = server,
            localOnly = local,
            failure = failure?.failure,
            failureMessage = failure?.message ?: ""
        )
    }

    companion object {

        /** 站点结果优先，本机只补它没给的那些。 */
        fun combine(server: List<Post>, loaded: List<Post>, term: String): List<Post> {
            val seen = server.map { it.postId }.toSet()
            return loaded.filter { it.postId !in seen && matches(it, term) }
        }

        /**
         * 本地这一路只能拿字符串直接比：正文是编译好的 HTML，标签正好卡在关键词中间时会漏
         * （「Typecho 的&lt;em&gt;搜索&lt;/em&gt;」搜「的搜索」不中）。宁可漏报，也不为每次
         * 搜索把上百篇正文重剥一遍标签。
         */
        fun matches(post: Post, term: String): Boolean {
            if (term.isBlank()) return false
            return post.displayTitle.contains(term, ignoreCase = true) ||
                post.title.contains(term, ignoreCase = true) ||
                post.excerpt.contains(term, ignoreCase = true) ||
                post.content.contains(term, ignoreCase = true)
        }
    }
}
