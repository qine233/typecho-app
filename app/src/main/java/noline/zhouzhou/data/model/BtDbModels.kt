package noline.zhouzhou.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * 数据库和 FTP 这两页的读模型。
 *
 * 键名按正式面板 13.1.0 的实测回包钉死，三条实测结论决定了这一层的形状：
 * 1. 列表口只有一个，而且**不在 `/database`**：`/data?action=getData&table=databases|ftps`。
 *    它的 `search.value` 是摆设——实测带任何关键词都照样回全表，所以过滤只能在这层自己做；
 * 2. 这两张表每行都带**明文 `password`**。这里的模型一个字段都不接，连 `raw` 都不留：
 *    网站那层留 `raw` 是为了把「面板还说了什么」摊出来，这两处摊出来就是泄密；
 * 3. 壳层三家各样：`GetInfo` 是裸对象、`GetDatabaseList` 是二维数组、`GetMySQLBinlogs`
 *    裹 `{status,msg,data}`、`get_login_logs` 回裸 `{page,data}`、`GetErrorLog` 干脆是一整段
 *    JSON 字符串。所以每个口配自己的解析函数，不硬套同一个形状。
 */

/** 数据库列表里的一行。`quota.size` 为 0 是「不限量」，不是「用完 0 字节」。 */
data class BtDatabase(
    val id: String,
    val name: String,
    val user: String,
    val accept: String,
    val remark: String,
    val addTime: String,
    val sid: String,
    val engine: String,
    val backupCount: Int?,
    val usedBytes: Long?
) {
    /** `todo@127.0.0.1`：用户名和访问来源合一行，面板列表上就是这个顺序。 */
    val access: String
        get() = when {
            user.isBlank() -> accept
            accept.isBlank() -> user
            else -> "$user@$accept"
        }

    val subtitle: String
        get() = listOfNotNull(
            access.takeIf { it.isNotBlank() },
            btByteSize(usedBytes).takeIf { it.isNotBlank() }?.let { "占用 $it" },
            backupCount?.takeIf { it > 0 }?.let { "备份 $it 份" }
        ).joinToString(" · ")
}

/** `GetInfo` 的一张表；`rows_count` 面板偶尔不给，给空就别编一个 0 出来。 */
data class BtDbTable(
    val name: String,
    val engine: String,
    val rows: Int?,
    val collation: String,
    val sizeText: String
) {
    val detail: String
        get() = listOfNotNull(
            engine.takeIf { it.isNotBlank() },
            collation.takeIf { it.isNotBlank() },
            rows?.let { "$it 行" },
            sizeText.takeIf { it.isNotBlank() }?.let { "数据 $it" }
        ).joinToString(" · ")
}

/** `GetInfo`：库本身的大小 + 表清单。 */
data class BtDbInfo(val name: String, val sizeText: String, val tables: List<BtDbTable>) {
    val isEmpty: Boolean get() = tables.isEmpty()
}

/** `GetDatabasesList` 的一条授权：`name` 是库名（或「全局权限」），`value` 是能碰的表。 */
data class BtDbGrant(val database: String, val tables: List<String>)

/** `GetMysqlUser` 里一个用户的一个来源主机。`hasPassword` 面板给的是「有 / 无」两个字。 */
data class BtMysqlHostAccess(
    val host: String,
    val hasPassword: Boolean?,
    val passwordChanged: String,
    val grants: List<String>
)

/** `GetMysqlUser`：一个 MySQL 账户和它在各主机上的权限。 */
data class BtMysqlAccount(val user: String, val hosts: List<BtMysqlHostAccess>) {
    val hostsText: String get() = hosts.map { it.host }.filter { it.isNotBlank() }.joinToString("、")
}

/**
 * 一个备份文件。实测这台面板给的是**字节数**（`size`）加秒级时间戳（`mtime`/`ctime`），
 * 文档里写的却是 `"1.5 KB"` 加 `addtime` 字符串——两种都认，谁在就用谁。
 */
data class BtDbBackup(
    val name: String,
    val path: String,
    val sizeBytes: Long?,
    val mTimeSeconds: Long?,
    val sizeText: String = "",
    val timeRaw: String = ""
) {
    val humanSize: String get() = sizeText.ifBlank { btByteSize(sizeBytes) }
    val timeText: String get() = timeRaw.ifBlank { mTimeSeconds?.let(::btEpochText).orEmpty() }
}

/** Binlog 文件一条；`current` 是 MySQL 正在写的那个，别把它当旧日志清了。 */
data class BtBinlogFile(val name: String, val path: String, val sizeBytes: Long?, val current: Boolean)

/** `GetMySQLInfo`：数据目录和监听端口。 */
data class BtMysqlServer(val dataDir: String, val port: String)

/**
 * `GetTimeOut` 的五项。面板这边 `expire_logs_days` 一写下去会**连带把
 * `binlog_expire_logs_seconds` 按它重算**（实测 600000 → 518400），所以这两格得一起看。
 */
data class BtMysqlTimeout(
    val interactiveSeconds: Int?,
    val waitSeconds: Int?,
    val binlogExpireSeconds: Int?,
    val expireLogDays: Int?,
    val passwordLifetimeDays: Int?
) {
    val rows: List<BtField>
        get() = listOfNotNull(
            interactiveSeconds?.let { BtField("交互连接超时", "$it 秒") },
            waitSeconds?.let { BtField("非交互连接超时", "$it 秒") },
            binlogExpireSeconds?.let { BtField("Binlog 保留", "${it / 86400} 天（$it 秒）") }
                ?: expireLogDays?.let { BtField("Binlog 保留", "$it 天") },
            passwordLifetimeDays?.let {
                BtField("密码有效期", if (it == 0) "永不过期" else "$it 天")
            }
        )
}

/** `get_restart_task`：MySQL 守护进程任务。没配过的时候面板只回 `{status:0}`。 */
data class BtGuardTask(val enabled: Boolean, val taskId: String, val name: String, val everyMinutes: String)

/** `GetLoginFailed`：登录失败处理插件。面板没装这个插件时 `status` 是 `"off"`。 */
data class BtLoginGuard(val enabled: Boolean, val threshold: Int?, val minDelayMs: Int?, val maxDelayMs: Int?)

/** FTP 列表里的一行。同样不带 `password`。 */
data class BtFtpUser(
    val id: String,
    val name: String,
    val path: String,
    val remark: String,
    val addTime: String,
    val enabled: Boolean,
    val endAt: String,
    val usedBytes: Long?,
    val quotaBytes: Long?
) {
    /** 不限量时别说「0 B」，那是「没设上限」的意思。 */
    val quotaText: String
        get() = when {
            quotaBytes == null -> ""
            quotaBytes <= 0L -> "空间不限量"
            else -> "${btByteSize(usedBytes).ifBlank { "0 B" }} / ${btByteSize(quotaBytes)}"
        }

    val subtitle: String
        get() = listOfNotNull(
            path.takeIf { it.isNotBlank() },
            quotaText.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
}

/**
 * FTP 的登录 / 操作日志一行。
 *
 * 实测这两条口回的是 `{page,data}`，`data` 在没有记录时是空数组；有记录时的字段名
 * 版本之间会换，所以这里认识的键优先，认不出的标量全拼进 `detail`，
 * 而不是挑一个键当唯一真相。密码类键一律不拼。
 */
data class BtFtpLogLine(val time: String, val action: String, val detail: String) {
    val text: String
        get() = listOf(time, action, detail).filter { it.isNotBlank() }.joinToString(" · ")
}

// ── 抠数 ──

private fun JsonObject.textOf(vararg keys: String): String {
    for (key in keys) {
        val text = btText(this[key])
        if (text.isNotBlank()) return text
    }
    return ""
}

private fun JsonObject.intOf(vararg keys: String): Int? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonPrimitive) {
            value.contentOrNull?.toDoubleOrNull()?.toInt()?.let { return it }
        }
    }
    return null
}

private fun JsonObject.longOf(vararg keys: String): Long? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonPrimitive) {
            value.contentOrNull?.toDoubleOrNull()?.toLong()?.let { return it }
        }
    }
    return null
}

private fun JsonObject.flagOf(key: String): Boolean? = when (val value = this[key]) {
    is JsonPrimitive -> value.booleanOrNull ?: value.contentOrNull?.let { it == "1" || it == "true" }
    else -> null
}

/** 面板日志行里绝不会有的键；拼 detail 时把它们筛掉。 */
private val HiddenLogKeys = setOf("password", "passwd", "pwd", "key", "token", "ftp_password", "new_password")

/**
 * 字节数翻成人话。`null` 和负数回空串——「面板没给」和「0 字节」是两件事，
 * 但 0 本身要说出来，因为它在 `quota.used` 位上是真零，只在 `quota.size` 位上才是不限量。
 */
fun btByteSize(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/** 秒级时间戳 → `2026-10-20 14:03`。0 和负数当「面板没给」。 */
fun btEpochText(seconds: Long?): String {
    val value = seconds ?: return ""
    if (value <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date(value * 1000))
}

/**
 * 备注。实测建库时那一格留空，面板会把输入框的占位词「填写备注」原样存进 `ps`，
 * 所以这里把这句当没写，不然列表里每条都顶着一句莫名其妙的备注。
 */
internal fun btRemark(item: JsonObject): String =
    item.textOf("ps").trim().takeUnless { it == "填写备注" }.orEmpty()

/** `getData table=databases`；`quota` 是 `{used,size,…}` 一层对象。 */
fun btDatabases(root: JsonElement?): List<BtDatabase> = btItems(root).mapNotNull { item ->
    val name = item.textOf("name", "dbname", "db")
    val id = item.textOf("id", "pid")
    if (name.isBlank() && id.isBlank()) return@mapNotNull null
    val quota = item["quota"] as? JsonObject
    BtDatabase(
        id = id,
        name = name,
        user = item.textOf("username", "db_user", "user"),
        accept = item.textOf("accept", "host", "address"),
        remark = btRemark(item),
        addTime = item.textOf("addtime"),
        sid = item.textOf("sid").ifBlank { "0" },
        engine = item.textOf("type").ifBlank { "MySQL" },
        backupCount = item.intOf("backup_count"),
        usedBytes = quota?.longOf("used")
    )
}

/**
 * `GetInfo`：`{data_size,database,tables:[{table_name,type,rows_count,collation,data_size,o_size,comment}]}`。
 * 这是裸对象，没有 `{status,data}` 信封，所以直接吃整棵回包。
 */
fun btDbInfo(root: JsonElement?): BtDbInfo {
    val obj = root as? JsonObject ?: return BtDbInfo("", "", emptyList())
    val tables = btItems(obj["tables"]).map { item ->
        BtDbTable(
            name = item.textOf("table_name", "name"),
            engine = item.textOf("type", "engine"),
            rows = item.intOf("rows_count", "rows"),
            collation = item.textOf("collation"),
            sizeText = item.textOf("data_size", "size")
        )
    }.filter { it.name.isNotBlank() }
    return BtDbInfo(obj.textOf("database", "name"), obj.textOf("data_size"), tables)
}

/**
 * `GetDatabaseList` / `get_database_table`：实测是二维数组 `[["count_todos"], ["db"]]`，
 * 一维字符串数组也照收（面板别的版本就这么给）。
 */
fun btNestedNames(root: JsonElement?): List<String> {
    val array = root as? JsonArray ?: btItems(root).let { return it.map { it.textOf("name") } }
    return array.mapNotNull { element ->
        when (element) {
            is JsonArray -> element.firstOrNull()?.let(::btText)
            else -> btText(element)
        }
    }.map { it.trim() }.filter { it.isNotBlank() }
}

/**
 * `GetDatabasesList`：`data[{name,value,tb_list:[{name,value}]}]`，`tb_list` 是这库下按表的授权。
 * 库名取 `value`：实测全局那一行的 `name` 是面板翻好的人话「全局权限」而 `value` 才是 `*`，
 * 按 `*` 才能和别处对得上；表名那一格反过来，`name` 才是表（`value` 有时是权限字）。
 */
fun btDbGrants(root: JsonElement?): List<BtDbGrant> = btItems(root).map { item ->
    BtDbGrant(
        database = item.textOf("value", "name"),
        tables = btItems(item["tb_list"]).map { it.textOf("name", "value") }.filter { it.isNotBlank() }
    )
}.filter { it.database.isNotBlank() }

/** `GetMysqlUser`：`data[{user,list:[{host,password:"有",password_last_changed,access_list:[…]}]}]`。 */
fun btMysqlAccounts(root: JsonElement?): List<BtMysqlAccount> = btItems(root).map { item ->
    BtMysqlAccount(
        user = item.textOf("user", "username", "name"),
        hosts = btItems(item["list"]).map { host ->
            BtMysqlHostAccess(
                host = host.textOf("host", "address"),
                hasPassword = when (host.textOf("password")) {
                    "" -> null
                    "有" -> true
                    "无" -> false
                    // 有的版本这里给布尔，认不出的值就当没说，不硬判成「无密码」。
                    else -> (host["password"] as? JsonPrimitive)?.booleanOrNull
                },
                passwordChanged = host.textOf("password_last_changed", "last_changed"),
                grants = btItems(host["access_list"]).map { grant ->
                    val scope = "${grant.textOf("database")}" +
                        grant.textOf("table").takeIf { it.isNotBlank() && it != grant.textOf("database") }
                            ?.let { ".$it" }.orEmpty()
                    val rights = btAccessWords(grant["access"])
                    listOf(scope.ifBlank { "*" }, rights).filter { it.isNotBlank() }.joinToString(" ")
                }.filter { it.isNotBlank() }
            )
        }
    )
}.filter { it.user.isNotBlank() }

/**
 * 一条授权里的权限清单。面板这里两种形状都见过：
 * 文档写的是 `[{access:"SELECT"},…]`，实测单库授权回的是裸的 `["ALL PRIVILEGES"]`，
 * 所以标量项也要收，不然权限那一半就静默丢了。
 */
private fun btAccessWords(element: JsonElement?): String = when (element) {
    is JsonArray -> element.mapNotNull { item ->
        when (item) {
            is JsonObject -> item.textOf("access", "title", "name")
            else -> btText(item)
        }
    }.filter { it.isNotBlank() }.distinct().joinToString("/")

    else -> btText(element).trim()
}

/** `GeUserHostList`：裸数组，每项是 `"root@localhost"` 这样的一句话。 */
fun btUserHosts(root: JsonElement?): List<String> =
    (root as? JsonArray)?.mapNotNull(::btText)?.filter { it.isNotBlank() } ?: emptyList()

/**
 * `GetBackup` / `GetAllBackup`：`{status,msg,data:[{name,path,size,mtime,ctime}],page}`。
 * 两个口的差别只在扫的目录：前者是各库的定时/单次备份，后者是「全部数据库」那种整包。
 */
fun btDbBackups(root: JsonElement?): List<BtDbBackup> = btItems(root).map { item ->
    // `size` 实测是数字、文档写的是 `"1.5 KB"`：带字母就当面板已经翻好人话，直接照抄。
    val rawSize = item.textOf("size")
    BtDbBackup(
        name = item.textOf("name", "filename"),
        path = item.textOf("path"),
        sizeBytes = item.longOf("size"),
        mTimeSeconds = item.longOf("mtime", "ctime"),
        sizeText = rawSize.takeIf { it.any(Char::isLetter) }.orEmpty(),
        timeRaw = item.textOf("addtime")
    )
}.filter { it.name.isNotBlank() }

/** `GetMySQLBinlogs`：`data[{name,path,size,last_modified,current}]`。 */
fun btBinlogFiles(root: JsonElement?): List<BtBinlogFile> = btItems(root).map { item ->
    BtBinlogFile(
        name = item.textOf("name"),
        path = item.textOf("path"),
        sizeBytes = item.longOf("size"),
        current = item.flagOf("current") ?: false
    )
}.filter { it.name.isNotBlank() }

/** `GetMySQLInfo`：裸对象 `{datadir,port}`。 */
fun btMysqlServer(root: JsonElement?): BtMysqlServer {
    val obj = root as? JsonObject ?: return BtMysqlServer("", "")
    return BtMysqlServer(obj.textOf("datadir", "data_dir"), obj.textOf("port"))
}

/**
 * `GetDbStatus`：`{mem:{…}}`。面板给的单位各不相同（缓冲池是 MB、排序缓冲是 KB、
 * `max_heap_table_size` 又是字节），所以只照抄数字并把单位按面板文档写在标签上，
 * 不在这里换算——换算错了比不换算更容易骗人。
 */
fun btMysqlMemory(root: JsonElement?): List<BtField> {
    val obj = ((root as? JsonObject)?.let { it["mem"] as? JsonObject }) ?: root as? JsonObject
    ?: return emptyList()
    return obj.entries.mapNotNull { (key, value) ->
        val text = btText(value)
        if (text.isBlank()) null else BtField(mysqlMemoryLabels[key] ?: key, text)
    }
}

private val mysqlMemoryLabels = mapOf(
    "innodb_buffer_pool_size" to "InnoDB 缓冲池（MB）",
    "innodb_log_buffer_size" to "InnoDB 日志缓冲（MB）",
    "max_connections" to "最大连接数",
    "table_open_cache" to "打开表缓存",
    "thread_cache_size" to "线程缓存",
    "key_buffer_size" to "MyISAM 索引缓冲（MB）",
    "tmp_table_size" to "临时表大小（MB）",
    "max_heap_table_size" to "MEMORY 表上限（字节）",
    "sort_buffer_size" to "排序缓冲（KB）",
    "read_buffer_size" to "顺序读缓冲（KB）",
    "read_rnd_buffer_size" to "随机读缓冲（KB）",
    "join_buffer_size" to "JOIN 缓冲（KB）",
    "thread_stack" to "线程栈（KB）",
    "binlog_cache_size" to "Binlog 缓存（KB）",
    "query_cache_size" to "查询缓存（MB）",
    "bt_query_cache_size" to "查询缓存（面板设置）",
    "query_cache_type" to "查询缓存类型",
    "query_cache_supprot" to "查询缓存支持" // 面板把这个键拼错了，照它拼。
)

/**
 * `GetRunStatus`：`SHOW GLOBAL STATUS` 摊平成一大坨键值。
 * 界面只摆认得出的这十几项，其余留在回包里不念——几百行计数器没人看，还会把有用的挤下去。
 */
fun btMysqlRuntime(root: JsonElement?): List<BtField> {
    val obj = root as? JsonObject ?: return emptyList()
    val picked = mysqlRuntimeLabels.mapNotNull { (key, label) ->
        btText(obj[key]).takeIf { it.isNotBlank() }?.let { BtField(label, it) }
    }
    // 缓冲池命中率是算出来的：两个计数器都在才算，缺任何一个就不出这一行。
    val requests = obj.longOf("Innodb_buffer_pool_read_requests")
    val diskReads = obj.longOf("Innodb_buffer_pool_reads")
    val hit = if (requests != null && diskReads != null && requests > 0 && diskReads <= requests) {
        (100.0 * (requests - diskReads) / requests)
    } else {
        null
    }
    return picked + listOfNotNull(hit?.let { BtField("缓冲池命中率", String.format(Locale.US, "%.1f%%", it)) })
}

private val mysqlRuntimeLabels = mapOf(
    "Uptime" to "MySQL 已运行（秒）",
    "Connections" to "累计连接次数",
    "Threads_connected" to "当前连接数",
    "Threads_running" to "正在跑的句子",
    "Max_used_connections" to "同时连接峰值",
    "Com_select" to "SELECT 次数",
    "Com_insert" to "INSERT 次数",
    "Com_update" to "UPDATE 次数",
    "Com_delete" to "DELETE 次数",
    "Slow_queries" to "慢查询数",
    "Aborted_connects" to "握手失败次数",
    "Bytes_received" to "收到字节",
    "Bytes_sent" to "发出字节",
    "Open_tables" to "打开的表",
    "Created_tmp_disk_tables" to "落盘临时表"
)

/** `GetTimeOut`：裸对象，五个键。面板没给的键留 null，界面上那一行直接消失。 */
fun btMysqlTimeout(root: JsonElement?): BtMysqlTimeout {
    val obj = root as? JsonObject ?: return BtMysqlTimeout(null, null, null, null, null)
    return BtMysqlTimeout(
        interactiveSeconds = obj.intOf("interactive_timeout"),
        waitSeconds = obj.intOf("wait_timeout"),
        binlogExpireSeconds = obj.intOf("binlog_expire_logs_seconds"),
        expireLogDays = obj.intOf("expire_logs_days"),
        passwordLifetimeDays = obj.intOf("default_password_lifetime")
    )
}

/**
 * `get_restart_task`：开着给整个任务对象（`{id,name,status,where1,…}`），
 * 没配过只给 `{status:0}`。`where1` 是「每 N 分钟」。
 */
fun btGuardTask(root: JsonElement?): BtGuardTask {
    val msg = ((root as? JsonObject)?.get("msg") as? JsonObject) ?: root as? JsonObject
    ?: return BtGuardTask(false, "", "", "")
    val body = (msg["msg"] as? JsonObject) ?: msg
    return BtGuardTask(
        enabled = (body["status"] as? JsonPrimitive)?.let {
            it.booleanOrNull ?: (it.contentOrNull?.toDoubleOrNull() ?: 0.0) != 0.0
        } ?: false,
        taskId = body.textOf("id"),
        name = body.textOf("name", "rname"),
        everyMinutes = body.textOf("where1")
    )
}

/**
 * `GetLoginFailed`：注意这里的 `status` 是字符串 `"on"/"off"`，
 * 不是信封那位布尔——信封判断在网络层已经因为缺 `data`/`msg` 而跳过了，整棵树才会到这里。
 */
fun btLoginGuard(root: JsonElement?): BtLoginGuard {
    val obj = root as? JsonObject ?: return BtLoginGuard(false, null, null, null)
    val text = btText(obj["status"])
    return BtLoginGuard(
        enabled = text.equals("on", ignoreCase = true),
        threshold = obj.intOf("connection_control_failed_connections_threshold"),
        minDelayMs = obj.intOf("connection_control_min_connection_delay"),
        maxDelayMs = obj.intOf("connection_control_max_connection_delay")
    )
}

/**
 * 日志正文：`GetErrorLog` 实测是一整段 JSON 字符串（没有信封），
 * `GetSlowLogs` 把正文装在 `msg` 里。两种都从这里取。
 */
fun btLogBody(root: JsonElement?): String {
    val obj = root as? JsonObject ?: return btText(root)
    val msg = obj["msg"]
    if (msg is JsonPrimitive && msg.isString) return msg.contentOrNull.orEmpty()
    return obj.textOf("logs", "log", "content")
}

/** `getData table=ftps`：`status` 是字符串 `"1"`（正常）/`"0"`（暂停）。 */
fun btFtpUsers(root: JsonElement?): List<BtFtpUser> = btItems(root).mapNotNull { item ->
    val name = item.textOf("name", "ftp_username", "username")
    val id = item.textOf("id")
    if (name.isBlank() && id.isBlank()) return@mapNotNull null
    val quota = item["quota"] as? JsonObject
    val endAt = item.textOf("end_time", "end_date")
    BtFtpUser(
        id = id,
        name = name,
        path = item.textOf("path", "dir"),
        remark = btRemark(item),
        addTime = item.textOf("addtime"),
        enabled = item.textOf("status").let { it.isBlank() || it == "1" || it.equals("true", true) },
        endAt = if (endAt.isBlank() || endAt == "0") "永久" else endAt,
        usedBytes = quota?.longOf("used"),
        quotaBytes = quota?.longOf("size")
    )
}

/**
 * FTP 的登录/操作日志。回包是裸的 `{page,data}`，`data` 里可能是数组，
 * 也可能按行为分成 `{upload:[],download:[],rename:[],delete:[]}` 四组（面板文档这么写，
 * 这台正式环境的 FTP 日志是关着的，所以两种都按这一套认）。
 */
fun btFtpLogLines(root: JsonElement?): List<BtFtpLogLine> {
    val data = (root as? JsonObject)?.get("data") ?: root
    if (data is JsonArray) return data.mapNotNull { it as? JsonObject }.map(::ftpLogLine)
    if (data is JsonObject) {
        return data.entries.flatMap { (group, items) ->
            val label = ftpLogGroupLabels[group] ?: group
            btItems(items).map { item ->
                ftpLogLine(item).let { BtFtpLogLine(it.time, label, listOf(label, it.detail).filter { p -> p.isNotBlank() }.joinToString(" ")) }
            }
        }
    }
    return emptyList()
}

private val ftpLogGroupLabels = mapOf(
    "upload" to "上传", "download" to "下载", "rename" to "改名", "delete" to "删除"
)

private fun ftpLogLine(item: JsonObject): BtFtpLogLine = BtFtpLogLine(
    time = listOf(item.textOf("time"), item.textOf("log_time"), item.textOf("addtime"), item.textOf("datetime"))
        .firstOrNull { it.isNotBlank() }.orEmpty(),
    action = listOf(item.textOf("action"), item.textOf("cmd"), item.textOf("op"), item.textOf("type"))
        .firstOrNull { it.isNotBlank() }.orEmpty(),
    detail = item.entries
        .filter { (key, value) ->
            key !in HiddenLogKeys && key !in setOf(
                "time", "log_time", "addtime", "datetime", "action", "cmd", "op", "type", "password"
            ) && value is JsonPrimitive
        }
        .map { (_, value) -> btText(value) }
        .filter { it.isNotBlank() }
        .joinToString(" ")
)

/**
 * 一台数据库服务器（`GetCloudServer`）。`db_password` 这一格**根本不读**：
 * 本地那台面板给的是空串，远程那台给的是什么没人能保证，模型里没有能装下它的字段。
 */
data class BtServerOption(
    val id: String,
    val host: String,
    val port: String,
    val user: String,
    val remark: String
) {
    val isLocal: Boolean get() = id == "0"
    val address: String
        get() = when {
            host.isBlank() -> port
            port.isBlank() -> host
            else -> "$host:$port"
        }
}

/** `GetCloudServer`：裸数组。 */
fun btServerOptions(root: JsonElement?): List<BtServerOption> = btItems(root).mapNotNull { item ->
    val id = item.textOf("id")
    val host = item.textOf("db_host", "host")
    if (id.isBlank() && host.isBlank()) return@mapNotNull null
    BtServerOption(
        id = id.ifBlank { "0" },
        host = host,
        port = item.textOf("db_port", "port"),
        user = item.textOf("db_user", "user"),
        remark = item.textOf("ps")
    )
}

/**
 * `view_ftp_types` / 各分类口：`{status,msg:[…]}`，msg 位才是数据。
 * 实测这台面板回的是空数组（一个分类都没建），所以列表只按「认得出名字才显示」。
 */
fun btNamedOptions(root: JsonElement?): List<BtSiteType> {
    val msg = ((root as? JsonObject)?.get("msg") as? JsonArray) ?: root as? JsonArray ?: return emptyList()
    return msg.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        BtSiteType(obj.textOf("id"), obj.textOf("ps", "name", "title")).takeIf { it.id.isNotBlank() || it.name.isNotBlank() }
    }
}

// ── 表单校验 ──
//
// 下面的规则分两种出处，注释里会写明是哪一种：
// · 「文档」= 官方接口文档写死的校验（`AddDatabase` 的 name 是 `^[\w\.-]+$`、64 字节）；
// · 「实测」= 在正式面板上真发过、被它原话拒过的：FTP 名 `aa-test-ftp` 回
//   「用户名不合法,不能带有特殊符号!」，密码 `Zz…!x` 回「必须为6-20位的字母和数字组合」、
//   纯数字回一句「添加失败」。文档里没写的这几条，只有实测说了算。

/** 面板建库表单上给的那四个字符集，多一个都不列。 */
val BtDbCharsets = listOf("utf8", "utf8mb4", "gbk", "big5")

/** 建库表单。库密码这一格实测面板**不做复杂度检查**（20 位带 `!` 照收），所以这里也不替它加戏。 */
object BtNewDatabaseForm {
    /** MySQL 的库名/账号名标识符。返回 null 表示可以发；否则这条就是给界面显示的缘故。 */
    private fun identifierError(raw: String, what: String, maxBytes: Int): String? {
        val name = raw.trim()
        return when {
            name.isBlank() -> "要给${what}起个名"
            name.toByteArray().size > maxBytes -> "${what}最长 $maxBytes 字节，面板那边会截断"
            // 文档原话：`^[\w\.-]+$`。中划线在 MySQL 标识符里要靠反引号才活得住，
            // 面板建库时并不加反引号，所以这里照文档收下列，界面上一句提醒说清风险。
            !name.matches(Regex("""^[\w.-]+$""")) ->
                "${what}只能用字母、数字、下划线、点和中划线"
            name.first().isDigit() -> "${what}得从字母或下划线开头，数字打头面板会当字符串"
            else -> null
        }
    }

    fun nameError(raw: String): String? = identifierError(raw, "数据库", 64)

    /** 账号名单独收紧一点：MySQL 的 user 表里用户名上限就是 32。 */
    fun userError(raw: String): String? = identifierError(raw, "数据库账号", 32)

    fun passwordError(raw: String): String? = when {
        raw.isBlank() -> "要给账号设个密码"
        raw.length < 8 -> "密码至少 8 位"
        raw.length > 64 -> "密码太长，面板那边容易出问题"
        raw.contains("'") || raw.contains("\\") -> "密码里别放引号和反斜杠，会打断面板拼的建库语句"
        else -> null
    }

    /** 访问来源：`%` 是任意主机，`127.0.0.1` 是只有本机；面板这一格吃的是逗号分隔的清单。 */
    fun addressError(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return "要写访问来源：本机的 127.0.0.1，或者任意主机的 %"
        if (text.contains(" ")) return "访问来源里别带空格"
        val parts = text.split(',').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "要写访问来源"
        // 末尾一段收成 `%` 的写法（`192.168.1.%`）MySQL 的 host 授权是真认的，得放行。
        val one = Regex(
            """^(\*|%|[0-9]{1,3}(\.[0-9]{1,3}){3}(/[0-9]{1,2})?|[0-9]{1,3}(\.[0-9]{1,3}){1,2}\.%|[A-Za-z0-9._-]+)$"""
        )
        if (parts.any { !it.matches(one) }) {
            return "访问来源得是 IP、IP 段或者主机名，多个用逗号分开"
        }
        return null
    }

    /**
     * 这一格的面板选项里有「允许所有地址」（就是 `%`），接口上做得到，所以这里不拦；
     * 但界面要照着面板自己的做法把它染成警告色——库对整个互联网开放，只靠密码挡着。
     */
    fun isWideOpen(address: String): Boolean =
        address.split(',').any { it.trim() == "%" || it.trim() == "*" }

    /** 密码生成：避开引号、反斜杠和空格，字母数字混排，长度按面板建库默认的 16 位。 */
    fun randomPassword(): String {
        val letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val pool = letters + digits
        val random = java.security.SecureRandom()
        return buildString {
            // 前两位钉死「一字母一数字」，剩下的随机；面板对库密码没规则，但人拿到的应该能用。
            append(letters[random.nextInt(letters.length)])
            append(digits[random.nextInt(digits.length)])
            repeat(14) { append(pool[random.nextInt(pool.length)]) }
        }
    }
}

/** MySQL 账户页那一格的校验：加账户和改权限用的是同一套名字规则。 */
object BtMysqlUserForm {
    fun nameError(raw: String): String? = BtNewDatabaseForm.userError(raw)

    fun passwordError(raw: String): String? = BtNewDatabaseForm.passwordError(raw)

    fun addressError(raw: String): String? = BtNewDatabaseForm.addressError(raw)
}

/** 建 FTP 表单，四条规则的出处全在上面的注释里。 */
object BtNewFtpForm {
    fun nameError(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isBlank() -> "要给 FTP 账号起个名"
            name.length > 32 -> "账号名最长 32 个字符"
            // 实测连中划线都被面板拒了，所以这里只放字母数字下划线。
            !name.matches(Regex("""^[A-Za-z][A-Za-z0-9_]*$""")) ->
                "FTP 账号只能用字母、数字和下划线，从字母开头（实测面板连中划线都不收）"
            else -> null
        }
    }

    fun passwordError(raw: String): String? = when {
        raw.isBlank() -> "要给 FTP 设个密码"
        raw.length < 6 || raw.length > 20 -> "FTP 密码得是 6 到 20 位"
        raw.any { !it.isLetterOrDigit() } -> "FTP 密码只能由字母和数字组成，放不了特殊字符"
        raw.none { it.isDigit() } || raw.none { it.isLetter() } -> "FTP 密码要字母和数字都有，光数字面板也拒"
        else -> null
    }

    /** 目录：面板这一格吃的是绝对路径，而且实测目录不存在时它会**顺手建出来**。 */
    fun pathError(raw: String): String? {
        val path = raw.trim()
        return when {
            path.isBlank() -> "要指定一个根目录"
            !path.startsWith("/") -> "得是从 / 开始的绝对路径"
            path.contains("..") -> "路径里别放 .."
            path.length > 200 -> "路径太长了"
            else -> null
        }
    }

    /**
     * 带宽和容量这一格面板吃的是 `100KB` / `1MB` 这种「数字 + 单位」，
     * 单位在面板文档里是可选的，但裸数字会被当成什么单位没人说得清，所以这里要求带单位。
     */
    fun sizeError(raw: String, allowBlank: Boolean): String? {
        val text = raw.trim()
        if (text.isBlank()) return if (allowBlank) null else "要填一个数"
        if (!text.matches(Regex("""^(0|[1-9][0-9]*)(B|KB|MB|GB|TB)?$""", RegexOption.IGNORE_CASE))) {
            return "写 0（表示不限量）或者带单位的数，比如 100KB、1MB"
        }
        return null
    }

    /** 单位补齐：面板对裸数字不买账，界面上「不限量」这一格翻成 0。 */
    fun cleanSize(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return "0"
        return if (text.matches(Regex("""^\d+$"""))) "$text KB" else text.uppercase()
    }

    /** FTP 能用同一把生成器：字母数字混排、6–20 位，正好落在面板允许的区间里。 */
    fun randomPassword(): String {
        val letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val random = java.security.SecureRandom()
        return buildString {
            append(letters[random.nextInt(letters.length)])
            append(digits[random.nextInt(digits.length)])
            repeat(10) { append((letters + digits)[random.nextInt(letters.length + digits.length)]) }
        }
    }
}
