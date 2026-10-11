package com.typecho.app.data.repository

import com.typecho.app.data.model.*
import com.typecho.app.data.remote.TypechoApi

class PostRepository(private val api: TypechoApi) {

    suspend fun getRecentPosts(
        config: SiteConfig, count: Int = 20, page: Int = 0
    ): Result<List<Post>> {
        return try {
            val posts = api.getRecentPosts(
                config.fullXmlrpcUrl, config.blogId, config.username, config.password, count, page
            )
            Result.success(fillShortPage(config, posts, count, page))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * PHP 8.4 的告警会挤掉响应尾部，页尾总有 1~3 篇解析不出来（实测 pageSize=20 常返回 17 篇）。
     * 缺的只能是本页最后几篇，所以按全局序号单独补取：单篇响应约 2KB，不会再被截断，
     * 顺带把被切成半截的那篇正文换回完整版。
     */
    private suspend fun fillShortPage(
        config: SiteConfig, posts: List<Post>, count: Int, page: Int
    ): List<Post> {
        if (page <= 0 || posts.size >= count) return posts
        var result = posts
        var index = (page - 1) * count + posts.size + 1
        val lastIndex = (page - 1) * count + count
        var attempts = 0
        while (index <= lastIndex && attempts < MaxGapFill) {
            attempts++
            val extra = try {
                api.getRecentPosts(
                    config.fullXmlrpcUrl, config.blogId, config.username, config.password, 1, index
                ).firstOrNull()
            } catch (e: Exception) {
                null
            } ?: break

            val existing = result.indexOfFirst { it.postId == extra.postId }
            result = if (existing >= 0) {
                result.toMutableList().also { it[existing] = extra }
            } else {
                result + extra
            }
            index++
        }
        return result
    }

    /**
     * 逐页取站点文章，直到取满 maxPosts 或碰到短页。
     * 管理后台和日记时间线要的是全量，单页请求太大反而会被 PHP 告警截掉尾巴。
     */
    suspend fun getAllPosts(
        config: SiteConfig, pageSize: Int = 50, maxPosts: Int = 400
    ): Result<List<Post>> {
        return try {
            val all = mutableListOf<Post>()
            var page = 1
            while (all.size < maxPosts) {
                val posts = api.getRecentPosts(
                    config.fullXmlrpcUrl, config.blogId, config.username, config.password,
                    pageSize, page
                )
                val filled = fillShortPage(config, posts, pageSize, page)
                all += filled.filter { p -> all.none { it.postId == p.postId } }
                if (filled.size < pageSize) break
                page++
            }
            Result.success(all.take(maxPosts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPost(config: SiteConfig, postId: String): Result<Post> {
        return try {
            val post = api.getPost(config.fullXmlrpcUrl, postId, config.username, config.password)
            Result.success(post)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPost(config: SiteConfig, post: Post, publish: Boolean): Result<String> {
        return try {
            val id = api.newPost(
                config.fullXmlrpcUrl, config.blogId, config.username, config.password, post, publish
            )
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePost(config: SiteConfig, postId: String, post: Post, publish: Boolean): Result<Boolean> {
        return try {
            api.editPost(config.fullXmlrpcUrl, postId, config.username, config.password, post, publish)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePost(config: SiteConfig, postId: String): Result<Boolean> {
        return try {
            api.deletePost(config.fullXmlrpcUrl, config.blogId, postId, config.username, config.password)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategories(config: SiteConfig): Result<List<Category>> {
        return try {
            val cats = api.getCategories(config.fullXmlrpcUrl, config.blogId, config.username, config.password)
            Result.success(cats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPages(config: SiteConfig): Result<List<Post>> {
        return try {
            val pages = api.getPages(config.fullXmlrpcUrl, config.blogId, config.username, config.password)
            Result.success(pages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 上传图片附件，返回可直接写进 Markdown 的图片地址。 */
    suspend fun uploadImage(
        config: SiteConfig, name: String, mimeType: String, bytes: ByteArray
    ): Result<String> {
        return try {
            val media = api.uploadMedia(
                config.fullXmlrpcUrl, config.blogId, config.username, config.password,
                name, mimeType, bytes
            )
            val url = resolveUrl(config, media.url)
            if (url.isBlank()) Result.failure(IllegalStateException("站点没有返回图片地址"))
            else Result.success(url)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 部分站点返回 /upload/xxx.jpg 这样的相对路径，拼上站点根地址才能给客户端渲染。 */
    private fun resolveUrl(config: SiteConfig, url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = config.siteUrl.trimEnd('/')
        val path = if (url.startsWith("/")) url else "/$url"
        return "$base$path"
    }

    private companion object {
        /** 单页最多补取几篇，避免异常情况下串行请求太多轮。 */
        const val MaxGapFill = 4
    }
}
