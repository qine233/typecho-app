package noline.zhouzhou.data.repository

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一行占用。clearable 为 true 的行才有「清理」按钮，其余只是解释钱花在哪了。
 */
/** 缓存文件按用途分三类，决定它出现在哪一行、能不能一键清掉。 */
internal enum class CacheClass { IMAGE, UPDATE, OTHER }

/**
 * 只看相对缓存根目录的第一段目录名：Coil 的磁盘缓存和 HTTP 缓存各自一个目录，
 * 更新包固定落在 updates/。深层路径里再出现 coil 字样不算图片缓存。
 */
internal fun classifyCache(relativePath: String): CacheClass {
    val top = relativePath.substringBefore('/').substringBefore('\\')
    return when {
        top.contains("coil", ignoreCase = true) -> CacheClass.IMAGE
        top == "updates" -> CacheClass.UPDATE
        else -> CacheClass.OTHER
    }
}

data class StorageEntry(
    val key: String,
    val label: String,
    val bytes: Long,
    val clearable: Boolean
)

data class StorageUsage(
    val entries: List<StorageEntry>,
    val freeBytes: Long
) {
    val totalBytes: Long get() = entries.sumOf { it.bytes }

    fun bytesOf(vararg keys: String): Long = bytesOf(keys.toList())

    fun bytesOf(keys: Collection<String>): Long =
        entries.filter { it.key in keys }.sumOf { it.bytes }

    fun entry(key: String): StorageEntry? = entries.firstOrNull { it.key == key }
}

/**
 * 存储占用统计与 App 内清理。
 *
 * 不用 StorageStatsManager：那个要「使用情况访问权限」，得把用户支到系统设置里开特殊开关，
 * 而这里每一项都在自己的私有目录里，直接走文件树就能算准。
 *
 * 清理只碰可再生的东西（图片缓存、更新安装包、文章缓存）。草稿和待同步队列是用户还没
 * 交上去的数据，删了就真没了，所以它们只出现在统计里，永远不进 clearCaches。
 */
class StorageRepository(private val context: Context) {

    suspend fun measure(): StorageUsage = withContext(Dispatchers.IO) {
        val cacheFiles = taggedCacheFiles()
        val imageCache = cacheFiles.filter { it.klass == CacheClass.IMAGE }.sumOf { it.file.length() }
        val updates = cacheFiles.filter { it.klass == CacheClass.UPDATE }.sumOf { it.file.length() }
        val otherCache = cacheFiles.filter { it.klass == CacheClass.OTHER }.sumOf { it.file.length() }

        val draftBytes = draftsDir().dirSize()
        val queueBytes = queueFiles().sumOf { it.length() }
        val postsBytes = postsCacheFile().length()
        val dataRoots = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.getExternalFilesDir(null))
        // filesDir 里已经单列过的部分不能再算进「其他数据」，否则一个草稿会被数两遍
        val alreadyCounted = draftBytes + queueBytes + postsBytes
        val otherData = dataRoots.sumOf { it.dirSize() } - alreadyCounted

        StorageUsage(
            entries = listOf(
                StorageEntry(KEY_APK, "安装包", apkBytes(), false),
                StorageEntry(KEY_IMAGE, "图片缓存", imageCache, true),
                StorageEntry(KEY_UPDATE, "已下载的更新包", updates, true),
                StorageEntry(KEY_POSTS, "文章缓存", postsBytes, true),
                StorageEntry(KEY_CACHE_OTHER, "其他缓存", otherCache, true),
                StorageEntry(KEY_DRAFTS, "本地草稿", draftBytes, false),
                StorageEntry(KEY_QUEUE, "待同步记录", queueBytes, false),
                StorageEntry(KEY_DATA_OTHER, "设置与账号数据", otherData.coerceAtLeast(0L), false)
            ),
            freeBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)
        )
    }

    /** 清理 [keys] 指定的几类缓存，返回释放的字节数。 */
    suspend fun clearCaches(keys: Set<String>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        if (keys.any { it in CACHE_KEYS }) {
            for (tagged in taggedCacheFiles()) {
                val key = when (tagged.klass) {
                    CacheClass.IMAGE -> KEY_IMAGE
                    CacheClass.UPDATE -> KEY_UPDATE
                    CacheClass.OTHER -> KEY_CACHE_OTHER
                }
                if (key in keys) freed += tagged.file.deleteSized()
            }
        }
        if (KEY_POSTS in keys) freed += postsCacheFile().deleteSized()
        freed
    }

    suspend fun clearImageCache(): Long = clearCaches(setOf(KEY_IMAGE))

    suspend fun clearPostsCache(): Long = clearCaches(setOf(KEY_POSTS))

    // ---- 目录定义 ----

    private data class Tagged(val file: File, val klass: CacheClass)

    /** 缓存目录下的每个文件按它相对根目录的第一段归类。 */
    private fun taggedCacheFiles(): List<Tagged> = cacheRoots().flatMap { root ->
        root.listFilesRecursive().map { file ->
            Tagged(file, classifyCache(file.relativeTo(root).path))
        }
    }

    private fun cacheRoots(): List<File> =
        listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir).filter { it.exists() }

    private fun postsCacheFile() = File(context.filesDir, "posts_cache.json")

    private fun draftsDir() = File(context.filesDir, "drafts")

    private fun queueFiles() =
        listOf("health_queue.json", "bills_queue.json").map { File(context.filesDir, it) }

    private fun apkBytes(): Long {
        val info = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        }.getOrNull() ?: return 0L
        val paths = buildList {
            info.sourceDir?.let { add(it) }
            info.splitSourceDirs?.let { addAll(it) }
        }
        return paths.sumOf { File(it).dirSize() }
    }

    /**
     * 自己遍历而不用 walkTopLevel：它会把符号链接跟进去，而缓存目录里一个指回上层的
     * 链接就能让统计转不出来。顺带 listFiles 返回 null（没权限）时也不会炸。
     */
    private fun File.listFilesRecursive(): List<File> {
        val files = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        listFiles()?.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isDirectory) {
                current.listFiles()?.forEach { stack.addLast(it) }
            } else {
                files.add(current)
            }
        }
        return files
    }

    private fun File.dirSize(): Long =
        if (isFile) length() else runCatching { listFilesRecursive().sumOf { it.length() } }.getOrDefault(0L)

    /** 删掉一个文件并回报实际释放了多少（删失败就返回 0）。 */
    private fun File.deleteSized(): Long {
        if (!exists()) return 0L
        val size = length()
        return if (delete()) size else 0L
    }

    companion object {
        const val KEY_APK = "apk"
        const val KEY_IMAGE = "image"
        const val KEY_UPDATE = "update"
        const val KEY_POSTS = "posts"
        const val KEY_CACHE_OTHER = "cache_other"
        const val KEY_DRAFTS = "drafts"
        const val KEY_QUEUE = "queue"
        const val KEY_DATA_OTHER = "data_other"

        /** 可以一键清掉的全部条目。 */
        val CACHE_KEYS = setOf(KEY_IMAGE, KEY_UPDATE, KEY_POSTS, KEY_CACHE_OTHER)
    }
}
