package noline.zhouzhou.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 宝塔官方接口文档（docs.bt.cn/api）的全量目录，随包打在 `assets/bt_catalog.json` 里。
 *
 * 字段名故意压到一个字母：这份表有 329 条、522 个参数，摊开写会有 200KB 的键名重复。
 * 面板的接口比 App 发版快，所以目录是数据不是代码——补一个模块只要换那份 json。
 */
@Serializable
data class BtParamSpec(
    /** 参数名，照面板文档原样写。 */
    val n: String,
    /** 1 = 必填。 */
    val r: Int = 0,
    /** 文档标的类型，仅用来提示。 */
    val t: String = "",
    /** 文档里那句话。 */
    val d: String = ""
)

@Serializable
data class BtEndpointSpec(
    /** 模块代号，如 site、docker。 */
    val m: String,
    /** 模块中文名。 */
    val mc: String,
    /** a = 走 `POST /模块` + body 里的 action；p = 走各自完整路径。 */
    val k: String = "a",
    val v: String = "POST",
    val p: String,
    val a: String,
    val ti: String = "",
    /** read / write / destroy。 */
    val g: String = "read",
    val ps: List<BtParamSpec> = emptyList()
)

/** 一条接口动得多深。这三档决定界面上要不要问一句、要不要人亲手打字。 */
enum class BtDanger {
    /** 只读，点了就跑。 */
    Read,

    /** 会改东西，要过确认门。 */
    Write,

    /** 删了回不来的东西，确认门上再要一次「亲手打一遍」。 */
    Destroy
}

/** 一个可调用的面板接口。 */
class BtEndpoint(val spec: BtEndpointSpec) {

    val module: String get() = spec.m
    val moduleTitle: String get() = spec.mc
    val name: String get() = spec.a
    val title: String get() = spec.ti.ifBlank { spec.a }
    val path: String get() = spec.p
    val isGet: Boolean get() = spec.v.equals("GET", ignoreCase = true)

    /** action 类接口把动作塞表单体；路径类接口动作已经写在 URL 里了。 */
    val sendsActionParam: Boolean get() = spec.k == "a"

    val danger: BtDanger
        get() = when (spec.g) {
            "read" -> BtDanger.Read
            "destroy" -> BtDanger.Destroy
            else -> BtDanger.Write
        }

    val params: List<BtParamSpec> get() = spec.ps

    val requiredParams: List<BtParamSpec> get() = params.filter { it.r == 1 }

    /** 少填了哪个必填项；全齐返回 null。 */
    fun missingParam(values: Map<String, String>): BtParamSpec? =
        requiredParams.firstOrNull { values[it.n].isNullOrBlank() }

    override fun toString(): String = "$moduleTitle · $name"
}

/** 目录本身：按模块分好组，界面直接拿去摆。 */
class BtCatalog(val endpoints: List<BtEndpoint>) {

    val modules: List<BtModule> = endpoints
        .groupBy { it.moduleTitle }
        .toSortedMap()
        .map { (title, list) -> BtModule(title, list.sortedWith(ReadFirstThenName)) }

    /**
     * 调用台列表唯一的筛法：先按模块，再按关键词。
     * 界面不再自己写一遍匹配规则，否则这边多认一个字段、那边少认一个，搜出来的东西就对不上。
     */
    fun filter(moduleTitle: String = "", keyword: String = ""): List<BtEndpoint> {
        val scoped = moduleTitle.takeIf { it.isNotBlank() }
            ?.let { title -> endpoints.filter { it.moduleTitle == title } }
            ?: endpoints
        val kw = keyword.trim()
        if (kw.isEmpty()) return scoped.sortedWith(ReadFirstThenName)
        return scoped.filter {
            it.name.contains(kw, ignoreCase = true) ||
                it.title.contains(kw, ignoreCase = true) ||
                it.moduleTitle.contains(kw, ignoreCase = true) ||
                it.path.contains(kw, ignoreCase = true) ||
                it.params.any { p -> p.n.contains(kw, ignoreCase = true) || p.d.contains(kw, ignoreCase = true) }
        }.sortedWith(ReadFirstThenName)
    }

    data class BtModule(val title: String, val endpoints: List<BtEndpoint>)

    companion object {
        private val ReadFirstThenName = compareBy<BtEndpoint>({ it.danger.ordinal }, { it.name })

        /** 那份 json 认不出来就当空目录：界面会明说「目录没读出来」，不会摆一张 0 条的表装成面板说的。 */
        fun parse(text: String): BtCatalog = try {
            BtCatalog(jsonOf().decodeFromString<List<BtEndpointSpec>>(text).map(::BtEndpoint))
        } catch (e: Exception) {
            BtCatalog(emptyList())
        }
    }
}

/**
 * 面板回包里绝不能上屏的键。
 *
 * 实测 13.1.0：数据库列表的 `password` 是明文吐出来的，`GetSSL` 直接带私钥。
 * 这两个口都要摆到通用调用台里，所以过滤放在渲染之前，不指望每个页面自己记得。
 *
 * 只按整个键名或后缀匹配，绝不做子串：目录里 `keyword`、`keys` 这类正常字段一堆，
 * 拿 `key` 去 contains 会把它们一起涂掉，那份回包就再也读不出东西了。
 */
private val SecretKeys = setOf(
    "password", "passwd", "pass", "pwd", "root", "key", "sk", "token", "secret",
    "private_key", "privatekey", "ssh_key", "access_key", "secret_key", "api_key",
    "ftp_password", "request_token"
)

private val SecretSuffixes = listOf("_password", "_passwd", "_key", "_secret", "_token")

internal fun isSecretKey(key: String): Boolean {
    val lower = key.lowercase()
    return lower in SecretKeys || SecretSuffixes.any { lower.endsWith(it) }
}

/** 返回脱敏后的 JSON 文本；命中的键值一律换成 `***`。 */
fun redactBtSecrets(text: String): String {
    if (text.isBlank()) return text
    return try {
        val element = jsonOf().parseToJsonElement(text)
        jsonOf(pretty = true).encodeToString(JsonElement.serializer(), redactBtElement(element))
    } catch (e: Exception) {
        text
    }
}

internal fun jsonOf(pretty: Boolean = false): Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = pretty
}

/** 先把整棵回包脱敏，再拿去摆表或打文本：表格读的也是这份数据，不能绕过过滤。 */
fun redactBtElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.associate { (key, value) ->
            if (isSecretKey(key)) key to JsonPrimitive("***") else key to redactBtElement(value)
        }
    )

    is JsonArray -> JsonArray(element.map(::redactBtElement))
    else -> element
}
