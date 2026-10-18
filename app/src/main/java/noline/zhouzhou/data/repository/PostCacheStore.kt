package noline.zhouzhou.data.repository

import android.content.Context
import noline.zhouzhou.data.model.CachedFeed
import noline.zhouzhou.data.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 列表接口已经带回完整正文，把这一批结果落盘，冷启动即可先渲染缓存：
 * 首屏不用等网络，站点抽风时也能翻旧文。
 */
class PostCacheStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile: File get() = File(context.filesDir, "posts_cache.json")

    suspend fun load(): List<Post> = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching emptyList()
            json.decodeFromString(CachedFeed.serializer(), cacheFile.readText()).posts
        }.getOrDefault(emptyList())
    }

    /** 只留最近 [KeptLimit] 篇：单篇正文可达数 KB，全存反而拖慢冷启动。 */
    suspend fun save(posts: List<Post>) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = CachedFeed(posts = posts.distinctBy { it.postId }.take(KeptLimit))
            write(payload)
        }
    }

    /**
     * 站点上删掉/改掉了这一篇，盘上的副本得跟着变。
     * 不然下次冷启动把已删的文章读回来，详情页还照它渲染（本站 getPost 是 500，
     * 命中缓存就再也不回源，缓存就是唯一真相）。
     */
    suspend fun remove(postId: String) = mutate { posts ->
        posts.filterNot { it.postId == postId }
    }

    suspend fun put(post: Post) = mutate { posts ->
        // 放最前面：文件只留最近 [KeptLimit] 篇，排到尾部会被截断掉。
        if (post.postId.isBlank()) posts
        else listOf(post) + posts.filterNot { it.postId == post.postId }
    }

    private suspend fun mutate(transform: (List<Post>) -> List<Post>) =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheFile
                if (!file.exists()) return@runCatching
                val current = json.decodeFromString(CachedFeed.serializer(), file.readText()).posts
                write(CachedFeed(posts = transform(current).distinctBy { it.postId }.take(KeptLimit)))
            }
        }

    private fun write(payload: CachedFeed) {
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(json.encodeToString(CachedFeed.serializer(), payload))
        tmp.renameTo(cacheFile)
    }

    companion object {
        private const val KeptLimit = 120
    }
}
