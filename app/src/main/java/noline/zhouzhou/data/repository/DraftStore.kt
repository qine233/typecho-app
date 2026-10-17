package noline.zhouzhou.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import noline.zhouzhou.data.model.LocalDraft
import java.io.File

/**
 * 编辑器本地稿，一篇一个文件。写的是 filesDir，不占用系统密钥库那条通道。
 *
 * 落盘挂在 store 自己的作用域上而不是 ViewModel 的：进程被系统回收时 viewModelScope
 * 已经取消了，而那恰恰是最需要把稿子留下来的时刻。
 */
class DraftStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir: File get() = File(context.filesDir, "drafts")
    private fun fileFor(key: String) = File(dir, "$key.json")

    /** 空稿直接忽略：既省文件，也避免刚进编辑器就把待恢复的那份覆盖掉。 */
    fun write(draft: LocalDraft) {
        if (draft.isBlank) return
        val key = draftKey(draft.postId.ifBlank { null }, draft.isPage)
        scope.launch {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "$key.tmp")
                tmp.writeText(json.encodeToString(LocalDraft.serializer(), draft))
                tmp.renameTo(fileFor(key))
            }
        }
    }

    suspend fun load(postId: String?, isPage: Boolean): LocalDraft? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(draftKey(postId, isPage))
            if (!file.exists()) return@runCatching null
            json.decodeFromString(LocalDraft.serializer(), file.readText()).takeUnless { it.isBlank }
        }.getOrNull()
    }

    fun discard(postId: String?, isPage: Boolean) {
        val key = draftKey(postId, isPage)
        scope.launch { runCatching { fileFor(key).delete() } }
    }

    /** 磁盘上的全部本地稿，按最后编辑时间倒序。给「数据与存储」页列出来用。 */
    suspend fun listAll(): List<DraftEntry> = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles { file: File -> file.isFile && file.name.endsWith(".json") }
                ?.mapNotNull { file ->
                    runCatching {
                        val draft = json.decodeFromString(LocalDraft.serializer(), file.readText())
                        if (draft.isBlank) return@runCatching null
                        DraftEntry(
                            key = file.name.removeSuffix(".json"),
                            title = draft.title.ifBlank { if (draft.isPage) "未命名页面" else "未命名文章" },
                            isPage = draft.isPage,
                            postId = draft.postId,
                            excerpt = draft.content.replace('\n', ' ').trim().take(60),
                            updatedAt = if (draft.updatedAt != 0L) draft.updatedAt else file.lastModified(),
                            bytes = file.length()
                        )
                    }.getOrNull()
                }
                ?.sortedByDescending { it.updatedAt }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 按文件名丢弃。列表页拿不到 postId/isPage 之外的键，所以直接给 key。 */
    suspend fun discardKey(key: String) = withContext(Dispatchers.IO) {
        runCatching { fileFor(key).delete() }
    }
}

/** 一份本地稿的摘要，够列表页显示一行。 */
data class DraftEntry(
    val key: String,
    val title: String,
    val isPage: Boolean,
    val postId: String,
    val excerpt: String,
    val updatedAt: Long,
    val bytes: Long
)

/**
 * postId 来自导航参数，可能是 URL 里塞进来的任意串，所以只留字母数字和 -_，
 * 免得一个 `../` 就把稿子写到 drafts 目录外面去。
 */
internal fun draftKey(postId: String?, isPage: Boolean): String {
    val id = postId.orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return (if (isPage) "page_" else "post_") + if (id.isBlank()) "new" else id
}
