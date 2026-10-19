package noline.zhouzhou.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通用调用台拿到回包之后怎么给人看。
 *
 * 329 个接口的返回形状没有统一约定：有的是对象数组，有的是一个摊平的对象，有的只有一句话。
 * 这里只认两种能读的样子——数组摆成表、摊平的对象摆成「字段 / 值」两列——
 * 剩下的一律原样给一段脱敏 JSON，宁可难看也不猜错。
 *
 * 表格和文本走的是同一份脱敏后的数据：数据库列表那种明文密码，
 * 不能因为「表格不带这一列」就漏在文本里。
 */
data class BtResultView(
    val columns: List<String>,
    val rows: List<List<String>>,
    val text: String,
    val truncated: Boolean
) {
    val isTable: Boolean get() = columns.isNotEmpty() && rows.isNotEmpty()
    val isEmpty: Boolean get() = rows.isEmpty() && text.isBlank()
}

/** 一屏摆不下太多列；剩下的字段人在下面的 JSON 里能翻到。 */
private const val MaxColumns = 5
private const val MaxRows = 60
private const val MaxCell = 46

/** 一段 300KB 的回包直接塞进一个 Text 会把组合卡住，够读就行。 */
private val MaxText = 40_000

fun btResultView(root: JsonElement): BtResultView {
    val safe = redactBtElement(root)
    val payload = (safe as? JsonObject)?.get("data") ?: safe
    val table = tableOf(payload)
    val pretty = jsonOf(pretty = true).encodeToString(JsonElement.serializer(), safe)
    return if (pretty.length > MaxText) {
        BtResultView(table.first, table.second, pretty.take(MaxText), truncated = true)
    } else {
        BtResultView(table.first, table.second, pretty, truncated = false)
    }
}

private fun tableOf(element: JsonElement): Pair<List<String>, List<List<String>>> = when (element) {
    is JsonArray -> arrayOfObjects(element)
    is JsonObject -> flatObject(element)
    else -> emptyList<String>() to emptyList()
}

private fun arrayOfObjects(array: JsonArray): Pair<List<String>, List<List<String>>> {
    val objects = array.filterIsInstance<JsonObject>()
    if (objects.isEmpty()) return emptyList<String>() to emptyList()
    val keys = LinkedHashSet<String>()
    objects.take(10).forEach { keys.addAll(it.keys) }
    val columns = keys.take(MaxColumns).toList()
    if (columns.isEmpty()) return emptyList<String>() to emptyList()
    val rows = objects.take(MaxRows).map { row -> columns.map { cell(row[it]) } }
    return columns to rows
}

private fun flatObject(obj: JsonObject): Pair<List<String>, List<List<String>>> {
    // 只有全是标量的对象才当两列表读；混着数组/对象的直接回落到文本，别把结构猜没。
    if (obj.values.any { it is JsonObject || it is JsonArray }) return emptyList<String>() to emptyList()
    val rows = obj.entries.take(MaxRows).map { (key, value) -> listOf(key, cell(value)) }
    return listOf("字段", "值") to rows
}

private fun cell(element: JsonElement?): String {
    if (element == null || element is JsonNull) return ""
    val text = (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length > MaxCell) flat.take(MaxCell) + "…" else flat
}
