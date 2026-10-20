package noline.zhouzhou.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import noline.zhouzhou.data.model.BtExistingFile
import noline.zhouzhou.data.model.BtFileAccess
import noline.zhouzhou.data.model.BtFileBody
import noline.zhouzhou.data.model.BtFileEntry
import noline.zhouzhou.data.model.BtFileHistoryFile
import noline.zhouzhou.data.model.BtFileHistoryLine
import noline.zhouzhou.data.model.BtDirPage
import noline.zhouzhou.data.model.BtPathProbe
import noline.zhouzhou.data.model.BtPathSize
import noline.zhouzhou.data.model.BtRecycleEntry
import noline.zhouzhou.data.model.BtRecyclePage
import noline.zhouzhou.data.model.btDirPage
import noline.zhouzhou.data.model.btDirPageLegacy
import noline.zhouzhou.data.model.btExistingFiles
import noline.zhouzhou.data.model.btFileAccess
import noline.zhouzhou.data.model.btFileBody
import noline.zhouzhou.data.model.btFileHistoryFiles
import noline.zhouzhou.data.model.btFileHistoryLines
import noline.zhouzhou.data.model.btItems
import noline.zhouzhou.data.model.btJoinPath
import noline.zhouzhou.data.model.btParentPath
import noline.zhouzhou.data.model.btPathProbe
import noline.zhouzhou.data.model.btPathSize
import noline.zhouzhou.data.model.btRecyclePage
import noline.zhouzhou.data.model.btTotal
import noline.zhouzhou.data.remote.BtFilesApi
import noline.zhouzhou.data.remote.BtUploadPart

/**
 * 文件管理器的仓库层。
 *
 * 三条这一层特有的规矩：
 * 1. **目录页要认回声。** 面板在路径不存在时不报错，它把人领到 `/www/wwwroot` 并在那儿放一堆条目，
 *    只有回包里的 `path` 说了实话。这里把请求路径传进解析器，对不上就整页当「不存在」；
 * 2. **写完一律重读目录。** 面板的删除、改名、改权限都回一句「成功」，可递归改权限那句是假的
 *    （实测子文件纹丝不动），所以界面上那句结果来自重读到的列表，不来自面板措辞；
 * 3. **目标路径在这里拼。** `MvFile`/`CopyFile` 只认「目标全路径」，人在界面上选的是「放进哪个目录」，
 *    这一层负责把 `目标目录/原名` 拼出来——那一步交给面板就会回「移动文件或目录失败!」。
 */
class BtFilesRepository(private val panel: BtPanelRepository) {

    private suspend fun client(): BtFilesApi = panel.filesClient()

    fun describeFailure(error: Throwable): BtFailure = panel.describeFailure(error)

    // ── 浏览 ──

    /**
     * 列一个目录。`GetDirNew` 认不出来时（回包里连 `dir`/`files` 都没有）退回 `GetDir`：
     * 面板文档标的是 11.7.0 的源码，这台跑的是 13.1.0，版本差在这条路上撞过很多次。
     */
    suspend fun dir(path: String, search: String = ""): Result<BtDirPage> = runCatching {
        val api = client()
        val primary = runCatching { api.dir(path, search) }
        val page = primary.getOrNull()?.root?.let { btDirPage(it, path) }
        if (page != null && (page.entries.isNotEmpty() || recognized(primary.getOrNull()?.root))) {
            page.sorted()
        } else {
            api.dirLegacy(path, search).root.let { btDirPageLegacy(it, path) }.sorted()
        }
    }

    private fun recognized(root: JsonElement?): Boolean {
        val obj = root as? JsonObject ?: return false
        return obj.containsKey("dir") || obj.containsKey("files")
    }

    private fun BtDirPage.sorted(): BtDirPage = copy(
        entries = entries.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    )

    /** 用户手输路径时先问一句在不在，比列出来才发现是别的目录强。 */
    suspend fun probe(path: String): Result<BtPathProbe> = runCatching { btPathProbe(client().exists(path).root) }

    suspend fun size(path: String): Result<BtPathSize> =
        runCatching { btPathSize(client().pathSize(path).root) }

    /**
     * 这一页的旁路读数：Composer 版本、这台配了哪些备份方案、当前目录在同步工具里的状态。
     * 三路并发，谁没读到就少一行，跟看板一个写法。
     */
    suspend fun tools(path: String): Result<BtFilesTools> = runCatching {
        val api = client()
        coroutineScope {
            val composer = async { runCatching { api.composerVersion() } }
            val backups = async { runCatching { api.backups() } }
            val sync = async { runCatching { api.syncStatus(path) } }
            BtFilesTools(
                composer = composer.await().getOrNull().orEmpty(),
                backups = backups.await().getOrNull()?.let { btItems(it) }.orEmpty(),
                syncOn = (sync.await().getOrNull() as? JsonObject)
                    ?.get("have_sync")?.let { it.toString() == "true" } ?: false,
                syncFailed = sync.await().isFailure
            )
        }
    }

    // ── 单条读 ──

    suspend fun body(path: String): Result<BtFileBody> =
        runCatching { btFileBody(client().fileBody(path).root, path) }

    suspend fun access(path: String): Result<BtFileAccess> =
        runCatching { btFileAccess(client().fileAccess(path).root) }

    suspend fun history(page: Int, keyword: String): Result<BtHistoryPage> = runCatching {
        val reply = client().fileHistory(page, keyword)
        BtHistoryPage(
            lines = btFileHistoryLines(reply.root),
            total = btTotal(reply.root)
        )
    }

    suspend fun historyFiles(): Result<List<BtFileHistoryFile>> =
        runCatching { btFileHistoryFiles(client().historyFiles()) }

    /** 目标目录里是否已经躺着同名东西；复制/上传前拿来问一句。 */
    suspend fun conflictIn(dir: String, name: String): Result<BtExistingFile?> = runCatching {
        btExistingFiles(client().existingFile(dir, name)).firstOrNull { it.name == name }
    }

    // ── 写：建 ──

    /** 在 [parent] 下建目录。名字里带斜杠在这里就挡掉（`BtFilesForm.nameError`）。 */
    suspend fun createDir(parent: String, name: String): Result<String> = runCatching {
        client().createDir(btJoinPath(parent, name))
    }

    suspend fun createFile(parent: String, name: String): Result<String> = runCatching {
        client().createFile(btJoinPath(parent, name))
    }

    suspend fun saveBody(body: BtFileBody, content: String): Result<String> = runCatching {
        client().saveBody(body.path, content, body.encoding)
    }

    /**
     * 改权限 / 属主。返回值里带了「只改这一层」那句：实测对目录发 `all=1` 也只动目录自己，
     * 所以这句话不是免责声明，是这一发的实际范围。
     */
    suspend fun setAccess(path: String, perm: String, owner: String): Result<String> = runCatching {
        val said = client().setAccess(path, perm, owner)
        "$said（只改这一层：递归那一格实测不发作用，子文件还是原来的权限）"
    }

    // ── 写：动 ──

    suspend fun rename(entry: BtFileEntry, newName: String): Result<String> = runCatching {
        client().move(entry.path, btJoinPath(btParentPath(entry.path), newName))
    }

    /** 搬进另一个目录：目标全路径在这一层拼。 */
    suspend fun moveInto(entry: BtFileEntry, targetDir: String): Result<String> = runCatching {
        client().moveInto(entry.path, targetDir, entry.name)
    }

    suspend fun copyInto(entry: BtFileEntry, targetDir: String): Result<String> = runCatching {
        client().copy(entry.path, btJoinPath(targetDir, entry.name))
    }

    suspend fun link(entry: BtFileEntry, targetPath: String): Result<String> = runCatching {
        client().link(entry.path, targetPath)
    }

    /** 目录走 `DeleteDir`、文件走 `DeleteFile`，两边都进回收站（实测 msg 就是「已将…移动到回收站!」）。 */
    suspend fun delete(entry: BtFileEntry): Result<String> = runCatching {
        val api = client()
        if (entry.isDir) api.deleteDir(entry.path) else api.deleteFile(entry.path)
    }

    /** 批量删除：逐条发，最后报成了几条。这一发在界面上要输入关键字。 */
    suspend fun deleteMany(entries: List<BtFileEntry>): Result<String> = runCatching {
        require(entries.isNotEmpty()) { "没选文件" }
        val api = client()
        val failed = entries.filterNot { entry ->
            runCatching {
                if (entry.isDir) api.deleteDir(entry.path) else api.deleteFile(entry.path)
            }.isSuccess
        }
        when {
            failed.isEmpty() -> "${entries.size} 条已经放进回收站"
            else -> "有 ${failed.size} 条没放进去：" + failed.joinToString("、") { it.name }
        }
    }

    /**
     * 解压。`type1` 从后缀猜（实测面板对这个参数不挑，给错也不报，所以宁可按后缀给）；
     * 目标目录不存在时面板会建出来。
     */
    suspend fun unzip(entries: List<BtFileEntry>, targetDir: String): Result<String> = runCatching {
        require(entries.isNotEmpty()) { "没选压缩包" }
        val type = btArchiveTypeOf(entries.first().name)
        client().unzip(entries.map { it.path }, targetDir, "utf-8", type)
    }

    suspend fun upload(dir: String, part: BtUploadPart): Result<String> =
        runCatching { client().upload(dir, part) }

    suspend fun scanDir(path: String): Result<String> = runCatching {
        client().scanDir(path) + "（结果只落在面板的安全日志里，这一格读不回来）"
    }

    suspend fun scanFile(path: String): Result<String> = runCatching {
        client().scanFile(path) + "（同上：这一格没有读回的口）"
    }

    suspend fun installRar(): Result<String> = runCatching { client().installRar() }

    suspend fun updateComposer(): Result<String> = runCatching { client().updateComposer() }

    // ── 回收站 ──

    suspend fun recycle(page: Int = 1, keyword: String = ""): Result<BtRecyclePage> = runCatching {
        btRecyclePage(client().recycleBin(page, keyword).root)
    }

    /**
     * 放回原处，或者放到 [to] 指定的**完整路径**。
     * 返回值里那句「占位的会被改名成 .bak」是实测行为，界面上要提前说。
     */
    suspend fun restore(entry: BtRecycleEntry, to: String = ""): Result<String> = runCatching {
        val said = client().restore(entry.rname, to)
        if (to.isBlank()) said else "$said（目标位置原有的东西会被面板改名成 原名_时间戳.bak 留着）"
    }

    suspend fun purge(entry: BtRecycleEntry): Result<String> = runCatching { client().purge(entry.rname) }

    suspend fun clearBin(): Result<String> = runCatching { client().clearRecycleBin() }
}

/** 文件管理页底部那张「这台机器的工具」卡。备份方案这一格这台回的是空数组，界面上就写「没配」。 */
data class BtFilesTools(
    val composer: String,
    val backups: List<JsonObject>,
    val syncOn: Boolean,
    val syncFailed: Boolean
) {
    val backupCount: Int get() = backups.size
}

data class BtHistoryPage(val lines: List<BtFileHistoryLine>, val total: Int?) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 压缩包后缀 → 面板那个 `type1`。认不出来的后缀一律当 `zip`，
 * 因为界面上只把这几类列成「解压」（实测对非压缩包发这一口，面板不报错，它建一个同名的空目录了事）。
 */
fun btArchiveTypeOf(name: String): String = when (name.lowercase().substringAfterLast('.', "")) {
    "tar" -> "tar"
    "gz", "tgz" -> "gz"
    "bz2" -> "bz2"
    "xz" -> "xz"
    "rar" -> "rar"
    "7z" -> "7z"
    else -> "zip"
}

/** 看着像压缩包的后缀。解压那颗按钮只在这些行上露出来。 */
fun btIsArchive(name: String): Boolean =
    listOf(".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".rar", ".7z").any {
        name.lowercase().endsWith(it)
    }
