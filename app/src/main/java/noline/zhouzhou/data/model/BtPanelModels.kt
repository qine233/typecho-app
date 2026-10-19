package noline.zhouzhou.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 宝塔面板接口返回的原始 JSON 在这一层转成人能看的东西。
 *
 * 键名按 13.1.0 的实测回包钉死，同时留旧版本的宽松兜底（同一份数据各版本能给成
 * `25.45%` / `25` / `"25.45"`，`"36G"` / `"36 GB"`，嵌套对象 / 扁字段）。
 * 规矩不变：认得就转成数，认不出就原样带文案，两者都不成就留 null——
 * 绝不用 0 或 "—" 冒充「面板说它是 0」。
 */

data class BtPanelConfig(
    val site: String = "",
    val key: String = "",
    /** 面板多为自签证书；开了这条才信任任意证书，代价是那一段不再验真。 */
    val trustSelfSigned: Boolean = false
) {
    val isComplete: Boolean get() = site.isNotBlank() && key.isNotBlank()
}

/** 面板给的一列「名字 + 值」，用于展示没被专门认出来的字段。 */
data class BtField(val label: String, val value: String)

data class BtDisk(
    val name: String,
    val totalText: String,
    val usedText: String,
    val freeText: String,
    val usedPercent: Double?,
    val detail: String = ""
)

data class BtNetwork(
    val upText: String,
    val downText: String,
    val upTotalText: String = "",
    val downTotalText: String = "",
    val extras: List<BtField> = emptyList()
)

data class BtSystemSnapshot(
    val cpuPercent: Double?,
    val cpuCores: String,
    val loadOne: String,
    val loadFive: String,
    val loadFifteen: String,
    val loadLabel: String,
    val memUsedGb: Double?,
    val memTotalGb: Double?,
    val memText: String,
    val uptimeText: String,
    val overview: String,
    val warnings: List<String>,
    val extras: List<BtField>
) {
    companion object {
        val Empty = BtSystemSnapshot(null, "", "", "", "", "", null, null, "", "", "", emptyList(), emptyList())
    }
}

/** 一张表里的一行。列名各版本不一致，认出来的排成标题/副标题/角标，其余全收进 fields。 */
data class BtRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val fields: List<BtField>
)

enum class BtTable(val wire: String, val label: String) {
    Sites("sites", "站点"),
    Databases("databases", "数据库"),
    Crontab("crontab", "计划任务"),
    Logs("logs", "面板日志")
}

// ── 抠数 ──

/** 标量转文案：布尔翻成是/否，数字和小数原样，嵌套结构交给 flatten。 */
fun btText(element: JsonElement?): String = when (element) {
    null, JsonNull -> ""
    is JsonPrimitive -> when {
        element.isString -> element.contentOrNull.orEmpty().trim()
        else -> element.booleanOrNull?.let { if (it) "是" else "否" } ?: element.content
    }
    else -> ""
}

/** `25.45%`、`已使用 25 %`、`25` 都能读；读不出或超出百分比范围返回 null。 */
fun parseBtPercent(text: String): Double? {
    val match = Regex("""(\d+(?:\.\d+)?)\s*%?""").find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return value.takeIf { it in 0.0..100.0 }
}

private val SizePerGb = mapOf(
    "TB" to (1.0 / 1024), "T" to (1.0 / 1024),
    "GB" to 1.0, "G" to 1.0,
    "MB" to 1024.0, "M" to 1024.0,
    "KB" to 1024.0 * 1024, "K" to 1024.0 * 1024,
    "B" to 1024.0 * 1024 * 1024
)

/**
 * 带单位的大小换算成 GB。没有单位一律算认不出：`36` 可能是 36 GB，也可能是端口号或条数。
 */
fun parseBtSizeGb(text: String): Double? {
    val match = Regex("""(\d+(?:\.\d+)?)\s*([KMGT]?B?|B)\b""", RegexOption.IGNORE_CASE)
        .find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].uppercase()
    // 「36G」和「36GB」都要认，但「36」这种没单位的要放过。
    val perGb = SizePerGb[unit] ?: return null
    return value / perGb
}

/**
 * 面板的内存字段是 MB 裸数字（`memTotal: 3655`），只在被专门认出来的内存键上用，
 * 别的键不敢这么猜——裸 `4` 更像核数而不是 4 MB。
 */
fun parseBtMegabytes(text: String): Double? =
    text.trim().toDoubleOrNull()?.takeIf { it >= 0 }?.let { it / 1024 }

/** 字节数翻成人话；面板给的是 B 还是 KB 由调用方说清楚。 */
fun formatBtBytes(bytes: Double?): String {
    val value = bytes ?: return ""
    if (value < 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = value
    var index = 0
    while (size >= 1024 && index < units.lastIndex) {
        size /= 1024
        index++
    }
    return if (index == 0) "${size.toLong()} ${units[index]}" else "%.1f %s".format(size, units[index])
}

/** 一位小数够了，`.0` 就别留着——内存栏写成「2 / 3.6 GB」比「2.0 / 3.6 GB」好读。 */
private fun gbText(value: Double?): String =
    value?.let { "%.1f".format(it).removeSuffix(".0") }.orEmpty()

/** 面板给的运行时长可能是秒数，也可能是「41天」这种现成文案。 */
fun formatBtUptime(element: JsonElement?): String {
    val raw = btText(element)
    if (raw.isBlank()) return ""
    val seconds = raw.toDoubleOrNull() ?: return raw
    if (seconds < 0) return raw
    var left = seconds.toLong()
    val days = left / 86400
    left %= 86400
    val hours = left / 3600
    left %= 3600
    val minutes = left / 60
    return buildString {
        if (days > 0) append("${days} 天")
        if (hours > 0) append(" ${hours} 小时")
        if (minutes > 0) append(" ${minutes} 分钟")
        if (isEmpty()) append("${left} 秒")
    }.trim()
}

private fun JsonObject.firstText(vararg keys: String): String {
    for (key in keys) {
        val text = btText(this[key])
        if (text.isNotBlank()) return text
    }
    return ""
}

private fun JsonObject.firstNested(vararg keys: String): JsonElement? {
    for (key in keys) {
        val element = this[key]
        if (element != null && element !is JsonNull) return element
    }
    return null
}

private fun btStringList(element: JsonElement?): List<String> =
    (element as? JsonArray)?.map(::btText) ?: emptyList()

private fun btNumberList(element: JsonElement?): List<Double> =
    (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() } ?: emptyList()

/**
 * 面板的键名翻成人话；不在表里的原样露出，绝不让一行数据因为「不认识」就消失。
 * 例外是 DropKeys 和凭据键——前者只会把有用列挤掉，后者根本不该出现在屏幕上。
 */
private val BtFieldLabels = mapOf(
    "id" to "编号", "name" to "名称", "sName" to "名称", "rname" to "名称", "status" to "状态",
    "domain" to "域名", "domains" to "域名",
    "path" to "根目录", "where" to "位置", "where1" to "位置", "where2" to "备注",
    "addtime" to "时间", "date" to "时间", "time" to "时间", "ps" to "备注", "ps1" to "备注",
    "type" to "类型", "sType" to "类型", "cycle" to "周期", "hour" to "小时", "minute" to "分钟",
    "week" to "星期", "minute2" to "分钟间隔", "hour2" to "小时间隔", "url" to "地址",
    "urladdress" to "请求地址", "username" to "账号", "accept" to "可连主机", "ip" to "IP",
    "agent" to "客户端", "charset" to "编码", "pname" to "数据库账号", "dbname" to "数据库",
    "db" to "数据库", "conn" to "连接数", "size" to "大小", "used" to "已用", "free" to "可用",
    "percentage" to "使用率", "disk" to "磁盘", "total" to "总量",
    "up" to "上行", "down" to "下行", "upTotal" to "累计上行", "downTotal" to "累计下行",
    "upPackets" to "上行包数", "downPackets" to "下行包数",
    "send" to "累计发送", "recv" to "累计接收", "backup" to "备份",
    "send_total" to "累计发送", "recv_total" to "累计接收",
    "total_up" to "累计上行", "total_down" to "累计下行",
    "backup_count" to "备份份数", "filesystem" to "设备", "inodes" to "inode",
    "project_type" to "项目类型", "php_version" to "PHP 版本", "type_zh" to "周期",
    "ssl" to "证书", "notAfter" to "证书到期", "endtime" to "证书剩余天数",
    "version" to "面板版本", "simple_system" to "系统", "system" to "系统", "title" to "面板",
    "cpuNum" to "CPU 核数", "cpuRealUsed" to "CPU 使用率", "memAvailable" to "可用内存(MB)",
    "memFree" to "空闲内存(MB)", "memCached" to "缓存(MB)", "memBuffers" to "缓冲(MB)",
    "memShared" to "共享(MB)", "site_total" to "站点数", "database_total" to "数据库数",
    "ftp_total" to "FTP 数", "log" to "内容", "uid" to "用户编号", "stop" to "停用原因",
    "save" to "保留份数", "backupTo" to "备份到", "notice" to "通知", "safety" to "安全模式",
    "enabled" to "启用", "user" to "执行用户", "type_name" to "任务分类", "run_user" to "运行用户",
    "loading" to "状态", "msg" to "说明", "message" to "说明", "job" to "任务类型",
    "day" to "天", "mon" to "备份份数"
)

/**
 * 面板把这些凭据原样放在回包里（数据库表就带 `password`）。
 * 手机是丢得起的屏幕，也是给人看的屏幕：值一律不落地显示，只留一行「不显示」的说明。
 */
private val SecretKeys = setOf(
    "password", "passwd", "pwd", "pass", "token", "secret", "key", "apikey", "api_key",
    "access_key", "accesskey", "secret_key", "secretkey", "private_key", "request_token",
    "ftp_password", "db_password", "auth_password"
)

internal fun isBtSecretKey(key: String): Boolean {
    val normalized = key.lowercase().removeSuffix("_")
    return normalized in SecretKeys || normalized.endsWith("password") ||
        normalized.endsWith("secret") || normalized.endsWith("token")
}

/**
 * 面板内部标志、空容器和一整段脚本：在手机上排成一行只会把真正有用的列挤掉。
 * `sBody` 那种几十行的 shell 脚本属于「要看得去任务详情」，不是列表里的一格。
 */
private val DropKeys = setOf(
    "echo", "flock", "sort", "result", "params", "keyword", "post_param", "second",
    "type_id", "pid", "sid", "uid", "quota", "conn_config", "project_config", "net", "waf",
    "proxy", "redirect", "edate", "stop_site", "backup_mode", "time_type", "special_time",
    "user_agent", "log_cut_path", "table_list", "db_backup_path", "notice_channel", "notice",
    "save_local", "where_hour", "where_minute", "sbody", "body", "db_type", "ssl", "mem",
    "split_type", "split_value", "time_set", "auto_list", "search_history", "net_flow_type",
    "net_flow_info"
)

/** 一行里塞不下一整篇脚本，超过就截断；详情另有地方看。 */
private const val ValueCap = 90

private fun capValue(text: String): String =
    text.replace('\n', ' ').replace('\r', ' ').trim()
        .let { if (it.length <= ValueCap) it else it.take(ValueCap) + "…" }

private fun isDropKey(key: String): Boolean = key.lowercase() in DropKeys

/**
 * 一层就够（最多再深一层拿 `ssl.notAfter` 这种）：再深的嵌套在手机上读起来是折磨。
 * 被 skip 点名的键整棵都不列——它已经被专门解析过一遍，再重复一次只会让人以为是两份数据。
 */
private fun flattenBt(
    prefix: String,
    obj: JsonObject,
    into: MutableMap<String, String>,
    skip: Set<String> = emptySet(),
    depth: Int = 0
) {
    for ((key, element) in obj) {
        if (prefix.isEmpty() && key in skip) continue
        if (isDropKey(key) || isBtSecretKey(key)) continue
        val name = if (prefix.isEmpty()) key else "$prefix.$key"
        when (element) {
            is JsonObject -> if (depth < 1) flattenBt(name, element, into, depth = depth + 1)
            is JsonArray -> {
                val items = element.map { btText(it) }.filter { it.isNotBlank() }
                if (items.isNotEmpty()) into[name] = capValue(items.joinToString("、"))
            }
            else -> {
                val text = capValue(btText(element))
                if (text.isNotBlank()) into[name] = text
            }
        }
    }
}

private fun btFieldsExcept(obj: JsonObject, skip: Set<String>, limit: Int): List<BtField> {
    val flat = linkedMapOf<String, String>()
    flattenBt("", obj, flat, skip)
    return flat.entries.take(limit)
        .map { (key, value) -> BtField(BtFieldLabels[key] ?: key, value) }
}

// ── 看板 ──

/** 实测：GetSystemTotal 的键（扁的，不是嵌套 memInfo）。 */
private val SystemTotalKeys = setOf("load", "memInfo", "mem_info", "memory", "cpuStatus", "cpu_status",
    "cpu", "cpuUse", "uptime", "run_time", "boot_time", "sTime", "warnings", "time", "system",
    "simple_system", "title", "version", "cpuRealUsed", "cpuNum", "memTotal", "memFree",
    "memBuffers", "memCached", "memAvailable", "memShared", "memRealUsed", "memNewTotal",
    "memNewRealUsed", "memNewTotalList", "memNewRealUsedList", "isuser", "isport", "installed",
    "docker_run")

/** GetNetWork 里已经被看板别处用掉的部分。 */
private val NetworkShellKeys = setOf("network", "iostat", "cpu_times", "cpu", "load", "disk", "site_total",
    "database_total", "ftp_total", "up", "down", "upTotal", "downTotal", "upPackets", "downPackets",
    "send", "recv", "upSpeed", "downSpeed", "system", "simple_system", "title", "time", "version",
    "installed", "docker_run")

/**
 * 看板：`system?action=GetSystemTotal` 为主，`GetNetWork` 补负载和内存。
 *
 * 13.1.0 的 GetSystemTotal 不给负载，负载只在 GetNetWork 里，所以这两个必须合着读。
 */
fun btSystemSnapshot(root: JsonElement?, networkInfo: JsonElement? = null): BtSystemSnapshot {
    val obj = root as? JsonObject ?: return BtSystemSnapshot.Empty
    val net = networkInfo as? JsonObject
    val load = (obj["load"] as? JsonObject) ?: (net?.get("load") as? JsonObject) ?: JsonObject(emptyMap())
    // 老版本把内存裹在 memInfo 里给，13.1.0 全摊平在第一层，两种都得认。
    val memShells = listOfNotNull(
        obj, net,
        obj["memInfo"] as? JsonObject, obj["mem_info"] as? JsonObject, obj["memory"] as? JsonObject,
        net?.get("memInfo") as? JsonObject, net?.get("mem") as? JsonObject
    )

    // 两个回包都给同一份数据时以 GetSystemTotal 为准，只有 GetNetWork 才有的（负载）再补。
    // 一个键在两个来源里都缺、另一个键只在第二个来源里有值时，不能拿第二个来源的兜底键去顶：
    // 所以按「键优先、来源其次」的顺序找，而不是按来源整体找一遍。
    fun textFrom(vararg keys: String): String {
        for (key in keys) {
            for (shell in memShells) {
                val text = btText(shell[key])
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    fun elementFrom(vararg keys: String): JsonElement? {
        for (key in keys) {
            for (shell in memShells) {
                val element = shell[key]
                if (element != null && element !is JsonNull) return element
            }
        }
        return null
    }

    // MB 裸数字优先，读不出再认「3.6GB」这种带单位的现成文案。
    val usedGb = parseBtMegabytes(textFrom("memRealUsed", "real_used"))
        ?: parseBtSizeGb(textFrom("memNewRealUsed", "realUsed", "actualused", "used"))
    val totalGb = parseBtMegabytes(textFrom("memTotal", "mem_total"))
        ?: parseBtSizeGb(textFrom("memNewTotal", "total", "realTotal"))
    val cpu = parseBtPercent(textFrom("cpuRealUsed", "cpuStatus", "cpu_status", "cpuUse"))
    val overview = listOfNotNull(
        textFrom("simple_system").ifBlank { textFrom("system") }.takeIf { it.isNotBlank() },
        textFrom("version").takeIf { it.isNotBlank() }?.let { "面板 $it" },
        textFrom("cpuNum").takeIf { it.isNotBlank() }?.let { "$it 核" }
    ).joinToString(" · ")

    val memLine = listOf(gbText(usedGb), gbText(totalGb))
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .takeIf { it.isNotBlank() }?.plus(" GB")
        .orEmpty()

    val skip = SystemTotalKeys + NetworkShellKeys
    val extras = (btFieldsExcept(obj, skip, ExtrasLimit) +
        btFieldsExcept(net ?: JsonObject(emptyMap()), skip, ExtrasLimit))
        .distinctBy { it.label + it.value }
        .take(ExtrasLimit)

    return BtSystemSnapshot(
        cpuPercent = cpu,
        cpuCores = textFrom("cpuNum"),
        loadOne = load.firstText("one", "load1", "avg_load1"),
        loadFive = load.firstText("five", "load5", "avg_load5"),
        loadFifteen = load.firstText("fifteen", "load15", "avg_load15"),
        // 有的版本给的是「正常」这种评价，13.1.0 给的是 limit/max（负载上限），两者不能混着贴。
        loadLabel = load.firstText("status", "average").ifBlank {
            load.firstText("limit", "max").takeIf { it.isNotBlank() }?.let { "上限 $it" }.orEmpty()
        },
        memUsedGb = usedGb,
        memTotalGb = totalGb,
        memText = memLine,
        uptimeText = formatBtUptime(elementFrom("time", "uptime", "run_time", "boot_time", "sTime")),
        overview = overview,
        warnings = btWarnings(obj["warnings"]) + btWarnings(net?.get("warnings")),
        extras = extras
    )
}

/** warnings 是「服务名 → {status,msg}」，面板用它报 nginx/mysql 掉了。 */
fun btWarnings(element: JsonElement?): List<String> {
    val obj = element as? JsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (service, value) ->
        when (value) {
            is JsonObject -> {
                val msg = value.firstText("msg", "message", "name")
                val state = value.firstText("status", "status_text")
                listOf(service, state, msg).filter { it.isNotBlank() }.joinToString(" · ")
            }
            else -> btText(value).takeIf { it.isNotBlank() }?.let { "$service · $it" }
        }
    }.filter { it.isNotBlank() }
}

/**
 * `system?action=GetDiskInfo`：13.1.0 给的是位置数组
 * `size=["59.9 GB","46.2 GB","13.7 GB","77.13%",…]` + `byte_size=[总,已用,可用]`，
 * 老版本给分开命名的键。两种都认，数组优先。
 */
fun btDisks(root: JsonElement?): List<BtDisk> = btItems(root).map { item ->
    val sizes = btStringList(item["size"])
    val bytes = btNumberList(item["byte_size"])
    val usedPercent = parseBtPercent(sizes.getOrNull(3).orEmpty())
        ?: bytes.getOrNull(0)?.let { total ->
            bytes.getOrNull(1)?.takeIf { total > 0 }?.let { used ->
                (used / total * 100).takeIf { it in 0.0..100.0 }
            }
        }
        ?: parseBtPercent(item.firstText("percentage", "usedPercent", "used"))
    BtDisk(
        name = item.firstText("rname", "path", "disk", "name", "mount_point"),
        totalText = sizes.getOrNull(0).orEmpty().ifBlank { formatBtBytes(bytes.getOrNull(0)) }
            // 老版本的 size 是一个字符串而不是数组，firstText 只在它不是数组时才回话。
            .ifBlank { item.firstText("total", "size") },
        usedText = sizes.getOrNull(1).orEmpty().ifBlank { formatBtBytes(bytes.getOrNull(1)) }
            .ifBlank { item.firstText("used") },
        freeText = sizes.getOrNull(2).orEmpty().ifBlank { formatBtBytes(bytes.getOrNull(2)) }
            .ifBlank { item.firstText("free", "available") },
        usedPercent = usedPercent,
        detail = listOf(item.firstText("filesystem"), item.firstText("type"))
            .filter { it.isNotBlank() && it != "/" }
            .joinToString(" ")
    )
}.filter { it.name.isNotBlank() }

/**
 * `system?action=GetNetWork`：裸对象，没有 status 信封。
 * `up`/`down` 是 KB/s 的即时速率，`upTotal`/`downTotal` 是开机以来的字节累计。
 */
fun btNetwork(root: JsonElement?): BtNetwork {
    val obj = root as? JsonObject ?: return BtNetwork("", "")
    fun speed(vararg keys: String): String =
        obj.firstText(*keys).toDoubleOrNull()?.let { "%.1f KB/s".format(it) } ?: obj.firstText(*keys)
    return BtNetwork(
        upText = speed("up", "upSpeed"),
        downText = speed("down", "downSpeed"),
        upTotalText = formatBtBytes(obj["upTotal"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()),
        downTotalText = formatBtBytes(obj["downTotal"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()),
        extras = btFieldsExcept(obj, NetworkShellKeys, ExtrasLimit)
    )
}

/**
 * 站点/数据库/FTP 的计数：GetNetWork 顺手就给了，比再去翻三张表便宜得多。
 * 面板没给就回空列表，界面那一行随之消失（不是「0 个」）。
 */
fun btCounts(vararg roots: JsonElement?): List<BtField> {
    val objs = roots.filterIsInstance<JsonObject>()
    fun pick(vararg keys: String): String =
        objs.firstOrNull { it.firstText(*keys).isNotBlank() }?.firstText(*keys).orEmpty()
    return listOfNotNull(
        pick("site_total", "siteCount").takeIf { it.isNotBlank() }?.let { BtField("站点", it) },
        pick("database_total", "databaseCount").takeIf { it.isNotBlank() }?.let { BtField("数据库", it) },
        pick("ftp_total", "ftpCount").takeIf { it.isNotBlank() }?.let { BtField("FTP", it) }
    )
}

// ── 表 ──

/**
 * 面板把同一张表裹在不同层里：`{"data":{"rows":[]}}`、`{"data":[]}`、裸数组都见过，
 * 有的接口还把结果塞进 msg。挨个找，找到第一个数组为止。
 */
fun btItems(root: JsonElement?): List<JsonObject> =
    (firstBtArray(root) ?: emptyList()).mapNotNull { it as? JsonObject }

/**
 * 面板的壳层不统一：`{"data":[…]}`、`{"data":{"rows":[…]}}`、裸数组、`{"list":[…]}` 都见过。
 * 按 data → rows → list → result 递归找两层，最后才试 msg（msg 装数组时是少数接口的数据位）。
 */
private fun firstBtArray(element: JsonElement?, depth: Int = 0): List<JsonElement>? {
    if (element == null || depth > MaxShellDepth) return null
    if (element is JsonArray) return element.toList()
    if (element !is JsonObject) return null
    for (key in DataKeys) {
        firstBtArray(element[key], depth + 1)?.let { return it }
    }
    return firstBtArray(element["msg"], depth + 1)
}

/**
 * 列表总条数：面板有时给 `records`/`total`，13.1.0 只把它写进 `page` 那串分页 HTML 的
 * 「共11970条」里。都没有就说不知道（不是 0）。
 */
fun btTotal(root: JsonElement?): Int? {
    val obj = when (root) {
        is JsonObject -> (root["data"] as? JsonObject) ?: root
        else -> return null
    }
    val raw = obj.firstNested("records", "total", "count", "totaFilter")?.let(::btText).orEmpty()
    raw.toDoubleOrNull()?.toInt()?.let { return it }
    return Regex("""共\s*(\d+)\s*条""").find(obj["page"].let(::btText))?.groupValues?.get(1)?.toIntOrNull()
}

private val TitleKeys = mapOf(
    BtTable.Sites to listOf("name", "rname", "domain_name", "title", "site"),
    BtTable.Databases to listOf("name", "dbname", "db", "title"),
    BtTable.Crontab to listOf("name", "rname", "sName", "title"),
    BtTable.Logs to listOf("log", "action", "domain", "name", "msg", "title")
)
private val SubtitleKeys = mapOf(
    BtTable.Sites to listOf("path", "where", "addtime", "status"),
    BtTable.Databases to listOf("username", "pname", "accept", "charset", "date"),
    // 计划任务先看「每天的01:30执行一次」这种节奏，脚本路径掉到字段里；节奏没给时才拿位置顶上。
    BtTable.Crontab to listOf("cycle", "type_zh", "where1", "sType", "where", "type"),
    // 面板日志一句话就是事件本身，时间是副标题；操作者要么当角标、要么落进字段，不该占掉时间那格。
    BtTable.Logs to listOf("addtime", "ip", "time")
)
private val BadgeKeys = mapOf(
    BtTable.Sites to listOf("project_type", "php_version", "status", "loading"),
    BtTable.Databases to listOf("type", "db_type", "conn"),
    BtTable.Crontab to listOf("status", "type_zh", "enabled", "sType", "type"),
    BtTable.Logs to listOf("type", "username")
)

/**
 * 站点角标：面板把类型和 PHP 版本拆成两个键，而且非 PHP 站点的 `php_version` 写的是「静态」。
 * 「静态」在类型里已经说明了，就别再拼一遍。
 */
private fun siteBadge(item: JsonObject): String {
    val type = item.firstText("project_type")
    val php = item.firstText("php_version")
    val translated = when (type.lowercase()) {
        "html" -> "静态"
        "proxy" -> "反向代理"
        "php" -> "PHP"
        "" -> ""
        else -> type
    }
    return when {
        translated.isBlank() -> item.firstText("status")
        php.isNotBlank() && php != "静态" && translated.endsWith("PHP") -> "$translated $php"
        else -> translated
    }
}

/** 计划任务的 status 实测只有 1/0（面板里停用的那条给的是 0）。老版本没给这两个键时退回类型原文。 */
private fun crontabBadge(item: JsonObject): String {
    val state = when (item.firstText("status")) {
        "1" -> "启用中"
        "0" -> "已停用"
        else -> when (item.firstText("enabled")) {
            "是", "1" -> "启用中"
            "否", "0" -> "已停用"
            else -> ""
        }
    }
    val cycle = item.firstText("type_zh")
    return listOf(
        state, cycle,
        // 状态和节奏都没有才拿 `type`（minuteTask 这种英文原文）顶上，总比角标空着强。
        if (state.isBlank() && cycle.isBlank()) item.firstText("type", "sType") else ""
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 证书：实测未部署时 `ssl` 直接给 -1，部署了是个对象，过期站点给的是负的剩余天数。 */
private fun sslFields(item: JsonObject): List<BtField> = when (val ssl = item["ssl"]) {
    is JsonObject -> {
        val days = ssl.firstText("endtime").toDoubleOrNull()
        val until = ssl.firstText("notAfter")
        val left = when {
            days == null -> ""
            days >= 0 -> "剩 ${days.toInt()} 天"
            else -> "已过期待续签"
        }
        listOfNotNull(
            BtField("证书", listOf(left, until.filterNotBlank("至 ")).filter { it.isNotBlank() }.joinToString(" "))
                .takeIf { it.value.isNotBlank() },
            ssl.firstText("subject").takeIf { it.isNotBlank() }?.let { BtField("证书域名", it) }
        )
    }
    else -> listOfNotNull(
        btText(ssl).takeIf { it.isNotBlank() && it != "-1" }?.let { BtField("证书", it) }
    )
}

private fun String.filterNotBlank(prefix: String): String = if (isBlank()) "" else "$prefix$this"

fun btRows(table: BtTable, root: JsonElement?): List<BtRow> {
    val titles = TitleKeys.getValue(table)
    val subs = SubtitleKeys.getValue(table)
    val badges = BadgeKeys.getValue(table)
    return btItems(root).map { item ->
        val consumed = titles + subs + badges + listOf("id", "project_type", "php_version", "status",
            "cycle", "type_zh")
        BtRow(
            id = item.firstText("id", "cid", "site_id", "cron_id"),
            // 标题/副标题同样要截断：面板日志那一格是一整句话，不截就能把屏幕顶穿。
            title = capValue(item.firstText(*titles.toTypedArray())).ifBlank { "未命名" },
            subtitle = capValue(item.firstText(*subs.toTypedArray())),
            badge = when (table) {
                BtTable.Sites -> siteBadge(item)
                BtTable.Crontab -> crontabBadge(item)
                else -> item.firstText(*badges.toTypedArray())
            },
            fields = (siteFieldsFor(table, item) +
                btFieldsExcept(item, consumed.toSet(), RowFieldsLimit)).take(RowFieldsLimit)
        )
    }
}

private fun siteFieldsFor(table: BtTable, item: JsonObject): List<BtField> =
    if (table == BtTable.Sites) sslFields(item) else emptyList()

private const val ExtrasLimit = 10
private const val RowFieldsLimit = 5
private const val MaxShellDepth = 2
private val DataKeys = listOf("data", "rows", "list", "result")
