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
 * 面板版本之间同一份数据能给成不同形状（`25.45%` / `25` / `"25.45"`，`"36G"` / `"36 GB"`），
 * 所以这里的规矩是「认得就转成数，认不出就原样带文案，两者都不成就留 null」——
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
    val usedPercent: Double?
)

data class BtNetwork(
    val upText: String,
    val downText: String,
    val extras: List<BtField> = emptyList()
)

data class BtSystemSnapshot(
    val cpuPercent: Double?,
    val loadOne: String,
    val loadFive: String,
    val loadFifteen: String,
    val loadLabel: String,
    val memUsedGb: Double?,
    val memTotalGb: Double?,
    val memText: String,
    val uptimeText: String,
    val warnings: List<String>,
    val extras: List<BtField>
)

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

/** 面板给的运行时长可能是秒数，也可能是「12天3小时」这种现成文案。 */
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

/** 面板的键名翻成人话；不在表里的原样露出，绝不让一行数据因为「不认识」就消失。 */
private val BtFieldLabels = mapOf(
    "id" to "编号", "name" to "名称", "sName" to "名称", "status" to "状态", "domain" to "域名",
    "path" to "根目录", "where" to "位置", "where1" to "位置", "where2" to "备注",
    "addtime" to "时间", "date" to "时间", "time" to "时间", "ps" to "备注", "ps1" to "备注",
    "type" to "类型", "sType" to "类型", "cycle" to "周期", "hour" to "小时", "minute" to "分钟",
    "week" to "星期", "minute2" to "分钟间隔", "hour2" to "小时间隔", "url" to "地址",
    "username" to "账号", "password" to "密码", "ip" to "IP", "agent" to "客户端",
    "charset" to "编码", "pname" to "数据库账号", "dbname" to "数据库", "db" to "数据库",
    "conn" to "连接数", "size" to "大小", "used" to "已用", "free" to "可用",
    "percentage" to "使用率", "disk" to "磁盘", "total" to "总量", "up" to "上行", "down" to "下行",
    "send" to "累计发送", "recv" to "累计接收", "backup" to "备份", "version" to "版本",
    "send_total" to "累计发送", "recv_total" to "累计接收",
    "total_up" to "累计上行", "total_down" to "累计下行",
    "run_user" to "运行用户", "loading" to "状态", "msg" to "说明", "message" to "说明",
    "job" to "任务类型", "bak" to "备份", "day" to "天", "mon" to "备份份数",
    "notify" to "通知", "save" to "保留份数", "safety" to "安全模式", "enabled" to "启用"
)

/**
 * 一层就够：再深的嵌套在手机上读起来是折磨，只保留标量。
 * 被 skip 点名的键整棵都不列——它已经被专门解析过一遍，再重复一次只会让人以为是两份数据。
 */
private fun flattenBt(
    prefix: String,
    obj: JsonObject,
    into: MutableMap<String, String>,
    skip: Set<String> = emptySet()
) {
    for ((key, element) in obj) {
        if (prefix.isEmpty() && key in skip) continue
        val name = if (prefix.isEmpty()) key else "$prefix.$key"
        when (element) {
            is JsonObject -> flattenBt(name, element, into)
            is JsonArray -> {
                val items = element.map { btText(it) }.filter { it.isNotBlank() }
                if (items.isNotEmpty()) into[name] = items.joinToString("、")
            }
            else -> {
                val text = btText(element)
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

/**
 * `GET /system` action=GetSystemTotal。
 * 运行时长不是每个版本都给，`uptimeText` 为空时界面就别摆这一行。
 */
fun btSystemSnapshot(root: JsonElement?): BtSystemSnapshot {
    val obj = root as? JsonObject ?: return BtSystemSnapshot(
        null, "", "", "", "", null, null, "", "", emptyList(), emptyList()
    )
    val load = obj["load"] as? JsonObject ?: JsonObject(emptyMap())
    val mem = obj.firstNested("memInfo", "mem_info", "memory") as? JsonObject ?: JsonObject(emptyMap())
    val cpu = obj.firstNested("cpuStatus", "cpu_status", "cpu", "cpuUse")
    val usedGb = mem.firstNested("realUsed", "actualUsed", "actualused", "used", "mem").let(::btText)
    val totalGb = mem.firstNested("total", "realTotal", "memTotal").let(::btText)
    val parsed = setOf("load", "memInfo", "mem_info", "memory", "cpuStatus", "cpu_status", "cpu",
        "cpuUse", "uptime", "run_time", "boot_time", "sTime", "warnings")
    return BtSystemSnapshot(
        cpuPercent = parseBtPercent(btText(cpu)),
        loadOne = load.firstText("one", "load1", "avg_load1"),
        loadFive = load.firstText("five", "load5", "avg_load5"),
        loadFifteen = load.firstText("fifteen", "load15", "avg_load15"),
        loadLabel = load.firstText("status", "average", "averaget"),
        memUsedGb = parseBtSizeGb(usedGb),
        memTotalGb = parseBtSizeGb(totalGb),
        memText = listOf(usedGb, totalGb).filter { it.isNotBlank() }.joinToString(" / "),
        uptimeText = formatBtUptime(obj.firstNested("uptime", "run_time", "boot_time", "sTime")),
        warnings = btWarnings(obj["warnings"]),
        extras = btFieldsExcept(obj, parsed, ExtrasLimit)
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

/** `GET /system` action=GetDiskInfo：可能是裸数组，也可能裹在 data 里。 */
fun btDisks(root: JsonElement?): List<BtDisk> = btItems(root).map { item ->
    BtDisk(
        name = item.firstText("disk", "name", "path"),
        totalText = item.firstText("size", "total"),
        usedText = item.firstText("used"),
        freeText = item.firstText("free", "available"),
        usedPercent = parseBtPercent(item.firstText("percentage", "usedPercent", "used"))
    )
}.filter { it.name.isNotBlank() }

/** `GET /system` action=GetNetWork：裸对象，没有 status 信封。 */
fun btNetwork(root: JsonElement?): BtNetwork {
    val obj = root as? JsonObject ?: return BtNetwork("", "")
    return BtNetwork(
        upText = obj.firstText("up", "send", "upSpeed"),
        downText = obj.firstText("down", "recv", "downSpeed"),
        extras = btFieldsExcept(obj, setOf("up", "down", "send", "recv", "upSpeed", "downSpeed"), ExtrasLimit)
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

/** 列表总条数：面板给就信面板，不给就说不知道（不是 0）。 */
fun btTotal(root: JsonElement?): Int? {
    val obj = when (root) {
        is JsonObject -> (root["data"] as? JsonObject) ?: root
        else -> return null
    }
    val raw = obj.firstNested("records", "total", "count", "totaFilter") ?: return null
    return btText(raw).toDoubleOrNull()?.toInt()
}

private val TitleKeys = mapOf(
    BtTable.Sites to listOf("name", "domain", "title", "site"),
    BtTable.Databases to listOf("name", "dbname", "db", "title"),
    BtTable.Crontab to listOf("name", "sName", "title"),
    BtTable.Logs to listOf("domain", "action", "name", "msg", "title")
)
private val SubtitleKeys = mapOf(
    BtTable.Sites to listOf("path", "where", "addtime", "status"),
    BtTable.Databases to listOf("pname", "charset", "date", "type"),
    // 计划任务先看「每小时」这种节奏，脚本路径掉到字段里；只有节奏没给时才拿路径顶上。
    BtTable.Crontab to listOf("cycle", "where1", "sType", "where", "type"),
    BtTable.Logs to listOf("ip", "username", "addtime", "time")
)
private val BadgeKeys = mapOf(
    BtTable.Sites to listOf("status", "loading", "backup"),
    BtTable.Databases to listOf("type", "conn"),
    BtTable.Crontab to listOf("type", "enabled", "sType"),
    BtTable.Logs to listOf("username", "type")
)

fun btRows(table: BtTable, root: JsonElement?): List<BtRow> {
    val titles = TitleKeys.getValue(table)
    val subs = SubtitleKeys.getValue(table)
    val badges = BadgeKeys.getValue(table)
    return btItems(root).map { item ->
        val consumed = titles + subs + badges + listOf("id")
        BtRow(
            id = item.firstText("id", "cid", "site_id", "cron_id"),
            title = item.firstText(*titles.toTypedArray()).ifBlank { "未命名" },
            subtitle = item.firstText(*subs.toTypedArray()),
            badge = item.firstText(*badges.toTypedArray()),
            fields = btFieldsExcept(item, consumed.toSet(), RowFieldsLimit)
        )
    }
}

private const val ExtrasLimit = 8
private const val RowFieldsLimit = 6
private const val MaxShellDepth = 2
private val DataKeys = listOf("data", "rows", "list", "result")
