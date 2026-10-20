package noline.zhouzhou.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 计划任务这一页的读模型。键名按正式面板 13.1.0 的实测回包钉死，四条实测结论决定了形状：
 *
 * 1. `GetCrontab` 回的是**裸数组**（没有 `{status,msg}` 信封），一行 40 来个键，
 *    文档只写了其中 9 个。剩下那些（`flock`、`second`、`params`、`time_type`、`backup_mode`…）
 *    面板网页的表单每次都原样发回去，所以这里整包留在 [BtCronTask.raw] 里，
 *    改任务时按「原样带回、只覆盖改过的那几格」发——FTP 限速那一格就是栽在只发局部字段上。
 * 2. 同一格在不同任务里**类型不一样**：`save` 在备份任务里是 `3`，在 shell 任务里是 `""`；
 *    `where_hour` 是 `1` 或 `""`；`db_type` 是 `""` 或 `null`。所以能空的都收成字符串，
 *    数字位收成可空，认不出的一律不当 0。
 * 3. `result` 这一格**含义说不清**：这台 8 个任务里 7 个是 `1`，唯一日志里写着「上传失败」的那个
 *    反倒是 `0`。样本只有一个，猜哪个方向都是编，所以模型收了、界面上不摆。
 * 4. `get_crontab_service` 回 `data.status=0`（文档说 0 是已停止），可这台的面板任务每分钟
 *    都在跑（`addtime` 就是刚刚）。这一口的读数不可信，整条链路都没接进来。
 */
data class BtCronTask(
    val id: String,
    /**
     * 界面上那个任务名。**优先取 `rname`**：实测 `modify_crond` 把发过去的 `name` 写进 `rname`
     * 这一列，表里原来的 `name` 从建任务之后再也不变（建 `aa-test-cron`、改名 `aa-test-cron-C`，
     * 回来 `name` 仍是旧值、`rname` 才是新值）。所以改名、发回整行、列表显示，都以 `rname` 为准。
     */
    val name: String,
    /** `name` 那一列：面板建任务时写死，之后再改也不动，只用来兜底和摊「面板没说的键」。 */
    val createdAtName: String,
    /** 周期代码：`minute-n` / `hour-n` / `day` / `day-n` / `week` / `month` / `hour`。 */
    val type: String,
    /** 周期里的那个 N（每 N 分钟 / 每 N 天 / 每周几 / 每月几号），含义跟着 [type] 变。 */
    val where1: String,
    val whereHour: String,
    val whereMinute: String,
    /** 任务种类：`toShell` / `toUrl` / `toPython` / `database` / `site` / `path` / `logs` / `rememory`。 */
    val sType: String,
    /** 备份类任务的目标名（站点名、库名、目录，或 `ALL`）；脚本类这一格是空的。 */
    val target: String,
    val script: String,
    val urlAddress: String,
    val dbType: String,
    val tableList: String,
    val enabled: Boolean?,
    /** 备份保留份数；非备份任务面板给空串，这里就是 null。 */
    val keepBackups: Int?,
    val backupTo: String,
    val keepLocal: Boolean?,
    /** 以哪个系统用户跑（实测这台是 `root`）。 */
    val runAs: String,
    val categoryId: String,
    val categoryName: String,
    /** `echo`：面板给这个任务落的脚本文件名，日志路径就是它拼出来的。 */
    val scriptName: String,
    val addTime: String,
    /** 面板自己翻好的周期短语（「每天」「每5分钟」）。 */
    val cycleZh: String,
    /** 面板自己写的整句执行时间说明，比我们自己拼的准。 */
    val cycleSentence: String,
    val raw: JsonObject
) {
    /** 文档没列、面板却在给的键，摊到详情里说清楚，不假装这行只有我们认得的那几格。 */
    val extraKeys: List<String>

    init {
        extraKeys = raw.keys.filter { it !in KnownCronKeys }.sorted()
    }

    val kindText: String
        get() = btCronKindLabel(sType)

    /** 这一行第二栏：干什么 + 对谁干。 */
    val subtitle: String
        get() = listOfNotNull(
            kindText.takeIf { it.isNotBlank() },
            targetText.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

    /** 任务对象：备份类看 `sName`，脚本类取正文第一行，URL 类看 `urladdress`。 */
    val targetText: String
        get() = when {
            target == "ALL" -> "全部"
            target.isNotBlank() -> target
            urlAddress.isNotBlank() -> urlAddress
            script.isNotBlank() -> script.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                .orEmpty()
            else -> ""
        }

    /** 周期：优先用面板那句原话，它没给才按代码自己拼。 */
    val cycleText: String
        get() = cycleSentence.ifBlank { cycleZh.ifBlank { btCronCycleLabel(type, where1) } }

    /** 备份那一串设置；不是备份任务就空着。 */
    val backupText: String
        get() {
            if (!isBackupKind) return ""
            val parts = listOfNotNull(
                keepBackups?.let { "保留 $it 份" },
                btCronBackupTargetText(backupTo).takeIf { it.isNotBlank() },
                keepLocal?.let { if (it) "留服务器一份" else "不留本地" }
            )
            return parts.joinToString(" · ")
        }

    val isBackupKind: Boolean get() = sType in CronBackupKinds

    val statusText: String
        get() = when (enabled) {
            true -> "启用中"
            false -> "已暂停"
            null -> "面板没给状态"
        }

    val detailFields: List<BtField>
        get() = listOfNotNull(
            BtField("周期", cycleText).takeIf { cycleText.isNotBlank() },
            BtField("种类", "$kindText（面板代码 $sType）").takeIf { sType.isNotBlank() },
            BtField("对象", target).takeIf { target.isNotBlank() },
            BtField("访问地址", urlAddress).takeIf { urlAddress.isNotBlank() },
            BtField("数据库类型", dbType).takeIf { dbType.isNotBlank() },
            BtField("指定表", tableList).takeIf { tableList.isNotBlank() },
            BtField("运行身份", runAs).takeIf { runAs.isNotBlank() },
            BtField("分类", categoryName).takeIf { categoryName.isNotBlank() },
            BtField("脚本文件", scriptName).takeIf { scriptName.isNotBlank() },
            BtField("面板写入时间", addTime).takeIf { addTime.isNotBlank() },
            backupText.takeIf { it.isNotBlank() }?.let { BtField("备份", it) }
        )
}

/** 批量操作那四种，`type` 参数实测只认这四个，别的回「参数错误」。 */
enum class BtCronBatchOp(val wire: String, val label: String) {
    Start("start", "启用"),
    Stop("stop", "暂停"),
    Delete("del", "删除"),
    Exec("exec", "立即执行")
}

/** 面板一行里我们认得的键；不在里面的进 [BtCronTask.extraKeys]。 */
private val KnownCronKeys = setOf(
    "id", "name", "rname", "type", "where1", "where_hour", "where_minute", "sType", "sName", "sBody",
    "urladdress", "db_type", "table_list", "status", "save", "backupTo", "save_local", "user",
    "type_id", "type_name", "echo", "addtime", "cycle", "type_zh", "sType"
)

/** 备份类任务，这三种共用「对象 + 保留份数 + 目标」那一套表单。 */
val CronBackupKinds = setOf("database", "site", "path")

/** 面板代码 → 中文。认不出的原样带出来，不硬套一个「未知类型」糊弄过去。 */
fun btCronKindLabel(code: String): String = when (code) {
    "toShell" -> "Shell 脚本"
    "toUrl" -> "访问 URL"
    "toPython" -> "Python 脚本"
    "database" -> "备份数据库"
    "site" -> "备份网站"
    "path" -> "备份目录"
    "logs" -> "日志切割"
    "rememory" -> "释放内存"
    "mysql" -> "备份 MySQL"
    "toPhp" -> "PHP 脚本"
    else -> code
}

/** 周期代码 → 中文；带 N 的那几种把 N 说清楚。 */
fun btCronCycleLabel(type: String, where1: String): String = when (type) {
    "minute-n" -> "每 ${where1.ifBlank { "?" }} 分钟"
    "hour-n" -> "每 ${where1.ifBlank { "?" }} 小时"
    "day-n" -> "每 ${where1.ifBlank { "?" }} 天"
    "minute" -> "每分钟"
    "hour" -> "每小时"
    "day" -> "每天"
    "week" -> "每周${btCronWeekdayText(where1)}"
    "month" -> "每月 ${where1.ifBlank { "?" }} 号"
    else -> type.ifBlank { "面板没给周期" }
}

/** 面板网页上那一列周期，建/改任务时下拉就用它。 */
val BtCronCycles: List<BtSiteType> = listOf(
    BtSiteType("minute-n", "每 N 分钟"),
    BtSiteType("hour-n", "每 N 小时"),
    BtSiteType("day", "每天"),
    BtSiteType("day-n", "每 N 天"),
    BtSiteType("week", "每周"),
    BtSiteType("month", "每月")
)

/** 建/改任务能选的种类，顺序照面板网页那个下拉。 */
val BtCronKinds: List<BtSiteType> = listOf(
    BtSiteType("toShell", "Shell 脚本"),
    BtSiteType("toUrl", "访问 URL"),
    BtSiteType("toPython", "Python 脚本"),
    BtSiteType("database", "备份数据库"),
    BtSiteType("site", "备份网站"),
    BtSiteType("path", "备份目录"),
    BtSiteType("logs", "日志切割"),
    BtSiteType("rememory", "释放内存")
)

/** 备份目标：面板 `orderOpt` 给的是 `value`，这里补一份人话。 */
fun btCronBackupTargetText(code: String): String = when (code) {
    "" -> ""
    "localhost", "local" -> "本机"
    "msonedrive" -> "微软 OneDrive"
    "baidualiyun" -> "百度对象存储"
    "aliyunoss" -> "阿里云 OSS"
    "txcos" -> "腾讯云 COS"
    "upyun" -> "又拍云"
    "jdooss" -> "京东云 OSS"
    "ftps" -> "FTP"
    "remotessh" -> "远程存储服务器"
    "googlecloud" -> "谷歌云"
    "s3" -> "S3 兼容"
    else -> code
}

/** 面板的 `week` 位实测是 0–7（0 和 7 都是周日），照这个翻。 */
private fun btCronWeekdayText(where1: String): String = when (where1.trim()) {
    "0", "7" -> "日"
    "1" -> "一"
    "2" -> "二"
    "3" -> "三"
    "4" -> "四"
    "5" -> "五"
    "6" -> "六"
    "" -> ""
    else -> where1
}

/** 面板级设置：`get_auto_config` 的 `name=site|mysql` 那一格。 */
data class BtCronAutoBackup(val kind: String, val configured: Boolean, val fields: List<BtField>) {
    val summary: String
        get() = if (!configured) "面板里没有这一条自动备份任务" else fields.joinToString(" · ") { "${it.label} ${it.value}" }
}

/** `get_rotate_log_config`：面板日志自己的切割设置。实测 `hour`/`minute` 是字符串。 */
data class BtCronRotateLog(
    val enabled: Boolean?,
    val cycle: String,
    val hour: String,
    val minute: String,
    val keep: Int?,
    val triggerSizeMb: Double?,
    val compress: Boolean?
) {
    val summary: String
        get() = listOfNotNull(
            when (enabled) { true -> "已开启"; false -> "未开启"; null -> "" },
            btCronCycleLabel(cycle, "").takeIf { cycle.isNotBlank() },
            keep?.let { "保留 $it 份" },
            triggerSizeMb?.takeIf { it > 0.0 }?.let { "满 $it MB 就切" },
            compress?.let { if (it) "切割后压缩" else "不压缩" }
        ).filter { it.isNotBlank() }.joinToString(" · ")
}

/** `get_log_path`：日志文件在服务器上的位置和大小。手机上打不开这个路径，只能报出来。 */
data class BtCronLogInfo(val path: String, val sizeText: String) {
    val exists: Boolean get() = path.isNotBlank() && path != "无"
}

/** `GetDataList`：`data` 是可备份的对象名单，`orderOpt` 是能用的备份目标。 */
data class BtCronPickList(val targets: List<BtSiteType>, val backupTargets: List<BtSiteType>)

/**
 * 一个任务的可编辑字段：整包原样收下来，改哪几格由调用方覆盖。
 *
 * 两处必须动手脚，都是实测出来的：
 * · `name` 这格发**界面上那个名字**（[BtCronTask.name]，即 `rname`），不能照抄原始行的
 *   `name` 列——那列是建任务时的旧值，原样发回去等于把上一次改的名悄悄盖回去；
 * · `addtime` 要发回去。实测不带它，面板把这一格写成空格；带着它，改完还是原时间。
 */
fun BtCronTask.editableBody(): Map<String, String> = raw.entries
    .filter { (key, value) -> key in CronEditableKeys && value !is JsonObject && value !is JsonArray }
    .mapNotNull { (key, value) ->
        // 面板给 null 的键别发空串过去：有些格（`db_type`）空串和「没有」在面板那边不是一回事。
        if (value is JsonNull) null else key to btText(value)
    }
    .toMap()
    .plus("name" to name)

/** 改任务时要一起发回去的键，其余（`result`、`cycle`、`type_zh`、`sort` 这些）是面板算出来的。 */
private val CronEditableKeys = setOf(
    "name", "rname", "addtime", "type", "where1", "where_hour", "where_minute", "week", "sType", "sName", "sBody",
    "urladdress", "db_type", "table_list", "status", "save", "backupTo", "save_local", "user",
    "type_id", "flock", "backup_mode", "split_type", "split_value", "stop_site", "log_cut_path",
    "notice", "notice_channel", "params", "post_param", "keyword", "user_agent", "version",
    "time_set", "time_type", "special_time", "second", "echo"
)

// ── 解析 ──

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

private fun JsonObject.doubleOf(vararg keys: String): Double? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonPrimitive) {
            value.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

/**
 * 面板这一层的开关位有三种给法：`1`/`0`、`"1"`/`"0"`、空串。空串和 null 都算「没给」，
 * 不能当 0——把「面板没说」写成「关着」是这层最容易被抓住的假话。
 */
private fun JsonObject.flagOf(vararg keys: String): Boolean? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.booleanOrNull?.let { return it }
        val text = value.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) continue
        if (text == "0") return false
        if (text == "1" || text.equals("true", ignoreCase = true)) return true
    }
    return null
}

private fun JsonObject.cronTask(): BtCronTask? {
    val id = textOf("id")
    val name = textOf("rname", "name")
    if (id.isBlank() && name.isBlank()) return null
    return BtCronTask(
        id = id,
        name = name,
        createdAtName = textOf("name"),
        type = textOf("type"),
        where1 = textOf("where1", "where_1"),
        whereHour = textOf("where_hour"),
        whereMinute = textOf("where_minute"),
        sType = textOf("sType"),
        target = textOf("sName"),
        script = textOf("sBody"),
        urlAddress = textOf("urladdress", "url"),
        dbType = textOf("db_type"),
        tableList = textOf("table_list"),
        enabled = flagOf("status"),
        keepBackups = intOf("save"),
        backupTo = textOf("backupTo", "backup_to"),
        keepLocal = flagOf("save_local"),
        runAs = textOf("user"),
        categoryId = textOf("type_id"),
        categoryName = textOf("type_name"),
        scriptName = textOf("echo"),
        addTime = textOf("addtime", "add_time"),
        cycleZh = textOf("type_zh"),
        cycleSentence = textOf("cycle"),
        raw = this
    )
}

/** `GetCrontab`：裸数组。 */
fun btCronTasks(root: JsonElement?): List<BtCronTask> = btItems(root).mapNotNull { it.cronTask() }

/** `get_crond_find`：单个任务的裸对象。 */
fun btCronTask(root: JsonElement?): BtCronTask? = (root as? JsonObject)?.cronTask()

/** `get_crontab_types`：`{status,msg:[…]}`，msg 位才是分类。 */
fun btCronCategories(root: JsonElement?): List<BtSiteType> = btNamedOptions(root)

/** `get_domain` / `get_system_user_list`：裸字符串数组。 */
fun btCronStringList(root: JsonElement?): List<String> = when (root) {
    is JsonArray -> root.map { btText(it) }.filter { it.isNotBlank() }
    is JsonObject -> btItems(root["data"]).map { it.textOf("name", "value") }.filter { it.isNotBlank() }
    else -> emptyList()
}

/**
 * `GetDataList`：`{data:[{name,ps}],orderOpt:[{name,value,status}]}`。
 * `orderOpt` 名字容易误会成排序选项，实测它是**能用的备份目标清单**（这台只给了 OneDrive）。
 */
fun btCronPickList(root: JsonElement?): BtCronPickList {
    val obj = root as? JsonObject ?: return BtCronPickList(emptyList(), emptyList())
    val targets = btItems(obj["data"]).mapNotNull { item ->
        val name = item.textOf("name", "value")
        name.takeIf { it.isNotBlank() }?.let { BtSiteType(it, item.textOf("ps").ifBlank { it }) }
    }
    val backupTargets = btItems(obj["orderOpt"]).mapNotNull { item ->
        val code = item.textOf("value", "name")
        code.takeIf { it.isNotBlank() }?.let { BtSiteType(it, item.textOf("name").ifBlank { btCronBackupTargetText(it) }) }
    }
    return BtCronPickList(targets, backupTargets)
}

/** `GetDatabases`（裸数组）/ `get_databases`（`{status,msg,data}`）：库 + 它的表清单。 */
fun btCronDatabases(root: JsonElement?): List<Pair<String, List<String>>> {
    val items = when (root) {
        is JsonArray -> btItems(root)
        is JsonObject -> btItems(root["data"])
        else -> emptyList()
    }
    return items.mapNotNull { item ->
        val name = item.textOf("name", "value")
        if (name.isBlank()) return@mapNotNull null
        name to btItems(item["table_list"]).mapNotNull { table ->
            val tb = table.textOf("tb_name", "value")
            tb.takeIf { it.isNotBlank() && it != "所有" }
        }
    }
}

/** `get_log_path`：`{status,msg:{log_path,size}}`；面板说「无」就是真没有。 */
fun btCronLogInfo(root: JsonElement?): BtCronLogInfo {
    val msg = ((root as? JsonObject)?.get("msg") as? JsonObject) ?: root as? JsonObject ?: return BtCronLogInfo("", "")
    return BtCronLogInfo(msg.textOf("log_path", "path"), msg.textOf("size"))
}

/** `get_auto_config`：`msg` 是对象；实测没建过自动备份时它只给一个 `{status:0}`。 */
fun btCronAutoBackup(root: JsonElement?, kind: String): BtCronAutoBackup {
    val msg = ((root as? JsonObject)?.get("msg") as? JsonObject)
    if (msg == null || msg.isEmpty()) return BtCronAutoBackup(kind, false, emptyList())
    val fields = listOfNotNull(
        msg.textOf("name").takeIf { it.isNotBlank() }?.let { BtField("任务", it) },
        msg.textOf("id").takeIf { it.isNotBlank() }?.let { BtField("任务 ID", it) },
        msg.textOf("type").takeIf { it.isNotBlank() }?.let { BtField("周期", btCronCycleLabel(it, msg.textOf("where1"))) },
        msg.textOf("save").takeIf { it.isNotBlank() }?.let { BtField("保留", "$it 份") },
        msg.textOf("backupTo").takeIf { it.isNotBlank() }?.let { BtField("备份到", btCronBackupTargetText(it)) }
    )
    // 只有一个 status、连任务名都没有 —— 面板这是在说「没有这条任务」，不是「有且关着」。
    if (fields.isEmpty() && msg.textOf("name").isBlank()) return BtCronAutoBackup(kind, false, emptyList())
    return BtCronAutoBackup(kind, true, listOfNotNull(BtField("状态", flagOf(msg, "status")?.let { if (it) "开启" else "关闭" }.orEmpty())) + fields)
}

private fun flagOf(obj: JsonObject, key: String): Boolean? = obj.flagOf(key)

/** `get_rotate_log_config`：`{status,msg:{stype,log_size,num,compress,status,hour,minute}}`。 */
fun btCronRotateLog(root: JsonElement?): BtCronRotateLog {
    val msg = ((root as? JsonObject)?.get("msg") as? JsonObject) ?: JsonObject(emptyMap())
    return BtCronRotateLog(
        enabled = msg.flagOf("status"),
        cycle = msg.textOf("stype", "type"),
        hour = msg.textOf("hour"),
        minute = msg.textOf("minute"),
        keep = msg.intOf("num"),
        triggerSizeMb = msg.doubleOf("log_size"),
        compress = msg.flagOf("compress")
    )
}

// ── 表单校验 ──
//
// 只有两处是面板说了算的（下面标了「实测」），其余都是「发过去面板会直接拒」的那几条，
// 面板没写死长度的这里一律不加戏——它自己的报错会被摊在失败卡上，比我们先猜错要诚实。

/** 建 / 改一个计划任务。 */
object BtCronForm {
    fun nameError(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isBlank() -> "要写个任务名"
            name.length > 100 -> "名字太长了，面板那一格也放不下"
            else -> null
        }
    }

    /** 周期里的 N：`minute-n` / `hour-n` / `day-n` 要 1–9999，`month` 要 1–31，`week` 要 0–7。 */
    fun where1Error(type: String, raw: String): String? {
        val value = raw.trim()
        val need = type in setOf("minute-n", "hour-n", "day-n", "week", "month")
        if (!need) return null
        if (value.isBlank()) return "${btCronCycleLabel(type, "N")} 得给个 N"
        val number = value.toIntOrNull() ?: return "这一格只能填整数"
        return when (type) {
            "month" -> if (number in 1..31) null else "每月几号，1–31"
            "week" -> if (number in 0..7) null else "星期几，0–7（0 和 7 都是周日）"
            else -> if (number in 1..9999) null else "间隔得是 1–9999"
        }
    }

    fun clockError(raw: String, what: String, max: Int): String? {
        val value = raw.trim()
        if (value.isBlank()) return "要选一个$what"
        val number = value.toIntOrNull() ?: return "${what}只能填整数"
        return if (number in 0..max) null else "${what}得在 0–$max 之间"
    }

    /** 脚本正文：面板对 `toShell` 不查内容，但空正文它照收，收完就是个每次跑空的任务。 */
    fun scriptError(raw: String): String? = raw.trim().takeIf { it.isBlank() }?.let { "总得让它执行点什么" }

    /**
     * 访问 URL：实测面板对 `toUrl` 只查这一格空不空，协议不写它照收，
     * 跑起来才发现访问不了。所以这一条是我们加的，不是面板的。
     */
    fun urlError(raw: String): String? {
        val url = raw.trim()
        return when {
            url.isBlank() -> "要写个网址"
            !url.startsWith("http://") && !url.startsWith("https://") -> "得是 http:// 或 https:// 开头"
            url.contains(" ") -> "网址里别留空格"
            else -> null
        }
    }

    /** 保留份数：面板这一格收 0–9999 的整数，实测填负数它回「保留份数不正确」。 */
    fun keepError(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val number = value.toIntOrNull() ?: return "份数只能填整数"
        return if (number in 0..9999) null else "份数得在 0–9999 之间"
    }

    /** 备份目录那一格：绝对路径，不给 `..`，理由同 FTP 根目录。 */
    fun pathError(raw: String): String? {
        val path = raw.trim()
        return when {
            path.isBlank() -> "要指定一个目录"
            !path.startsWith("/") -> "得是从 / 开始的绝对路径"
            path.contains("..") -> "路径里别放 .."
            path.length > 200 -> "路径太长了"
            else -> null
        }
    }

    /** 自定义分类名。 */
    fun categoryError(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isBlank() -> "要给分类起个名"
            name.length > 20 -> "分类名别超过 20 个字"
            else -> null
        }
    }
}
