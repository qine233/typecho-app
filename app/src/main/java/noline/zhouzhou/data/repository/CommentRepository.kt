package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.*
import noline.zhouzhou.data.remote.TypechoApi

class CommentRepository(private val api: TypechoApi) {

    suspend fun getComments(
        config: SiteConfig,
        postId: String? = null,
        status: String? = null,
        number: Int = 50,
        offset: Int = 0
    ): Result<List<Comment>> {
        return try {
            val comments = api.getComments(
                config.fullXmlrpcUrl, config.blogId, config.username, config.password,
                postId, status, number, offset
            )
            Result.success(comments)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun newComment(
        config: SiteConfig,
        postId: String,
        content: String,
        parentCommentId: String? = null
    ): Result<String> {
        return try {
            val id = api.newComment(
                config.fullXmlrpcUrl, config.blogId, config.username, config.password,
                postId, content, parentCommentId
            )
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteComment(config: SiteConfig, commentId: String): Result<Boolean> {
        return try {
            api.deleteComment(config.fullXmlrpcUrl, config.blogId, commentId, config.username, config.password)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editCommentStatus(config: SiteConfig, commentId: String, status: String): Result<Boolean> {
        return try {
            api.editComment(
                config.fullXmlrpcUrl, config.blogId, commentId, config.username, config.password,
                status = status
            )
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
