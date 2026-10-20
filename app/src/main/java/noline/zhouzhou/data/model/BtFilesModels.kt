package noline.zhouzhou.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 文件管理器这一页的读模型。键名按正式面板 13.1.0 的实测回包钉死，七条结论决定了形状：
 *
 * 1. **列表有两个口，字段名不是一个大小写。** `GetDirNew` 回小写键的对象行
 *    （`nm/sz/mt/acc/user/lnk/durl/cmp/fav/rmk/top/sn`），`GetDir` 回同一堆信息的**大写键 + 分号拼接串**
 *    （`DIR`/`FILES`/`PAGE`/`STORE`/`FILE_RECYCLE`）。两个口都实测通过，这里以 `GetDirNew` 为主、
 *    `GetDir` 当它哪天不在了的兜底；
 * 2. **路径不存在时面板不报错，它把你领到 `/www/wwwroot`。** 实测发 `path=/nope/nope`，
 *    回包 HTTP 200、`dir`/`files` 各有一堆条目，而 `path` 那一格写的是 `/www/wwwroot`。
 *    所以每个目录页都要拿回包里的 `path` 和请求的那个比一眼，对不上就当「不存在」处理
 *    —— 不然人在手机上输错一层目录，看见的是另一个站点的根目录；
 * 3. **目录那一格的 `sz` 不是内容占用。** 实测 `/tmp` 下那几个 systemd 目录 `sz=17`，
 *    那是目录 inode 自身的大小；同一个 `/tmp` 走 `get_path_size` 回的是 3772868。
 *    所以目录的大小一律留空，要看占用是单独一发；
 * 4. **收藏目录这一格看不到实物。** `store` / `get_files_store` 在这台都回空数组
 *    （一个收藏都没建过），面板文档也没写它的字段。所以 [BtDirPage.favorites] 的解析
 *    是「裸字符串或带 path 的对象都接着」，等真建了收藏再收口；
 * 5. **`GetFileHistory` 不吃 `limit`。** 带上 `limit` 直接 HTTP 400 空 body（实测），
 *    只发 `p` 和 `content` 才有数据，每页固定 20 条（回包里的 `row: 20`）；
 * 6. **它的 `status` 是字符串 `"true"`。** 所以这一口走不了信封判定，整包当裸数据读；
 * 7. **`search` 那一格发过去是白发的。** 实测带 `search=link` 列 `/tmp/aa-test-files`，面板不看它、
 *    照样回整层，却把关键词记进 `search_history` 原样回给你（还会出现在网页端）。
 *    所以这一页筛名字是本地按 `name` 做的，那一格根本不发出去；
 * 8. **目录列表不分页。** `GetDirNew` 的 `limit` 实测不看：发 `limit=5` 回来 31 条，
 *    `page` 那串 HTML 也永远写着 `1/1`。所以这一页没有「下一页」，一个大目录一次给完，
 *    给不完由 `is_max` 说；
 * 9. **回收站才是真分页**，每页固定 10 条、目录在前文件在后（详见 [BtRecycleEntry]）。
 */

/** 目录里的一行。面板给的是相对 [BtFileEntry.path] 的名字，路径由解析时拼好。 */
data class BtFileEntry(
    /** 面板给的名字，含点号开头的隐藏项（这台 `/tmp` 里就有 `.fluah_time`）。 */
    val name: String,
    /** 拼好的绝对路径。写操作（改名、删除、改权限）发的是这一格，不是 [name]。 */
    val path: String,
    val isDir: Boolean,
    /** 文件才有字节数；目录那一格面板给的是 inode 大小，这里不当内容占用，见类注释第 3 条。 */
    val sizeBytes: Long?,
    val modifiedAt: Long?,
    /** `644` / `755` 这种三位八进制。面板给的是字符串，空就当没读到。 */
    val perm: String,
    val owner: String,
    /** 软链接指向哪里；不是链接就空。 */
    val symlink: String,
    /** 面板给这个文件生成的下载直链；这台实测一直是空串（没开过分享链接）。 */
    val downloadUrl: String,
    val favorite: Boolean,
    val remark: String,
    /** 面板的「置顶」这一格。实测怎么发都不动，所以只读不写。 */
    val pinned: Boolean
) {
    val sizeText: String
        get() = when {
            isDir -> ""
            sizeBytes == null -> ""
            else -> formatBtBytes(sizeBytes.toDouble())
        }

    /** 行上的副标题：谁所有、什么权限、什么时候动过。 */
    val subtitle: String
        get() = listOfNotNull(
            owner.takeIf { it.isNotBlank() }?.let { "属主 $it" },
            perm.takeIf { it.isNotBlank() }?.let { "权限 $it" },
            sizeText.takeIf { it.isNotBlank() }?.let { "大小 $it" },
            timeText.takeIf { it.isNotBlank() }?.let { "改于 $it" }
        ).joinToString(" · ")

    val timeText: String
        get() = modifiedAt?.let { btEpochToText(it) }.orEmpty()

    /** 名字里带斜杠或纯点号的行不能当路径片段用（`..` 是面板自己给的相对写法）。 */
    val isNavigable: Boolean get() = isDir && name.isNotBlank() && !name.contains("/")
}

/** 一次目录浏览的全部产出。 */
data class BtDirPage(
    val entries: List<BtFileEntry>,
    val total: Int?,
    /** 面板回包里 `path` 那一格的原文。 */
    val echoedPath: String,
    /** 请求的路径和面板认下的那个是不是同一个；不等就是第 2 条那个静默兜底。 */
    val pathMatches: Boolean,
    val favorites: List<String>,
    val recents: List<BtPathRecord>,
    val searches: List<BtPathRecord>,
    /** 回包里的 `file_recycle`：回收站开关。关着的话删除就是直接永久删。 */
    val recycleEnabled: Boolean,
    /** `is_max`：面板说这个目录太长、只回了一部分。这台一直没触发过。 */
    val truncated: Boolean,
    val dirCount: Int,
    val fileCount: Int
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** 面板记下来的一个路径（收藏目录 `dir_history`、搜索记录 `search_history` 都是这个形状）。 */
data class BtPathRecord(val value: String, val timeSeconds: Long?) {
    val timeText: String get() = timeSeconds?.let { btEpochToText(it) }.orEmpty()
}

/** 文件正文。`encoding` 和 `only_read` 原样带出去，界面按它们决定能不能编辑。 */
data class BtFileBody(
    val path: String,
    val content: String,
    val encoding: String,
    val sizeBytes: Long?,
    /** 面板认这个文件「只读」（实测 `only_read: false` 是常态）；为真时保存也会被面板拒。 */
    val readOnly: Boolean,
    val autoSave: Boolean,
    /** 这个文件在面板里存了几个历史版本（`historys` 那一格）。 */
    val historyCount: Int,
    /** `st_mtime`：面板读到的磁盘上的修改时间，可能是秒也可能带小数。 */
    val diskModifiedAt: Long?
) {
    val encodingLabel: String get() = encoding.ifBlank { "面板没给编码" }

    /** 面板对超过某个体积的文件会拒绝编辑，这条实测撞在 msg 里。 */
    val tooLarge: Boolean get() = sizeBytes != null && sizeBytes > MaxEditableBytes
}

/** 权限与属主。`users` 是面板给的可选系统用户名单（实测 29 个）。 */
data class BtFileAccess(val perm: String, val owner: String, val users: List<String>)

/**
 * 回收站里的一条。`rname` 是面板存盘用的那个化名，恢复和彻底删除都只认它。
 *
 * 实测这一口的排序是**目录在前、文件在后，同类按时间新→旧**（刚删掉的那个排在第一页第一行，
 * 每页固定 10 条，`limit` 参数它不看）。所以「我刚删的那条在哪」翻第一页就能看见，
 * 而按名字找要用 `search`——那一格只匹配 `name`（原始 basename），匹配不到路径。
 */
data class BtRecycleEntry(
    val rname: String,
    val name: String,
    /** `dname`：**原始完整路径**（实测已含 `name` 本身，不是「所在目录」），别再接一遍名字。 */
    val originalPath: String,
    val timeSeconds: Long?,
    val sizeBytes: Long?,
    val isDir: Boolean
) {
    val originalDir: String get() = btParentPath(originalPath)
    val timeText: String get() = timeSeconds?.let { btEpochToText(it) }.orEmpty()
    val sizeText: String get() = if (isDir) "" else sizeBytes?.let { formatBtBytes(it.toDouble()) }.orEmpty()
}

/** 回收站按类型给的计数。面板的键 `ont_text` 就是这个拼写，指的是文本文件那一类。 */
data class BtRecycleCounts(
    val all: Int?,
    val dir: Int?,
    val file: Int?,
    val image: Int?,
    val text: Int?,
    val db: Int?,
    val totalSizeBytes: Long?
) {
    val breakdown: String
        get() = listOfNotNull(
            dir?.takeIf { it > 0 }?.let { "目录 $it" },
            file?.takeIf { it > 0 }?.let { "文件 $it" },
            image?.takeIf { it > 0 }?.let { "图片 $it" },
            text?.takeIf { it > 0 }?.let { "文本 $it" },
            db?.takeIf { it > 0 }?.let { "数据库 $it" }
        ).joinToString(" · ")

    val totalSizeText: String get() = totalSizeBytes?.let { formatBtBytes(it.toDouble()) }.orEmpty()
}

data class BtRecyclePage(
    val entries: List<BtRecycleEntry>,
    val total: Int?,
    val counts: BtRecycleCounts,
    /** `status`：回收站功能开没开；`status_db` 是数据库回收站那一路，这台是 false。 */
    val enabled: Boolean,
    val dbEnabled: Boolean
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** 面板的文件操作历史（`GetFileHistory`）。这一口实测回的是全站文件管理的流水，不只自己这台设备。 */
data class BtFileHistoryLine(
    val id: String,
    val type: String,
    val log: String,
    val time: String,
    val username: String
)

/** 有编辑历史的那些文件（`file_history_list`）。 */
data class BtFileHistoryFile(
    val path: String,
    val time: String,
    val id: String,
    val versions: Int
)

/** 单个路径的占用（`get_path_size`）。这台 `/tmp` 实测 3772868 字节。 */
data class BtPathSize(val path: String, val bytes: Long?) {
    val text: String get() = bytes?.let { formatBtBytes(it.toDouble()) }.orEmpty()
}

/** `test_path` 的产出。不存在时面板把 `path` 写成空串，所以判在不在只看 [exists]。 */
data class BtPathProbe(val path: String, val exists: Boolean, val isDir: Boolean)

/** `CheckExistsFiles` 的一行：目标目录里已经躺着的同名东西。 */
data class BtExistingFile(val name: String, val sizeBytes: Long?, val isDir: Boolean)

// ── 解析 ──

/**
 * `GetDirNew` 的整包。传 [BtReply.root]：这一口没有 status 信封，整个回包就是数据。
 *
 * 请求路径由调用方给（回包里的 `path` 在不存在时会被面板换成别的，见类注释第 2 条）。
 */
fun btDirPage(root: JsonElement?, requestedPath: String): BtDirPage {
    val obj = root as? JsonObject ?: return emptyDirPage(requestedPath)
    val entries = btFileEntries(obj["dir"], requestedPath, true) + btFileEntries(obj["files"], requestedPath, false)
    val echo = obj.textOf("path")
    return BtDirPage(
        entries = entries,
        total = totalFromPageHtml(obj["page"].let(::btText)),
        echoedPath = echo,
        pathMatches = echo.isBlank() || samePath(echo, requestedPath),
        favorites = favoritePaths(obj["store"]),
        recents = pathRecords(obj["dir_history"]),
        searches = pathRecords(obj["search_history"]),
        recycleEnabled = obj.flagOf("file_recycle") ?: true,
        truncated = obj.flagOf("is_max") ?: false,
        dirCount = itemCount(obj["dir"]),
        fileCount = itemCount(obj["files"])
    )
}

/**
 * `GetDir` 的整包（兜底路径）：大写键 + 每行一条 `;` 拼接串。
 * 只有前五个位置（名字、大小、时间、权限、属主）能对上号，后面七个位置实测和命名键顺序并不一致，
 * 所以这里只认前五个，其余留空——这是兜底，不是主路。
 */
fun btDirPageLegacy(root: JsonElement?, requestedPath: String): BtDirPage {
    val obj = root as? JsonObject ?: return emptyDirPage(requestedPath)
    val dirs = obj["DIR"].positionalEntries(requestedPath, isDir = true)
    val files = obj["FILES"].positionalEntries(requestedPath, isDir = false)
    val echo = obj.textOf("PATH")
    return BtDirPage(
        entries = dirs + files,
        total = totalFromPageHtml(obj["PAGE"].let(::btText)),
        echoedPath = echo,
        pathMatches = echo.isBlank() || samePath(echo, requestedPath),
        favorites = favoritePaths(obj["STORE"]),
        recents = pathRecords(obj["DIR_HISTORY"]),
        searches = pathRecords(obj["SEARCH_HISTORY"]),
        recycleEnabled = obj.flagOf("FILE_RECYCLE") ?: true,
        truncated = obj.flagOf("IS_MAX") ?: false,
        dirCount = dirs.size,
        fileCount = files.size
    )
}

private fun emptyDirPage(requestedPath: String): BtDirPage =
    BtDirPage(emptyList(), null, requestedPath, true, emptyList(), emptyList(), emptyList(), true, false, 0, 0)

private fun JsonElement?.positionalEntries(parent: String, isDir: Boolean): List<BtFileEntry> {
    val items = this as? JsonArray ?: return emptyList()
    return items.mapNotNull { row ->
        val parts = (row as? JsonPrimitive)?.contentOrNull?.split(";") ?: return@mapNotNull null
        val name = parts.getOrElse(0) { "" }.trim()
        if (name.isBlank()) return@mapNotNull null
        BtFileEntry(
            name = name,
            path = btJoinPath(parent, name),
            isDir = isDir,
            sizeBytes = parts.getOrElse(1) { "" }.toLongOrNull(),
            modifiedAt = parts.getOrElse(2) { "" }.toLongOrNull(),
            perm = parts.getOrElse(3) { "" }.trim(),
            owner = parts.getOrElse(4) { "" }.trim(),
            symlink = "",
            downloadUrl = "",
            favorite = false,
            remark = "",
            pinned = false
        )
    }
}

/** 命名单元数据（dir / files 两个键共用一套字段）。`is_dir` 那一格实测在这两个数组里都是缺的，
 *  所以目录还是文件由它躺在哪个键里决定，回包里真给了 `is_dir` 时以回包为准。 */
fun btFileEntries(element: JsonElement?, parent: String, isDir: Boolean): List<BtFileEntry> {
    val items = element as? JsonArray ?: return emptyList()
    return items.mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val name = item.textOf("nm", "name")
        if (name.isBlank()) return@mapNotNull null
        BtFileEntry(
            name = name,
            path = btJoinPath(parent, name),
            isDir = item.flagOf("is_dir") ?: isDir,
            sizeBytes = item.numOf("sz", "size")?.toLong(),
            modifiedAt = item.numOf("mt", "mtime")?.toLong(),
            perm = item.textOf("acc", "chmod"),
            owner = item.textOf("user", "chown"),
            // 实测软链那一格回的是 `->/完整路径`，前缀是面板自己加的箭头，界面上还要写「软链 →」，所以这里先剥掉。
            symlink = item.textOf("lnk").removePrefix("->").trim(),
            downloadUrl = item.textOf("durl"),
            favorite = item.textOf("fav") == "1" || item.flagOf("fav") == true,
            remark = item.textOf("rmk"),
            pinned = item.flagOf("top") ?: false
        )
    }
}

/** 文件正文。`data` 那一格是内容本身，`msg` 装的是它（实测这一口两个键都给）。 */
fun btFileBody(root: JsonElement?, path: String): BtFileBody {
    val obj = root as? JsonObject ?: return BtFileBody(path, "", "", null, false, false, 0, null)
    val history = (obj["historys"] as? JsonArray)?.size
        ?: obj.textOf("historys").toIntOrNull()
        ?: 0
    return BtFileBody(
        path = path,
        content = obj["data"].stringContent(),
        encoding = obj.textOf("encoding"),
        sizeBytes = obj.numOf("size")?.toLong(),
        readOnly = obj.flagOf("only_read") ?: false,
        autoSave = obj.flagOf("auto_save") ?: false,
        historyCount = history,
        diskModifiedAt = obj.numOf("st_mtime")?.toLong()
    )
}

fun btFileAccess(root: JsonElement?): BtFileAccess {
    val obj = root as? JsonObject ?: return BtFileAccess("", "", emptyList())
    return BtFileAccess(
        perm = obj.textOf("chmod"),
        owner = obj.textOf("chown"),
        users = (obj["users"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    )
}

/** 回收站列表（`Get_Recycle_bin`）。同样传整包：这一口的数据就在顶层的 `list` 里。 */
fun btRecyclePage(root: JsonElement?): BtRecyclePage {
    val obj = root as? JsonObject ?: return BtRecyclePage(emptyList(), null, BtRecycleCounts(null, null, null, null, null, null, null), true, false)
    val entries = (obj["list"] as? JsonArray)?.mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val rname = item.textOf("rname")
        if (rname.isBlank()) return@mapNotNull null
        BtRecycleEntry(
            rname = rname,
            name = item.textOf("name"),
            originalPath = item.textOf("dname"),
            timeSeconds = item.numOf("time")?.toLong(),
            sizeBytes = item.numOf("size")?.toLong(),
            isDir = item.flagOf("is_dir") ?: false
        )
    }.orEmpty()
    return BtRecyclePage(
        entries = entries,
        total = obj.numOf("all")?.toInt() ?: totalFromPageHtml(obj["page"].let(::btText)),
        counts = BtRecycleCounts(
            all = obj.numOf("all")?.toInt(),
            dir = obj.numOf("dir")?.toInt(),
            file = obj.numOf("file")?.toInt(),
            image = obj.numOf("image")?.toInt(),
            text = obj.numOf("ont_text")?.toInt(),
            db = obj.numOf("db")?.toInt(),
            totalSizeBytes = obj.numOf("all_size")?.toLong()
        ),
        enabled = obj.flagOf("status") ?: true,
        dbEnabled = obj.flagOf("status_db") ?: false
    )
}

/** 文件操作历史（`GetFileHistory`）。`data` 是行，`page` 里有总条数。 */
fun btFileHistoryLines(root: JsonElement?): List<BtFileHistoryLine> =
    ((root as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val log = item.textOf("log")
        if (log.isBlank()) return@mapNotNull null
        BtFileHistoryLine(
            id = item.textOf("id"),
            type = item.textOf("type"),
            log = log,
            time = item.textOf("addtime"),
            username = item.textOf("username")
        )
    }

/**
 * 有编辑历史的文件（`file_history_list`）。
 *
 * 面板这一口的 `filename` 实测**回的是字面量 `undefined`**（前端拼出来脏了的字段），
 * 所以名字一律从 `filepath` 取最后一段，`filename` 只在 filepath 也空时兜底。
 */
fun btFileHistoryFiles(root: JsonElement?): List<BtFileHistoryFile> =
    btItems(root).mapNotNull { item ->
        val path = item.textOf("filepath", "path")
        if (path.isBlank() || path == "undefined") return@mapNotNull null
        BtFileHistoryFile(
            path = path,
            time = item.textOf("time"),
            id = item.textOf("id"),
            versions = (item["historys"] as? JsonArray)?.size ?: 0
        )
    }

/** 收藏目录：这台一个都没建过，两种可能的形状（裸字符串 / 带 path 的对象）都接着。 */
private fun favoritePaths(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { raw ->
        when (raw) {
            is JsonObject -> raw.textOf("path", "name", "dir", "title")
            else -> btText(raw)
        }
    }.filter { it.isNotBlank() }
    else -> emptyList()
}

fun btPathSize(root: JsonElement?): BtPathSize {
    val obj = root as? JsonObject ?: return BtPathSize("", null)
    return BtPathSize(obj.textOf("path"), obj.numOf("size")?.toLong())
}

fun btPathProbe(root: JsonElement?): BtPathProbe {
    val obj = root as? JsonObject ?: return BtPathProbe("", false, false)
    return BtPathProbe(
        path = obj.textOf("path"),
        exists = obj.flagOf("exists") ?: false,
        isDir = obj.flagOf("is_dir") ?: false
    )
}

/** 目标目录里已经有同名东西时回一行，没有就回空数组（实测）。 */
fun btExistingFiles(root: JsonElement?): List<BtExistingFile> = btItems(root).map { item ->
    BtExistingFile(
        name = item.textOf("filename", "name"),
        sizeBytes = item.numOf("size")?.toLong(),
        isDir = item.flagOf("is_dir") ?: false
    )
}

private fun pathRecords(element: JsonElement?): List<BtPathRecord> =
    btItems(element).mapNotNull { item ->
        val value = item.textOf("val", "path", "name")
        if (value.isBlank()) null else BtPathRecord(value, item.numOf("time")?.toLong())
    }

private fun itemCount(element: JsonElement?): Int = (element as? JsonArray)?.size ?: 0

// ── 路径 ──

/**
 * 拼绝对路径。父路径已经带斜杠时不重复拼，末尾斜杠先去掉。
 * 名字里带 `../` 的输入不会在这里被拼进路径——那一层由 [BtFilesForm] 在提交前拦掉。
 */
fun btJoinPath(parent: String, name: String): String {
    val base = parent.trimEnd('/')
    val child = name.trim().trimStart('/')
    return when {
        base.isEmpty() -> "/" + child
        child.isEmpty() -> base.ifBlank { "/" }
        else -> "$base/$child"
    }
}

/** 上一层。`/www` 的父级是 `/`，`/` 的父级还是 `/`。 */
fun btParentPath(path: String): String {
    val base = path.trimEnd('/')
    if (base.isEmpty() || base == "/") return "/"
    val cut = base.lastIndexOf('/')
    return if (cut <= 0) "/" else base.substring(0, cut)
}

/** 路径按斜杠切开做面包屑。第一项永远是「根」，最后一个是当前这层。 */
fun btPathCrumbs(path: String): List<Pair<String, String>> {
    val base = path.trimEnd('/')
    if (base.isBlank() || base == "/") return listOf("/" to "/")
    val parts = base.split("/").filter { it.isNotBlank() }
    var accumulated = ""
    return parts.map { part ->
        accumulated += "/" + part
        part to accumulated
    }
}

fun btFileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** 两个路径是不是同一层：只认末尾斜杠和大小写完全一致，别拿前缀糊过去。 */
private fun samePath(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

/** 总条数只写在 `page`/`PAGE` 那串 HTML 的「共31条」里。 */
private fun totalFromPageHtml(html: String): Int? =
    Regex("""共\s*(\d+)\s*条""").find(html)?.groupValues?.get(1)?.toIntOrNull()

private fun btEpochToText(seconds: Long): String {
    // 面板给的是本地时区墙上时间的秒数（实测 `mt` 换算出来和网页显示那格一致），直接格式化。
    val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(seconds * 1000))
}

private fun JsonElement?.stringContent(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> btText(this)
}

private fun JsonObject.textOf(vararg keys: String): String {
    for (key in keys) {
        val value = btText(this[key])
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun JsonObject.numOf(vararg keys: String): Double? {
    for (key in keys) {
        val element = this[key] ?: continue
        val value = when (element) {
            is JsonPrimitive -> element.contentOrNull?.toDoubleOrNull() ?: (element.doubleOrNullSafe())
            else -> null
        }
        if (value != null) return value
    }
    return null
}

private fun JsonPrimitive.doubleOrNullSafe(): Double? = runCatching { content.toDouble() }.getOrNull()

private fun JsonObject.flagOf(key: String): Boolean? = when (val element = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> element.booleanOrNull
        ?: element.contentOrNull?.trim()?.let { raw ->
            when (raw.lowercase()) {
                "true", "1" -> true
                "false", "0", "" -> false
                else -> null
            }
        }
    else -> null
}

// ── 表单校验 ──

/**
 * 文件管理器的表单校验。面板那一侧查得严（路径歪了整条不收，回一句「指定目录不存在」或
 * 干脆给个 Python 回溯），所以能在这里挡住的先在这里挡。
 */
object BtFilesForm {

    /** 绝对路径：必须以 `/` 开头，不能带 `..` 往上跳。 */
    fun pathError(raw: String): String? {
        val path = raw.trim()
        return when {
            path.isBlank() -> "路径是空的"
            !path.startsWith("/") -> "路径要从 / 开头写"
            path.contains("..") -> "路径里不要写 ..，要往上层用面包屑"
            path.contains("//") -> "路径里有两个连着的斜杠"
            else -> null
        }
    }

    /** 目录名、文件名：这一格是给在当前目录下的，不能塞斜杠。 */
    fun nameError(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isBlank() -> "名字是空的"
            name == "." || name == ".." -> "这个名字是相对路径的写法，面板不认"
            name.contains("/") -> "名字里不要带斜杠，这一格只写文件（目录）本身的名字"
            name.length > 80 -> "名字太长了，面板那一格放不下"
            else -> null
        }
    }

    /** 权限位：只认三到四位八进制，`755`、`0644` 都行，`rwxr-xr-x` 面板不收。 */
    fun permError(raw: String): String? {
        val perm = raw.trim()
        return when {
            perm.isBlank() -> "权限位是空的"
            !perm.all { it in '0'..'7' } -> "权限只写八进制数字，比如 755 或 0644"
            perm.length < 3 || perm.length > 4 -> "权限位要三到四位数字"
            else -> null
        }
    }

    /** 移动/复制的目标：给的是「放进哪个目录」，不是新路径。 */
    fun targetDirError(raw: String): String? = pathError(raw)
}

/** 文本编辑器的体积上限。超过这个数面板自己也不给编辑，界面上先说清楚。 */
const val MaxEditableBytes: Long = 2L * 1024 * 1024

/**
 * 常用目录。这一排不是猜的，是照着 `GetDirNew` 读到的实际布局摆的：
 * `/www` 下面是 `wwwroot`（九个小站都在这）、`server`（面板本体和 MySQL 数据）、
 * `backup`（备份落这里）、`wwwlogs`、`.Recycle_bin`（回收站的实物就在这，界面上不直接进，走回收站那一页）。
 */
val BtQuickRoots: List<Pair<String, String>> = listOf(
    "/www/wwwroot" to "网站根目录",
    "/www/server/panel" to "面板本体",
    "/www/server/data" to "MySQL 数据",
    "/www/backup" to "备份目录",
    "/www/wwwlogs" to "网站日志",
    "/tmp" to "临时目录",
    "/" to "根目录"
)

/**
 * 这台面板做不到的事，全部是实测撞过的：
 * 写在这一张卡上而不是散在代码注释里，是为了让人点开就看见，不用试。
 */
val BtFilesLimits: List<Pair<String, String>> = listOf(
    "压缩（打包成 zip）" to "files 模块里没有这个 action。`mutil_unzip` 只能解压，反向的那一口在面板网页上是走后台任务队列的，接口不给。",
    "生成下载直链" to "文档里那条 `create_download_url` 实测回 HTTP 400 空 body；`remove_download_url` 在目录里，但没有生成的口，删也就无从删起。",
    "看后台任务跑到哪一步" to "解压、批量删除这些活落在面板的任务队列里，`/task?action=get-task-lists` 以及能想到的别名实测全部读不出来，所以这一页只能报「指令已经发出去了」。",
    "目录置顶" to "列表里那一格 `top` 读得出来，但实测怎么发都不变，界面上只当读数。",
    "把回收站按目录筛" to "回包给了 `dir`/`file` 两个计数，可 `search` 那一格实测只匹配条目的原始名，做不到「只看某个目录删进来的那些」。"
)
