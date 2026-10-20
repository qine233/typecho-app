package noline.zhouzhou.data.remote

import kotlinx.serialization.json.JsonElement
import noline.zhouzhou.data.model.btText

/**
 * 文件管理器（`POST /files`）的有类型调用。
 *
 * 每一条都在正式面板 13.1.0 上实测过（建一次性目录 `/tmp/aa-test-files`，跑完
 * 建目录→建文件→写正文→复制→改名→软链→改权限→删进回收站→恢复→彻底删），
 * 实测出来的八条规矩决定了这里的参数名：
 *
 * 1. **建目录/建文件/读正文/写正文，那一格都叫 `path`**，且是**完整路径**（含文件名）。
 *    `CreateFile` 只要 `path` 一个参数就建出一个空文件（实测回「文件创建成功!」）；
 * 2. **读权限的口要 `filename`，写权限的口也要 `filename`**，可这两个 `filename` 装的其实是完整路径；
 *    反过来 [deleteFile] 那一格叫 `path`。同一件事三套键名，发错一个就是 HTTP 200 + 空 body 的静默无操作，
 *    所以每个方法只留实测通了的那一个名字；
 * 3. **改名和移动是同一个 `MvFile{sfile,dfile}`，`dfile` 必须是目标全路径。**
 *    实测 `dfile` 给成「目标目录」时面板直接回「移动文件或目录失败!」，不会替你拼文件名，
 *    所以仓库层得自己把 `目标目录/原名` 拼出来（见 [moveInto]）；
 * 4. **递归改权限做不到。** 对目录发 `SetFileAccess{all:1}` 回「设置成功」，
 *    实测它自己的 `chmod` 变成了发过去的那值、子文件仍是原来的 `755/www`。
 *    所以这一发只改点到的那一层，界面上照实说；
 * 5. **回收站那一格 `path` 装的是 `rname`。** 恢复（`Re_Recycle_bin`）和彻底删除（`Del_Recycle_bin`）
 *    都是这个写法：面板存盘用的化名（`_bt_tmp_bt_aa-test-files_bt_sub_t_1792492419.096186` 这种）
 *    塞进 `path`。`Del_Recycle_bin` 发 `rname`/`name`/`file`/`filename` 实测一律 HTTP 400 空 body；
 * 6. **`Re_Recycle_bin` 的 `rpath` 也是「目标全路径」而不是「放进哪个目录」**，
 *    而且目标位置已经有东西时面板会先把它改名成 `原名_时间戳.bak` 再放新的进来（实测）；
 * 7. **`GetFileHistory` 不吃 `limit`。** 带上它 HTTP 400 空 body，只发 `p` 和 `content`；
 * 8. **上传是 `multipart/form-data`**，文件字段固定叫 `zunfile`、目标目录叫 `path`，
 *    签名的两个字段照样得放在 multipart 的字符串段里（走 [BtPanelApi.callUpload]）。
 *
 * 三条**没做成方法**的：
 * · `Recycle_bin`（回收站开关）文档只给 `db` 一个参数、没说开关值发什么，而发错的后果是
 *   「以后删除直接永久删」，这种拿正式环境试不起的口子不编；
 * · `create_download_url`（生成下载直链）实测 HTTP 400 空 body，`remove_download_url` 因此也无从下手；
 * · `send_baota`（把疑似误报的文件发给宝塔官方）—— 那是把服务器上的文件原文发到第三方，
 *   这App里不给按钮。
 */
class BtFilesApi(private val api: BtPanelApi) {

    private suspend fun put(action: String, params: Map<String, String> = emptyMap()): String {
        val reply = api.call(BtModule.Files, action, params)
        return reply.msg.ifBlank { btText(reply.data).ifBlank { "面板已收下这条指令" } }
    }

    // ── 只读 ──

    /**
     * 目录列表。`limit` 实测不看（发 5 回来 31 条），一个大目录一次给完，
     * 是否被截断看回包里的 `is_max`。整包（含 `path` 回声、收藏、最近目录）由调用方从 [BtReply.root] 取。
     */
    suspend fun dir(path: String, search: String = ""): BtReply = api.call(
        BtModule.Files, "GetDirNew",
        buildMap {
            put("path", path)
            put("showhidden", "1")
            if (search.isNotBlank()) put("search", search)
        }
    )

    /** `GetDirNew` 不在了才用这条：同一堆信息，大写键 + 分号拼接串。 */
    suspend fun dirLegacy(path: String, search: String = ""): BtReply = api.call(
        BtModule.Files, "GetDir",
        buildMap {
            put("path", path)
            put("showhidden", "1")
            if (search.isNotBlank()) put("search", search)
        }
    )

    /** 文件正文。`path` 是完整路径；二进制或超大文件面板会自己拒，那句原文由异常带出去。 */
    suspend fun fileBody(path: String): BtReply =
        api.call(BtModule.Files, "GetFileBody", mapOf("path" to path))

    /** 权限、属主，外加这台机器的系统用户名单（实测 29 个，属主下拉就从这里来）。 */
    suspend fun fileAccess(path: String): BtReply =
        api.call(BtModule.Files, "GetFileAccess", mapOf("filename" to path))

    /**
     * 改权限 + 属主。`formmode`/`jobm` 是面板网页表单带的两个模式位，实测原样发 `1`/`0` 能通，
     * 这里就照它发；`all` 那格别指望它递归（见类注释第 4 条）。
     */
    suspend fun setAccess(path: String, access: String, user: String): String = put(
        "SetFileAccess",
        mapOf(
            "filename" to path,
            "access" to access,
            "user" to user,
            "formmode" to "1",
            "jobm" to "0",
            "all" to "0"
        )
    )

    /**
     * 这一层有多占地方。实测 `/tmp` 回 3772868 字节。
     * 目录列表里那个 `sz` 是目录 inode 自身的大小，看占用只能走这条。
     */
    suspend fun pathSize(path: String): BtReply =
        api.call(BtModule.Files, "get_path_size", mapOf("path" to path))

    /** 路径探测：整包是 `{path,is_dir,exists}`。 */
    suspend fun exists(path: String): BtReply =
        api.call(BtModule.Files, "test_path", mapOf("path" to path))

    /**
     * 目标目录里是否已经躺着这个文件名（复制/上传前拿来提醒冲突）。
     * 实测回 `[{"filename":"a.txt","size":27,"mtime":"1792492375","is_dir":false}]`；
     * 多个文件名的写法文档没交代清楚，所以这里一次只问一个。
     */
    suspend fun existingFile(dir: String, name: String): JsonElement =
        api.call(BtModule.Files, "CheckExistsFiles", mapOf("dfile" to dir, "filename" to name)).data

    /** 收藏目录名单。这台一个都没建过，实测回空数组。 */
    suspend fun stores(): JsonElement = api.call(BtModule.Files, "get_files_store").data

    /** 文件操作历史（面板全站流水，不只本机）。整包从 [BtReply.root] 取，`data` 是行、`page` 有总数。 */
    suspend fun fileHistory(page: Int, content: String = ""): BtReply = api.call(
        BtModule.Files, "GetFileHistory",
        buildMap {
            put("p", page.toString())
            // 只发列目录那两格。带上 limit 实测 HTTP 400 空 body（类注释第 7 条）。
            if (content.isNotBlank()) put("content", content)
        }
    )

    /** 哪些文件在面板里存着编辑历史。 */
    suspend fun historyFiles(): JsonElement = api.call(BtModule.Files, "file_history_list").data

    /** 回收站列表：`p` 从 1 起，每页固定 10 条；`search` 只匹配原始名（不是路径）。 */
    suspend fun recycleBin(page: Int, search: String = ""): BtReply = api.call(
        BtModule.Files, "Get_Recycle_bin",
        buildMap {
            put("p", page.toString())
            if (search.isNotBlank()) put("search", search)
        }
    )

    /** 这个目录在面板的同步工具里是什么状态（实测这台 `have_sync:false`）。 */
    suspend fun syncStatus(path: String): JsonElement =
        api.call(BtModule.Files, "get_bt_sync_status", mapOf("path" to path)).root

    /** Composer 版本号。面板把它塞在 msg 位（实测 `2.0.14`）。 */
    suspend fun composerVersion(): String =
        api.call(BtModule.Files, "get_composer_version").msg

    /** 已配置的备份方案。这台回的是空数组，装在 msg 位。 */
    suspend fun backups(): JsonElement = api.call(BtModule.Files, "list_backups").root

    /** 目录里的视频文件（面板给在线播放用）。 */
    suspend fun videos(path: String): JsonElement =
        api.call(BtModule.Files, "get_videos", mapOf("path" to path)).data

    // ── 写 ──

    /** `path` 是「新目录的完整路径」，父级必须已经存在，面板不替你逐级建。 */
    suspend fun createDir(path: String): String = put("CreateDir", mapOf("path" to path))

    /** 只给 `path` 就建一个空文件（实测）。 */
    suspend fun createFile(path: String): String = put("CreateFile", mapOf("path" to path))

    /** 保存正文。`encoding` 用读回来那个，别自己换。 */
    suspend fun saveBody(path: String, data: String, encoding: String): String = put(
        "SaveFileBody",
        mapOf("path" to path, "data" to data, "encoding" to encoding.ifBlank { "utf-8" })
    )

    /** 复制。`dfile` 是**目标全路径**（实测），给成目录面板不收。 */
    suspend fun copy(source: String, target: String): String =
        put("CopyFile", mapOf("sfile" to source, "dfile" to target))

    /** 改名 / 移动。`dfile` 同样是目标全路径。 */
    suspend fun move(source: String, target: String): String =
        put("MvFile", mapOf("sfile" to source, "dfile" to target))

    /**
     * 把某个对象搬进另一个目录：`MvFile` 只认全路径，所以目标路径在这里拼出来。
     * 目标目录里已经有同名的，面板会自己决定覆盖还是报错，那句原文由异常带出去。
     */
    suspend fun moveInto(source: String, dir: String, name: String): String =
        move(source, noline.zhouzhou.data.model.btJoinPath(dir, name))

    /** 软链接。`dfile` 是链接本身的全路径（实测建成之后 `test_path` 会解析到源文件，那是应该的）。 */
    suspend fun link(source: String, target: String): String =
        put("CreateLink", mapOf("sfile" to source, "dfile" to target))

    /** 删除文件：进回收站，msg 是「已将文件移动到回收站!」。 */
    suspend fun deleteFile(path: String): String = put("DeleteFile", mapOf("path" to path))

    /** 删除目录：同样进回收站。 */
    suspend fun deleteDir(path: String): String = put("DeleteDir", mapOf("path" to path))

    /**
     * 从回收站放回原处。`rname` 是列表里那个存盘化名。
     * [to] 非空时是**目标全路径**（含新名字），面板会把占位的东西改名成 `xxx_时间戳.bak`。
     */
    suspend fun restore(rname: String, to: String = ""): String = put(
        "Re_Recycle_bin",
        buildMap {
            put("path", rname)
            if (to.isNotBlank()) put("rpath", to)
        }
    )

    /** 彻底删除回收站里的这一条。那一格叫 `path`，装的是 `rname`（见类注释第 5 条）。 */
    suspend fun purge(rname: String): String = put("Del_Recycle_bin", mapOf("path" to rname))

    /**
     * 清空整个回收站。**这条一次都没在正式面板上按过**：这台回收站里躺着 1545 条别人的记录，
     * 拿它试手等于删掉别人的数据。参数按文档发，界面上要输入关键字才按得动。
     */
    suspend fun clearRecycleBin(): String = put("Close_Recycle_bin")

    /**
     * 批量解压。`sfile_list` 是 JSON 数组字符串，`type1` 传压缩格式（zip / gz / tar…）。
     *
     * 实测对**不是压缩包的文件**发这一口，面板不报错，它建出一个同名的空目录当解压目标
     * （`aa-test.zip` 那次就是这么来的），所以界面上只把压缩格式那几个后缀列出来让人选。
     */
    suspend fun unzip(sources: List<String>, targetDir: String, coding: String, type: String): String = put(
        "mutil_unzip",
        mapOf(
            "sfile_list" to "[" + sources.joinToString(",") { "\"${it.jsonEscaped()}\"" } + "]",
            "dfile" to targetDir,
            "coding" to coding,
            "type1" to type
        )
    )

    /** 上传一个文件到 `path` 目录下。文件字段叫 `zunfile`，走 multipart。 */
    suspend fun upload(dir: String, part: BtUploadPart): String {
        val reply = api.callUpload(BtModule.Files, "UploadFile", mapOf("path" to dir), part)
        return reply.msg.ifBlank { "文件已经交给面板上传" }
    }

    /**
     * Webshell 扫描。结果只落在面板自己的安全日志里，
     * `ActionTask` / `/task get-task-lists` 实测读不出队列，所以这一发只能报「发出去了」。
     */
    suspend fun scanDir(path: String): String = put("dir_webshell_check", mapOf("path" to path))

    suspend fun scanFile(path: String): String = put("file_webshell_check", mapOf("filename" to path))

    /** 装 RAR 支持（unrar）。这是往那台机器上装东西，界面上要确认。 */
    suspend fun installRar(): String = put("install_rar")

    /** 更新 Composer 本体。同样是动那台机器的全局工具。 */
    suspend fun updateComposer(): String = put("update_composer")

    private fun String.jsonEscaped(): String = buildString {
        for (ch in this@jsonEscaped) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(ch)
        }
    }
}
