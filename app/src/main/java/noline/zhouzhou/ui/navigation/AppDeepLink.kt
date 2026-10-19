package noline.zhouzhou.ui.navigation

import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.sync.StatusNotifier
import noline.zhouzhou.data.sync.UpdateNotifier

/**
 * App 内部深链。常驻通知和桌面小组件都靠它把人送到指定页面，
 * 所以解析必须是纯函数：Android 的 Uri 在 JVM 单测里是假的，一碰就返回默认值。
 */

/** 小组件替用户填进记账面板的那半张表单——品名和分类代填，金额还得自己敲。 */
data class BillPrefill(val title: String = "", val kind: String = "")

/** 一条深链解析出来的落点。 */
data class AppLink(
    val route: String,
    val prefill: BillPrefill? = null,
    /** 健康卡片那颗「记体重」：进去要把录入面板拉开，而不是只落在页面上。 */
    val openAdd: Boolean = false
)

object AppLinks {
    const val BILLS_ADD = "nline://bills/add"
    const val HEALTH = "nline://health"
    const val HEALTH_ADD = "nline://health/add"

    fun billsAdd(title: String = "", kind: String = ""): String {
        val query = listOfNotNull(
            "title=${percentEncode(title)}".takeIf { title.isNotBlank() },
            "kind=${percentEncode(kind)}".takeIf { kind.isNotBlank() }
        )
        return if (query.isEmpty()) BILLS_ADD else "$BILLS_ADD?${query.joinToString("&")}"
    }
}

fun parseAppLink(link: String?): AppLink? {
    val raw = link?.takeIf { it.isNotBlank() } ?: return null
    return when {
        raw == StatusNotifier.DEEP_LINK -> AppLink(Screen.Storage.route)
        raw == StatusNotifier.DEEP_LINK_BILLS -> AppLink(Screen.Bills.route)
        // 更新下完那条通知：回到设置页，由页面把包交给安装器。
        raw == UpdateNotifier.DEEP_LINK_UPDATE -> AppLink(Screen.Settings.route)
        raw == AppLinks.HEALTH -> AppLink(Screen.Health.route)
        raw == AppLinks.HEALTH_ADD -> AppLink(Screen.Health.route, openAdd = true)
        raw == AppLinks.BILLS_ADD || raw.startsWith("${AppLinks.BILLS_ADD}?") ->
            AppLink(Screen.Bills.route, prefill = prefillFromQuery(raw.substringAfter('?', "")))
        else -> null
    }
}

private fun prefillFromQuery(query: String): BillPrefill {
    val params = query.split('&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        part.substringBefore('=') to percentDecode(part.substringAfter('=', ""))
    }.toMap()
    val kind = params["kind"].orEmpty()
    return BillPrefill(
        title = params["title"].orEmpty(),
        // 链接是外面传进来的，分类代码只认那 8 个，别的按 other 走（和服务器一致）
        kind = if (kind.isBlank()) "" else BillKind.normalize(kind)
    )
}

private val Unreserved = ('A'..'Z') + ('a'..'z') + ('0'..'9') + charArrayOf('-', '.', '_', '~')

internal fun percentEncode(value: String): String =
    value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val b = byte.toInt() and 0xFF
        if (b.toChar() in Unreserved) b.toChar().toString() else "%%%02X".format(b)
    }

internal fun percentDecode(raw: String): String {
    if (!raw.contains('%')) return raw
    val bytes = ArrayList<Byte>(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        // 只认完整的 %XX；残骸按原样留下，别把用户输入的品名吃掉
        val escaped =
            if (c == '%' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (escaped != null) {
            bytes += escaped.toByte()
            i += 3
        } else {
            val step = if (c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()) 2 else 1
            raw.substring(i, i + step).toByteArray(Charsets.UTF_8).forEach { bytes += it }
            i += step
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}
