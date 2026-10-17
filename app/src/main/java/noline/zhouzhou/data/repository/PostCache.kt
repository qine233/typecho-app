package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.Post
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存缓存列表接口已返回的完整文章。
 * 站点在 PHP 8.4 下 metaWeblog.getPost 会 500，而 getRecentPosts 已带完整正文，
 * 因此详情/编辑优先用缓存，避免再次请求 getPost。
 */
class PostCache {
    private val map = ConcurrentHashMap<String, Post>()

    fun putAll(posts: List<Post>) {
        posts.forEach { if (it.postId.isNotBlank()) map[it.postId] = it }
    }

    fun put(post: Post) {
        if (post.postId.isNotBlank()) map[post.postId] = post
    }

    fun get(postId: String): Post? = map[postId]

    fun remove(postId: String) {
        map.remove(postId)
    }

    fun clear() {
        map.clear()
    }
}
