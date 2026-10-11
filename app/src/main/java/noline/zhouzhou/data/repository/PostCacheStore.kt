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
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedFeed.serializer(), payload))
            tmp.renameTo(cacheFile)
        }
    }

    companion object {
        private const val KeptLimit = 120
    }
}
