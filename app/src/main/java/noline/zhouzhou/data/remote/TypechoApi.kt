package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TypechoApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val xmlRpc: XmlRpcClient = XmlRpcClient()
) {

    private val xmlMediaType = "text/xml; charset=utf-8".toMediaType()

    private suspend fun call(
        url: String, methodName: String, params: List<Any?>, page: Int = 0
    ): XmlRpcValue = withContext(Dispatchers.IO) {
        val body = xmlRpc.buildRequest(methodName, params)
        val request = Request.Builder()
            .url(withPage(url, page))
            .post(body.toRequestBody(xmlMediaType))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw XmlRpcException("HTTP ${response.code}: ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw XmlRpcException("Empty response")
        xmlRpc.parseResponse(responseBody)
    }

    // ── Connection Test ──

    suspend fun testConnection(url: String): Boolean {
        return try {
            call(url, "demo.sayHello", emptyList())
            true
        } catch (e: XmlRpcFault) {
            // Typecho does not implement demo.sayHello (fault -32601); a
            // well-formed fault still proves the endpoint speaks XML-RPC.
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Authentication ──

    suspend fun login(url: String, username: String, password: String): BlogInfo {
        val result = call(url, "blogger.getUsersBlogs", listOf("", username, password))
        val blogs = result.asArray()
        if (blogs.isEmpty()) throw XmlRpcException("No blogs found for this user")

        val blog = blogs[0].asStruct()
        return BlogInfo(
            blogId = (blog["blogid"] ?: blog["blogId"])?.asString() ?: "1",
            blogName = (blog["blogName"] ?: blog["blog_name"])?.asString() ?: "",
            url = (blog["url"] ?: blog["xmlrpc"])?.asString() ?: "",
            xmlrpcUrl = url,
            isAdmin = (blog["isAdmin"] ?: blog["is_admin"])?.asBoolean() ?: false
        )
    }

    // ── Posts ──

    suspend fun getRecentPosts(
        url: String, blogId: String, username: String, password: String,
        count: Int, page: Int = 0
    ): List<Post> {
        val result = call(
            url, "metaWeblog.getRecentPosts",
            listOf(blogId, username, password, count), page
        )
        return result.asArray().map { parsePost(it) }.filter { it.postId.isNotBlank() }
    }

    suspend fun getPost(
        url: String, postId: String, username: String, password: String
    ): Post {
        val result = call(url, "metaWeblog.getPost", listOf(postId, username, password))
        return parsePost(result)
    }

    suspend fun newPost(
        url: String, blogId: String, username: String, password: String,
        post: Post, publish: Boolean, isPage: Boolean = false
    ): String {
        val struct = buildPostStruct(post, isPage)
        val result = call(url, "metaWeblog.newPost", listOf(blogId, username, password, struct, publish))
        return result.asString()
    }

    suspend fun editPost(
        url: String, postId: String, username: String, password: String,
        post: Post, publish: Boolean, isPage: Boolean = false
    ): Boolean {
        val struct = buildPostStruct(post, isPage)
        call(url, "metaWeblog.editPost", listOf(postId, username, password, struct, publish))
        return true
    }

    suspend fun deletePost(
        url: String, blogId: String, postId: String, username: String, password: String
    ): Boolean {
        call(url, "blogger.deletePost", listOf(blogId, postId, username, password, true))
        return true
    }

    // ── Categories ──

    suspend fun getCategories(
        url: String, blogId: String, username: String, password: String
    ): List<Category> {
        val result = call(url, "metaWeblog.getCategories", listOf(blogId, username, password))
        return result.asArray().map { item ->
            val s = item.asStruct()
            // Typecho 的 mw.getCategories 里 description 放的是分类名，真正的描述在 categoryDescription；
            // 也不返回 slug，别名只能从 htmlUrl（/category/tech/）里截。
            val htmlUrl = (s["htmlUrl"] ?: s["html_url"])?.asString() ?: ""
            Category(
                categoryId = (s["categoryId"] ?: s["categoryid"])?.asString() ?: "",
                name = (s["title"] ?: s["categoryName"] ?: s["name"])?.asString() ?: "",
                slug = (s["slug"] ?: s["categorySlug"])?.asString()
                    ?: slugFromHtmlUrl(htmlUrl),
                parentId = (s["parentId"] ?: s["parent_id"])?.asString() ?: "0",
                description = (s["categoryDescription"] ?: s["catDescription"])?.asString() ?: "",
                url = htmlUrl,
                postCount = (s["count"] ?: s["post_count"])?.asInt() ?: 0
            )
        }
    }

    /** 新建分类。wp.newCategory 的 struct 只需要 name，slug/parent/description 缺省时站点自己生成。 */
    suspend fun newCategory(
        url: String, blogId: String, username: String, password: String,
        name: String, slug: String, parentId: String, description: String
    ): String {
        val struct = mutableMapOf<String, Any?>("name" to name)
        if (slug.isNotBlank()) struct["slug"] = slug
        if (description.isNotBlank()) struct["description"] = description
        struct["parent"] = parentId.toIntOrNull() ?: 0
        val result = call(url, "wp.newCategory", listOf(blogId, username, password, struct))
        return result.asString()
    }

    suspend fun deleteCategory(
        url: String, blogId: String, categoryId: String, username: String, password: String
    ): Boolean {
        // 传错 ID 会被 PHP  coerce 成 0，宁可在本地直接失败也别删到别的分类。
        val mid = categoryId.toIntOrNull()
            ?: throw XmlRpcException("分类 ID 不是数字：$categoryId")
        call(url, "wp.deleteCategory", listOf(blogId, username, password, mid))
        return true
    }

    // ── Comments (WordPress XML-RPC extensions) ──

    suspend fun getComments(
        url: String, blogId: String, username: String, password: String,
        postId: String? = null, status: String? = null, number: Int = 50, offset: Int = 0
    ): List<Comment> {
        val filter = mutableMapOf<String, Any?>(
            "number" to number,
            "offset" to offset
        )
        if (postId != null) filter["post_id"] = postId
        if (status != null) filter["status"] = status

        // Typecho signature: wp.getComments(blogId, userName, password, struct)
        val result = call(url, "wp.getComments", listOf(blogId, username, password, filter))
        return result.asArray().map { parseComment(it) }
    }

    suspend fun newComment(
        url: String, blogId: String, username: String, password: String,
        postId: String, content: String, parentCommentId: String? = null
    ): String {
        val struct = mutableMapOf<String, Any?>(
            "comment_parent" to (parentCommentId ?: "0"),
            "content" to content
        )
        // Typecho attributes the comment to the authenticated user automatically.
        val result = call(url, "wp.newComment", listOf(blogId, username, password, postId, struct))
        return result.asString()
    }

    suspend fun deleteComment(
        url: String, blogId: String, commentId: String, username: String, password: String
    ): Boolean {
        call(url, "wp.deleteComment", listOf(blogId, username, password, commentId))
        return true
    }

    suspend fun editComment(
        url: String, blogId: String, commentId: String, username: String, password: String,
        status: String? = null, content: String? = null
    ): Boolean {
        val struct = mutableMapOf<String, Any?>()
        if (status != null) struct["status"] = status
        if (content != null) struct["content"] = content
        call(url, "wp.editComment", listOf(blogId, username, password, commentId, struct))
        return true
    }

    // ── Pages ──

    suspend fun getPages(
        url: String, blogId: String, username: String, password: String
    ): List<Post> {
        val result = call(url, "wp.getPages", listOf(blogId, username, password))
        return result.asArray().map { parsePost(it) }.filter { it.postId.isNotBlank() }
    }

    /** 单篇页面走 wp.getPage（metaWeblog.getPost 只认文章，站点上还会 500）。 */
    suspend fun getPage(
        url: String, blogId: String, pageId: String, username: String, password: String
    ): Post {
        val id = pageId.toIntOrNull()
            ?: throw XmlRpcException("页面 ID 不是数字：$pageId")
        val result = call(url, "wp.getPage", listOf(blogId, id, username, password))
        return parsePost(result)
    }

    suspend fun deletePage(
        url: String, blogId: String, pageId: String, username: String, password: String
    ): Boolean {
        val id = pageId.toIntOrNull()
            ?: throw XmlRpcException("页面 ID 不是数字：$pageId")
        call(url, "wp.deletePage", listOf(blogId, username, password, id))
        return true
    }

    // ── Media ──

    /**
     * Typecho 的 metaWeblog.newMediaObject 走 Widget_Upload::uploadHandle()，
     * 它把 bits 原样 file_put_contents 落盘（IXR 解析时已 base64_decode），
     * 所以这里传 Base64Value，由客户端编码。返回体只有 file / url 两个字段。
     */
    suspend fun uploadMedia(
        url: String, blogId: String, username: String, password: String,
        name: String, mimeType: String, bytes: ByteArray
    ): MediaRef {
        val data = mapOf<String, Any?>(
            "name" to name,
            "type" to mimeType,
            "bits" to XmlRpcValue.Base64Value(bytes)
        )
        val result = call(url, "metaWeblog.newMediaObject", listOf(blogId, username, password, data))
        val s = result.asStruct()
        return MediaRef(
            name = (s["file"])?.asString() ?: name,
            url = (s["url"] ?: s["file"])?.asString() ?: ""
        )
    }

    // ── Parsing helpers ──

    private fun parsePost(value: XmlRpcValue): Post {
        val s = value.asStruct()
        return Post(
            postId = (s["postid"] ?: s["postId"] ?: s["post_id"] ?: s["page_id"])?.asString() ?: "",
            title = (s["title"])?.asString() ?: "",
            content = joinBody(s["description"] ?: s["content"], s["mt_text_more"] ?: s["text_more"]),
            excerpt = (s["mt_excerpt"] ?: s["excerpt"])?.asString() ?: "",
            dateCreated = parseDate(s["dateCreated"] ?: s["date_created_gmt"] ?: s["pubDate"]),
            link = (s["link"] ?: s["permaLink"] ?: s["permalink"] ?: s["uri"])?.asString() ?: "",
            categories = (s["categories"] ?: s["category"])?.let { cat ->
                when (cat) {
                    // 页面的 categories 是结构体数组，asString 取不到名字，过滤掉空串就行。
                    is XmlRpcValue.ArrayValue -> cat.value.map { it.asString() }.filter { it.isNotBlank() }
                    is XmlRpcValue.StringValue -> listOf(cat.value)
                    else -> emptyList()
                }
            } ?: emptyList(),
            tags = (s["mt_keywords"] ?: s["tags"])?.let { t ->
                when (t) {
                    is XmlRpcValue.StringValue -> t.value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    is XmlRpcValue.ArrayValue -> t.value.map { it.asString() }
                    else -> emptyList()
                }
            } ?: emptyList(),
            status = (s["post_status"] ?: s["postStatus"] ?: s["status"] ?: s["page_status"])?.asString() ?: "publish",
            author = (s["wp_author_display_name"] ?: s["author"] ?: s["dc:creator"])?.asString() ?: "",
            slug = (s["wp_slug"] ?: s["slug"])?.asString() ?: "",
            commentCount = (s["comment_count"] ?: s["comments"])?.asInt() ?: 0,
            allowComment = (s["mt_allow_comments"] ?: s["allowComment"])?.asInt()?.let { it == 1 } ?: true,
            isMarkdown = true
        )
    }

    private fun parseComment(value: XmlRpcValue): Comment {
        val s = value.asStruct()
        return Comment(
            commentId = (s["comment_id"] ?: s["commentId"])?.asString() ?: "",
            postId = (s["post_id"] ?: s["postId"])?.asString() ?: "",
            author = (s["author"] ?: s["comment_author"])?.asString() ?: "",
            authorEmail = (s["author_email"] ?: s["comment_author_email"])?.asString() ?: "",
            authorUrl = (s["author_url"] ?: s["comment_author_url"])?.asString() ?: "",
            content = (s["content"] ?: s["comment_content"])?.asString() ?: "",
            dateCreated = parseDate(s["date_created_gmt"] ?: s["dateCreated"] ?: s["comment_date"]),
            status = (s["status"] ?: s["comment_status"])?.asString() ?: "approve",
            parentId = (s["parent"] ?: s["comment_parent"])?.asString() ?: "0",
            type = (s["type"] ?: s["comment_type"])?.asString() ?: "comment"
        )
    }

    private fun parseDate(value: XmlRpcValue?): Long {
        if (value == null) return 0L
        return when (value) {
            is XmlRpcValue.IntValue -> value.value
            is XmlRpcValue.DateTimeValue -> parseIso8601(value.value)
            is XmlRpcValue.StringValue -> value.value.toLongOrNull() ?: parseIso8601(value.value)
            else -> 0L
        }
    }

    private fun parseIso8601(text: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyyMMdd'T'HH:mm:ss", java.util.Locale.US)
            format.parse(text)?.time?.div(1000) ?: 0L
        } catch (e: Exception) {
            try {
                val format2 = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                format2.parse(text)?.time?.div(1000) ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /**
     * post_type=page 让 Typecho 走 PageEdit 而不是 PostEdit，
     * 状态键也随类型换名：mwNewPost 读的是 {$type}_status，页面必须写 page_status。
     */
    private fun buildPostStruct(post: Post, isPage: Boolean): Map<String, Any?> {
        val struct = mutableMapOf<String, Any?>(
            "title" to post.title,
            "description" to post.content,
            "mt_excerpt" to post.excerpt,
            "mt_keywords" to post.tags.joinToString(","),
            "categories" to post.categories,
            (if (isPage) "page_status" else "post_status") to post.status
        )
        if (isPage) struct["post_type"] = "page"
        if (post.link.isNotBlank()) struct["link"] = post.link
        return struct
    }

    /**
     * Typecho 的管理列表组件都按 request 里的 page 参数翻页（AdminTrait::initPage），
     * metaWeblog.getRecentPosts 内部就是 PostAdmin，所以页码挂在 URL 上即可，
     * 不必用「更大的 count 重新拉一遍」这种浪费流量的方式。
     */
    private fun withPage(url: String, page: Int): String =
        if (page <= 0) url
        else url + if (url.contains('?')) "&page=$page" else "?page=$page"

    /** 中文别名的分类，htmlUrl 里是百分号编码，直接截出来会给用户看一串乱码。 */
    private fun slugFromHtmlUrl(htmlUrl: String): String {
        val raw = htmlUrl.substringAfterLast("/category/").substringBefore("/")
        if (!raw.contains('%')) return raw
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: IllegalArgumentException) {
            raw
        }
    }

    /**
     * Typecho 把正文按 <!--more--> 拆成 description 与 mt_text_more 两段返回
     * （XmlRpc::getPostExtended），只取前者会丢掉「更」之后的全部内容。
     * 这里按核心的拼回方式还原，编辑再保存时分隔符也还在。
     */
    private fun joinBody(main: XmlRpcValue?, more: XmlRpcValue?): String {
        val head = main?.asString() ?: ""
        val tail = more?.asString() ?: ""
        return when {
            tail.isBlank() -> head
            head.isBlank() -> tail
            else -> "$head\n<!--more-->\n$tail"
        }
    }
}

class XmlRpcException(message: String, cause: Throwable? = null) : Exception(message, cause)
