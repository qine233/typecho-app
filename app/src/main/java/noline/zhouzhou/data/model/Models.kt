package noline.zhouzhou.data.model

import kotlinx.serialization.Serializable
import noline.zhouzhou.util.DateUtils

@Serializable
data class Post(
    val postId: String = "",
    val title: String = "",
    val content: String = "",
    val excerpt: String = "",
    val dateCreated: Long = 0L,
    val link: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: String = "publish",
    val author: String = "",
    val slug: String = "",
    val commentCount: Int = 0,
    val allowComment: Boolean = true,
    val isMarkdown: Boolean = true
) {
    val displayDate: String
        get() = DateUtils.formatDate(dateCreated)

    /** Typecho 存标题时会转义，按纯文本渲染前要先还原，否则会出现 &lt;TEST&gt;。 */
    val displayTitle: String
        get() = title.decodeEntities()

    val plainExcerpt: String
        get() = (if (excerpt.isNotBlank()) excerpt else content)
            .stripHtml()
            .decodeEntities()
            .take(200)

    /** 属于「日记」分类的文章：在首页隐藏，改为在「日记动态」时间线展示。 */
    val isDiary: Boolean
        get() = categories.any { it.trim() == DIARY_CATEGORY }

    /** 日记标题常常是占位符（未命名文档 / 日记 / 空），此时正文优先、弱化标题。 */
    val hasMeaningfulTitle: Boolean
        get() = title.isNotBlank() && title.trim() !in PLACEHOLDER_TITLES

    /** 正文里的首图，用于动态卡片的配图展示。 */
    val firstImage: String?
        get() = feedImages.firstOrNull()

    /** 正文里的配图，用于详情轮播；上限防止长文里几十张图拖垮列表。 */
    val feedImages: List<String>
        get() {
            val origin = siteOrigin
            return Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .findAll(content)
                .map { it.groupValues[1].replace("&amp;", "&").trim() }
                .map { resolveImage(it, origin) }
                .filter { it.isNotBlank() }
                .take(9)
                .toList()
        }

    /** 文章链接的 scheme://host，用于把正文里的相对图片地址补全。 */
    private val siteOrigin: String
        get() = Regex("^(https?://[^/]+)").find(link)?.groupValues?.get(1) ?: ""

    private fun resolveImage(src: String, origin: String): String = when {
        src.startsWith("data:") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("http://") || src.startsWith("https://") -> src
        origin.isEmpty() -> ""
        src.startsWith("/") -> origin + src
        src.startsWith("./") -> origin + src.removePrefix(".")
        else -> "$origin/$src"
    }

    private fun String.stripHtml(): String =
        this.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()

    companion object {
        const val DIARY_CATEGORY = "日记"
        private val PLACEHOLDER_TITLES = setOf("日记", "未命名文档", "未命名", "无标题", "untitled")
    }
}

private val NamedEntities = mapOf(
    "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "amp" to "&", "nbsp" to " "
)

/** 只解码一层实体（XML-RPC 那层已经由解析器处理过），&amp; 因此不会被二次展开。 */
private fun String.decodeEntities(): String {
    if (!contains('&')) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        val end = if (c == '&') indexOf(';', i + 1) else -1
        if (end < 0 || end - i > 10) {
            out.append(c)
            i++
            continue
        }
        val body = substring(i + 1, end)
        val decoded = if (body.startsWith("#")) {
            val code = body.substring(1)
            val cp = if (code.startsWith("x") || code.startsWith("X"))
                code.substring(1).toIntOrNull(16) else code.toIntOrNull()
            cp?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
        } else {
            NamedEntities[body.lowercase()]
        }
        if (decoded == null) {
            out.append(c)
            i++
        } else {
            out.append(decoded)
            i = end + 1
        }
    }
    return out.toString()
}

/** 落盘缓存的载体。 */
@Serializable
data class CachedFeed(
    val savedAt: Long = 0L,
    val posts: List<Post> = emptyList()
)

/**
 * 编辑器的本地稿：进程被杀或闪退时不至于丢稿。
 * 字段名跟 [EditorUiState] 对齐，提交成功或用户明确丢弃后就删。
 */
@Serializable
data class LocalDraft(
    val postId: String = "",
    val isPage: Boolean = false,
    val title: String = "",
    val content: String = "",
    val excerpt: String = "",
    val tagsText: String = "",
    val selectedCategories: List<String> = emptyList(),
    val status: String = "publish",
    /** 改过的发布时间（epoch 秒）；0 是老本地稿，按没动过时间处理。 */
    val publishAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val isBlank: Boolean get() = title.isBlank() && content.isBlank()
}

@Serializable
data class Comment(
    val commentId: String = "",
    val postId: String = "",
    val author: String = "",
    val authorEmail: String = "",
    val authorUrl: String = "",
    val content: String = "",
    val dateCreated: Long = 0L,
    val status: String = "approve",
    val parentId: String = "0",
    val type: String = "comment"
) {
    val displayDate: String
        get() = DateUtils.formatDate(dateCreated)

    val isApproved: Boolean get() = status == "approve"
    val isSpam: Boolean get() = status == "spam"
    val isPending: Boolean get() = status == "hold" || status == "waiting"
}

/** 一条顶层评论及其楼中楼回复。 */
data class CommentThread(
    val root: Comment,
    val replies: List<Comment> = emptyList()
)

@Serializable
data class UserInfo(    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val roles: List<String> = emptyList()
)

@Serializable
data class BlogInfo(
    val blogId: String = "1",
    val blogName: String = "",
    val url: String = "",
    val xmlrpcUrl: String = "",
    val isAdmin: Boolean = false
)

@Serializable
data class Category(
    val categoryId: String = "",
    val name: String = "",
    val slug: String = "",
    val parentId: String = "0",
    val description: String = "",
    val url: String = "",
    val postCount: Int = 0
)

data class MediaRef(
    val name: String = "",
    val url: String = ""
)

@Serializable
data class SiteConfig(
    val siteUrl: String = "",
    val xmlrpcPath: String = "/action/xmlrpc",
    val username: String = "",
    val password: String = "",
    val blogId: String = "1"
) {
    val fullXmlrpcUrl: String
        get() {
            val base = siteUrl.trimEnd('/')
            val path = if (xmlrpcPath.startsWith("/")) xmlrpcPath else "/$xmlrpcPath"
            return "$base$path"
        }
}

/** 密码只在内存里是明文，落盘一律走 Keystore 密文。 */
data class CloudreveConfig(
    val site: String = "",
    val email: String = "",
    val password: String = ""
) {
    val isComplete: Boolean
        get() = site.isNotBlank() && email.isNotBlank() && password.isNotBlank()
}

enum class PostStatus(val xmlRpcValue: String, val label: String) {
    PUBLISH("publish", "已发布"),
    DRAFT("draft", "草稿"),
    PRIVATE("private", "私密")
}

enum class CommentStatus(val xmlRpcValue: String, val label: String) {
    APPROVE("approve", "已通过"),
    HOLD("hold", "待审核"),
    SPAM("spam", "垃圾"),
    TRASH("trash", "已删除")
}
