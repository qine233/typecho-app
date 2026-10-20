package noline.zhouzhou.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 反向代理这一页的读模型。
 *
 * 形状先按 docs.bt.cn/api 写，之后在 13.1.0 的正式面板上把读的四个口逐个对过（只读，没发过写）。
 * 校准结果记在这里，因为它就是下面这些解析规则存在的原因：
 *
 * 1. 每一条回包都整份留在 [BtReverseProxyEntry.raw] / [BtReverseProxyRule.raw] 里，文档没写、
 *    面板却在给的键一律摊到「面板没说的键」，不假装这一行只有我们认得的那几格；
 * 2. 能空的标量全收成字符串或可空，认不出来就是 null，**不当 0、不当 false**——
 *    面板的 `status` 是字符串 `"1"`，`websocket_status` 是布尔，两种「关」长得完全不一样；
 * 3. 路径风格接口（`/mod/proxy/com/…`）有两个坑，网络层按这两条处理：文档里连字符的路径
 *    （`get-list`）要换成下划线才发得出去，而 `action` 这一格还得照文档原样塞进请求体；
 * 4. `get_global_conf` 实际给的是一大堆平铺的 nginx 配置项，比文档那则示例宽得多；
 *    其中 `proxy_cache.cache_status` 给的是**布尔 `false`**，不是文档写的 `"0"` / `"1"`——
 *    所以「面板给了缓存这一格」不等于「缓存开着」，两种写法都得认（见 [nestedFlag]）。
 *
 * 名字前缀统一带 `Reverse`：站点详情那边已经有一个 [BtProxyRule] 了，那是 `/site` 的
 * `GetProxyList`，字段叫 `proxyname` / `proxydir` / `proxysite`，和这一模块的
 * `proxy_path` / `proxy_pass` 是两套东西，撞在一起迟早读错。
 */

/** 代理挂在哪个 web 服务器上；这一段是路径里的 `stype`，不是参数。 */
enum class BtProxyEngine(val wire: String, val label: String) {
    Nginx("nginx", "Nginx"),
    Apache("apache", "Apache"),
    Openresty("openresty", "OpenResty");

    companion object {
        fun from(wire: String): BtProxyEngine = entries.firstOrNull { it.wire == wire } ?: Nginx
    }
}

/**
 * 一条反向代理项目（列表口 `get_list` 的一行）。
 *
 * 实测这一口的 `data` 外面还裹了一层：`{"data":{"where":…,"page":"共2条…","data":[…]}}`，
 * 总条数只在 `page` 那串 HTML 里，所以分页要靠 [btTotal] 从整棵树上数。
 */
data class BtReverseProxyEntry(
    val id: String,
    /** 站点名，面板用它当这一条的主键去调其它接口（`site_name` 传的就是这个）。 */
    val name: String,
    /** 网站根目录。 */
    val path: String,
    /** `"1"` 开、`"0"` 关；面板哪天给别的写法就当不知道，界面上显示原文。 */
    val statusText: String,
    val remark: String,
    val addTime: String,
    /** 到期时间，`0000-00-00` 是「没有到期这一说」。 */
    val expireTime: String,
    /** 改名后的名字：面板的 `rname` 这一列在别的表里才是真名，这里只摊出来。 */
    val renamed: String,
    val raw: JsonObject
) {
    val enabled: Boolean?
        get() = statusText.trim().toFlag()

    /** 列表上一眼要看的：站点名，没有就退到根目录。 */
    val title: String get() = name.ifBlank { path }

    val extraKeys: List<String> = raw.keys.filter { it !in KnownEntryKeys }.sorted()

    companion object {
        private val KnownEntryKeys =
            setOf("id", "name", "path", "status", "ps", "addtime", "edate", "rname")
    }
}

/**
 * 一个站点下面的一条代理规则（`get_proxy_list` 的一项）。
 *
 * 一个站点可以挂好几条（不同 `proxy_path`），所以详情页是「站点 → 规则列表」两层。
 * `websocket` / `gzip` 在文档里是**对象**（`{"websocket_status":true}`），不是布尔——
 * 面板往里塞过别的键，所以整包留在 [raw] 里。
 */
data class BtReverseProxyRule(
    val proxyType: String,
    /** 匹配路径，如 `/api`；同一站点下不能重复。 */
    val proxyPath: String,
    /** 转发目标，如 `http://127.0.0.1:8080`。 */
    val proxyPass: String,
    val proxyHost: String,
    val remark: String,
    val raw: JsonObject
) {
    val title: String get() = proxyPath.ifBlank { proxyPass }

    val websocketEnabled: Boolean? get() = nestedFlag(raw["websocket"], "websocket_status")

    val gzipEnabled: Boolean? get() = nestedFlag(raw["gzip"], "gzip_status")

    val extraKeys: List<String> = raw.keys.filter { it !in KnownRuleKeys }.sorted()

    companion object {
        private val KnownRuleKeys =
            setOf("proxy_type", "proxy_path", "proxy_pass", "proxy_host", "websocket", "gzip", "remark")
    }
}

/**
 * 站点的代理全局设置（`get_global_conf`）。
 *
 * 这一口是「一个站点一份」，不是每条规则一份：gzip、websocket、日志、缓存都在这。
 * 正式面板 13.1.0 实测这一份是**几十个键的平铺大对象**（`primary_port`、`ipv4_ssl_port_conf`、
 * `security.*`、`redirect.*` 这些 Nginx 模板全在里面），文档示例只画了五个键。
 * 认得出的只取 [domainList] / [sslEnabled] / [gzipEnabled] / [websocketEnabled] / [logType] /
 * [cacheEnabled]，其余一律按原文摊到界面上，不猜。
 */
data class BtReverseProxyConf(
    val domainList: List<String>,
    val sslEnabled: Boolean?,
    val gzipEnabled: Boolean?,
    val websocketEnabled: Boolean?,
    /** `default` / `file` / `rsyslog` / `off`；面板给的是字符串，不翻译。 */
    val logType: String,
    /**
     * 代理缓存开关。实测面板给的是 `proxy_cache.cache_status`，而且是**布尔 `false`**
     * 而不是文档写的 `"0"`/`"1"` —— 所以「有 proxy_cache 这一格」不等于「缓存开着」。
     */
    val cacheEnabled: Boolean?,
    /** 面板当前给的缓存时长（实测 `1d`），空表示面板没说。 */
    val cacheExpires: String,
    val raw: JsonObject
) {
    /** 文档没展开的那几块（缓存、basic_auth、IP 限制、内容替换），原样带出去。 */
    val extraSections: List<String> = raw.keys.filter { it !in KnownConfKeys }.sorted()

    companion object {
        /** 那一口读不到时用得上：全空，且 [BtReverseProxyConf.raw] 里没有任何键可摊。 */
        val Empty =
            BtReverseProxyConf(emptyList(), null, null, null, "", null, "", JsonObject(emptyMap()))

        private val KnownConfKeys =
            setOf("domain_list", "ssl_info", "gzip", "websocket", "proxy_log")
    }
}

/**
 * 站点挂的一个域名（`get_domain_list` 的一项）。
 *
 * 注意和 [BtReverseProxyConf.domainList] 不是一回事：全局配置那口给的是 `"example.com:80"`
 * 这种拼好的字符串，这一口才是拆开的 `name` + `port` + `healthy`。两口的形状文档各写各的，
 * 所以各收各的，不做合并。
 */
data class BtReverseProxyDomain(
    val name: String,
    val port: String,
    /** `1` 是面板探活通过；这一格文档只给过 1，别的值一律按「面板没说」处理。 */
    val healthyText: String,
    val raw: JsonObject
) {
    val healthy: Boolean? get() = healthyText.trim().toFlag()

    val title: String get() = if (port.isBlank()) name else "$name:$port"
}

/** 代理站点的访问日志（`GetSiteLogs`，注意这个方法名是大驼峰，文档原样）。 */
data class BtReverseProxyLog(
    /** 日志正文整段装在 `data.msg` 里。 */
    val text: String,
    /** 面板算好的大小，形如 `0.00 b`、`12.30 KB`，原样显示。 */
    val size: String
)

// ── 解析 ──

/** 列表口：`data.data` 那一层由 [btItems] 负责找，这里只管逐行翻译。 */
fun btReverseProxyEntries(root: JsonElement?): List<BtReverseProxyEntry> =
    btItems(root).map { item ->
        BtReverseProxyEntry(
            id = btText(item["id"]),
            name = btText(item["name"]),
            path = btText(item["path"]),
            statusText = btText(item["status"]),
            remark = btText(item["ps"]),
            addTime = btText(item["addtime"]),
            expireTime = btText(item["edate"]),
            renamed = btText(item["rname"]),
            raw = item
        )
    }

fun btReverseProxyRules(root: JsonElement?): List<BtReverseProxyRule> =
    btItems(root).map { item ->
        BtReverseProxyRule(
            proxyType = btText(item["proxy_type"]),
            proxyPath = btText(item["proxy_path"]),
            proxyPass = btText(item["proxy_pass"]),
            proxyHost = btText(item["proxy_host"]),
            remark = btText(item["remark"]),
            raw = item
        )
    }

fun btReverseProxyConf(root: JsonElement?): BtReverseProxyConf {
    val obj = root as? JsonObject
        ?: return BtReverseProxyConf(emptyList(), null, null, null, "", null, "", JsonObject(emptyMap()))
    return BtReverseProxyConf(
        domainList = (obj["domain_list"] as? JsonArray)?.map { btText(it) }?.filter { it.isNotBlank() }
            ?: emptyList(),
        sslEnabled = nestedFlag(obj["ssl_info"], "ssl_status"),
        gzipEnabled = nestedFlag(obj["gzip"], "gzip_status"),
        websocketEnabled = nestedFlag(obj["websocket"], "websocket_status"),
        logType = btText((obj["proxy_log"] as? JsonObject)?.get("log_type")),
        cacheEnabled = nestedFlag(obj["proxy_cache"], "cache_status"),
        cacheExpires = btText((obj["proxy_cache"] as? JsonObject)?.get("expires")),
        raw = obj
    )
}

/** 域名列表：`data.domain_list`；有的版本直接给 `data` 数组，两种都认。 */
fun btReverseProxyDomains(root: JsonElement?): List<BtReverseProxyDomain> {
    val nested = (root as? JsonObject)?.get("domain_list") as? JsonArray
    val items = if (nested != null) nested.mapNotNull { it as? JsonObject } else btItems(root)
    return items.map { item ->
        BtReverseProxyDomain(
            name = btText(item["name"]),
            port = btText(item["port"]),
            healthyText = btText(item["healthy"]),
            raw = item
        )
    }
}

fun btReverseProxyLog(root: JsonElement?): BtReverseProxyLog {
    val obj = root as? JsonObject ?: return BtReverseProxyLog("", "")
    return BtReverseProxyLog(text = btText(obj["msg"]), size = btText(obj["size"]))
}

/** 文档里 `websocket` / `gzip` / `ssl_info` 是对象包一个布尔，个别版本也直接给布尔，两种都认。 */
private fun nestedFlag(element: JsonElement?, key: String): Boolean? = when (element) {
    is JsonObject -> (element[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toFlag() }
    else -> null
}

/** `"1"` / `"true"` / `"开"` 这类写法都当开；认不出来返回 null，让界面显示原文。 */
private fun String.toFlag(): Boolean? = when (lowercase()) {
    "1", "true", "yes", "on", "开", "已开启" -> true
    "0", "false", "no", "off", "关", "未开启" -> false
    else -> null
}

// ── 表单校验 ──

/**
 * 新建/添加代理的表单。校验放在发出去之前：面板那边一次失败也算一次调用，
 * 攒够 20 次签名或调用失败会被锁一小时，所以能本地拦下的一律不发。
 */
data class BtReverseProxyForm(
    /** 新建整站代理时是域名；往已有站点上加一条时这一格是站点名。 */
    val domains: String = "",
    val proxyPass: String = "",
    val proxyPath: String = "/",
    val proxyHost: String = "\$http_host",
    val proxyType: String = "http",
    val remark: String = ""
) {
    /** 返回第一条不满足的话；null 表示可以发。 */
    fun problem(): String? = when {
        domains.isBlank() -> "域名（站点名）不能空着"
        // 文档写死：主域名不能带泛解析，带 `*` 面板会拒。
        domains.contains("*") -> "主域名不能用泛解析（不能带 *）"
        proxyPass.isBlank() -> "目标地址不能空着"
        // 文档只认两种：http（默认）和 unix。unix 那一档填的是 socket 文件路径，不是 URL。
        proxyType == "unix" -> {
            if (!proxyPass.startsWith("/")) "unix 类型的目标要填 socket 文件路径（以 / 开头）" else null
        }
        !proxyPass.startsWith("http://") && !proxyPass.startsWith("https://") ->
            "目标地址要写成 http:// 或 https:// 开头"
        proxyPath.isNotBlank() && !proxyPath.startsWith("/") -> "匹配路径要以 / 开头"
        else -> null
    }

    val canSubmit: Boolean get() = problem() == null
}
