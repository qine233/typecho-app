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
 * 网站管理这一层的读模型。
 *
 * 键名按正式面板 13.1.0 的实测回包钉死：站点表一行是 `id/name/path/status/ps/addtime/edate/
 * project_type/php_version/stop/backup_count/domain/ssl/proxy/redirect`，其中 `domain` 是**个数**
 * 而不是列表，`ssl` 未部署时直接给 -1，`proxy`/`redirect` 是布尔。详情各页签的壳层各不相同
 * （`GetSiteDomains` 裹 `domains`、`GetIndex` 是裸字符串、`GetSiteRunPath` 给 `{runPath,dirs}`），
 * 所以每个页签有自己的解析函数，不硬套同一个形状。
 */

/** 站点证书那一小块；面板给的是「剩几天」而不是日期差，直接用它。 */
data class BtSiteSsl(
    val deployed: Boolean,
    val issuer: String,
    val notAfter: String,
    val daysLeft: Int?,
    val domains: List<String> = emptyList()
) {
    val isExpiring: Boolean get() = daysLeft?.let { it <= 15 } == true
    val summary: String
        get() = when {
            !deployed -> "未部署证书"
            daysLeft == null -> listOfNotNull("至 $notAfter".takeIf { notAfter.isNotBlank() }, issuer.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            daysLeft < 0 -> "已过期，待续签"
            else -> listOfNotNull("剩 $daysLeft 天", notAfter.takeIf { it.isNotBlank() }?.let { "至 $it" })
                .joinToString(" · ")
        }
}

/** 站点列表里的一行。`raw` 留着给详情弹窗把面板还说了什么摊出来。 */
data class BtSite(
    val id: String,
    val name: String,
    val path: String,
    val remark: String,
    val addTime: String,
    val expireDate: String,
    val typeLabel: String,
    val phpVersion: String,
    val running: Boolean,
    val stopReason: String,
    val domainCount: Int?,
    val backupCount: Int?,
    val hasProxy: Boolean,
    val hasRedirect: Boolean,
    val ssl: BtSiteSsl,
    val raw: JsonObject
) {
    /** 列表副标题：域名几个 + 根目录，够认站就行，其余在详情页看。 */
    val subtitle: String
        get() = listOfNotNull(
            domainCount?.takeIf { it > 0 }?.let { "$it 个域名" },
            path.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

    val badge: String
        get() = when {
            !running -> "已停用"
            else -> listOfNotNull(
                typeLabel,
                phpVersion.takeIf { it.isNotBlank() && it != "静态" && typeLabel.endsWith("PHP") }
            ).filter { it.isNotBlank() }.joinToString(" ")
        }
}

/** 域名管理页签：`domains[]` 一项一个域名，`binding` 说明它是子目录绑来的。 */
data class BtSiteDomain(
    val name: String,
    val rootDomain: String,
    val isSubDirectoryBinding: Boolean,
    val dnsOk: Boolean?,
    val sslApplied: Boolean
)

/** 运行目录页签：`runPath` 是当前目录，`dirs` 是可选项。 */
data class BtRunPath(val runPath: String, val dirs: List<String>)

/** 默认文档页签：面板回的是逗号分隔的一行，顺序就是优先级。 */
data class BtSiteIndex(val files: List<String>)

/**
 * 伪静态：这一层只有名单，没有「在用哪个」。
 *
 * 实测 `GetRewriteList` 回的 `rewrite` 和 `default_list` 两份一模一样，也没有任何在用标记，
 * 而面板上没有别的只读口能问出「这站现在套的是哪个模板」——所以模型里根本不放这个字段，
 * 界面上也就不会出现一个「当前：xxx」的假答案。
 */
data class BtRewrite(val templates: List<String>)

/** 反向代理一条规则。 */
data class BtProxyRule(
    val name: String,
    val directory: String,
    val target: String,
    val domain: String,
    val type: String,
    val cache: String
)

/** 重定向一条规则。 */
data class BtRedirectRule(
    val name: String,
    val target: String,
    val domains: List<String>,
    val path: String,
    val kind: String,
    val code: String
)

/** 访问限制页签：三个 0 就是没开限流。 */
data class BtLimitNet(val perServer: Int?, val perIp: Int?, val rateKb: Int?, val enabled: Boolean)

/** 301 转页签。 */
data class Bt301Status(val enabled: Boolean, val from: String, val to: String, val url: String)

/** 安全响应头页签：五项开关。 */
data class BtSecurityHeaders(
    val xssProtection: Boolean,
    val contentTypeOptions: Boolean,
    val referrerPolicy: Boolean,
    val contentSecurityPolicy: Boolean,
    val permissionsPolicy: Boolean
)

/** 证书详情：只取能安全露出的部分，私钥那三个键根本不读。 */
data class BtSslDetail(
    val deployed: Boolean,
    val domains: List<String>,
    val authType: String,
    val httpToHttps: Boolean,
    val tlsVersions: List<String>,
    val expiresAt: String
)

/** 网站日志：面板把整段文本塞在 msg 里（实测能到 190KB 一行），界面必须截着看。 */
data class BtSiteLog(val text: String, val path: String) {
    val isEmpty: Boolean get() = text.isBlank()
}

/** 站点分类（`get_site_types`），建站表单里选「网站分类」用。 */
data class BtSiteType(val id: String, val name: String)

/** 建站表单和 PHP 下拉要的三个来源合一份，页面少等三跳。 */
data class BtSiteOptions(
    val phpVersions: List<BtPhpVersion>,
    val siteTypes: List<BtSiteType>,
    val defaultSite: String
)

/** 密码保护：实测面板只回布尔，`username` 是「认得出就给、认不出就空」。 */
data class BtPwdProtection(val enabled: Boolean, val username: String)

/** 「网站设置」页签一次拉齐的五样。 */
data class BtSiteSettings(
    val phpCurrent: String,
    val phpVersions: List<BtPhpVersion>,
    val runPath: BtRunPath,
    val index: BtSiteIndex,
    val binding: BtRunPath,
    val ftp: String
)

/** 「安全」页签一次拉齐的四样。 */
data class BtSiteSecurity(
    val limit: BtLimitNet,
    val password: BtPwdProtection,
    val redirect301: Bt301Status,
    val headers: BtSecurityHeaders
)

/** 可切的 PHP 版本；`version` 为 `00` 表示切成纯静态。 */
data class BtPhpVersion(val version: String, val name: String, val installed: Boolean)

/**
 * 建站回执。`AddSite` 成功时面板**不给 `msg`**，给的是
 * `{siteStatus,siteId,ftpStatus,databaseStatus,gitStatus}`——新站的编号只在这棵树里，
 * 顺带的数据库/FTP 建没建成也只看这里。
 */
data class BtSiteCreation(val siteId: String, val parts: List<BtField>) {
    val summary: String
        get() = (listOfNotNull(siteId.takeIf { it.isNotBlank() }?.let { "站点编号 $it" }) +
            parts.map { "${it.label} ${it.value}" }).joinToString(" · ")
}

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
        if (value == null || value is JsonNull) continue
        value.jsonPrimitive.contentOrNull?.toDoubleOrNull()?.toInt()?.let { return it }
    }
    return null
}

private fun JsonObject.flagOf(vararg keys: String): Boolean {
    for (key in keys) {
        val value = this[key] ?: continue
        when (value) {
            is JsonPrimitive -> {
                value.booleanOrNull?.let { return it }
                val text = value.contentOrNull.orEmpty()
                if (text.isBlank()) continue
                return text != "0" && !text.equals("false", ignoreCase = true)
            }
            // 空对象/空数组/空的域名列表都算「没配」，非空就是配了。
            is JsonObject -> if (value.isNotEmpty()) return true
            is JsonArray -> if (value.isNotEmpty()) return true
            else -> Unit
        }
    }
    return false
}

private fun btStringArray(element: JsonElement?): List<String> =
    (element as? JsonArray)?.map(::btText)?.filter { it.isNotBlank() } ?: emptyList()

/** 面板的 `status` 实测是字符串 `"1"`（正常）/`"0"`（停用），也见过布尔和 `正常`/`已停用`。 */
private fun siteRunning(text: String): Boolean = when (text) {
    "", "1", "是", "正常", "true" -> true
    "0", "false" -> false
    else -> !text.contains("停") && !text.contains("异常")
}

/**
 * `project_type` 实测 PHP 站给 `PHP`（大写）、静态站给 `html`；`php_version` 静态站写的是「静态」。
 * 不认识的值原样露出，别把 `node` 说成 PHP。
 */
private fun siteTypeLabel(text: String): String = when (text.lowercase()) {
    "" -> ""
    "html" -> "纯静态"
    "php" -> "PHP"
    "proxy" -> "反向代理"
    else -> text
}

private fun parseSsl(element: JsonElement?): BtSiteSsl {
    val obj = element as? JsonObject ?: return BtSiteSsl(false, "", "", null)
    return BtSiteSsl(
        deployed = true,
        issuer = listOf(obj.textOf("issuer_O"), obj.textOf("issuer")).filter { it.isNotBlank() }
            .distinct().joinToString(" "),
        notAfter = obj.textOf("notAfter", "endtime_date"),
        daysLeft = obj.intOf("endtime"),
        domains = btStringArray(obj["dns"])
    )
}

/** `/data?action=getData&table=sites`：数据在 `data` 里，是裸数组。 */
fun btSites(root: JsonElement?): List<BtSite> = btItems(root).mapNotNull { item ->
    val id = item.textOf("id", "site_id")
    val name = item.textOf("name", "rname", "domain_name")
    if (id.isBlank() && name.isBlank()) return@mapNotNull null
    BtSite(
        id = id,
        name = name,
        path = item.textOf("path", "dir"),
        remark = item.textOf("ps", "rname"),
        addTime = item.textOf("addtime", "add_time"),
        expireDate = item.textOf("edate"),
        typeLabel = siteTypeLabel(item.textOf("project_type")),
        phpVersion = item.textOf("php_version", "version"),
        running = siteRunning(item.textOf("status")),
        stopReason = item.textOf("stop"),
        domainCount = item.intOf("domain", "domain_count"),
        backupCount = item.intOf("backup_count"),
        hasProxy = item.flagOf("proxy"),
        hasRedirect = item.flagOf("redirect"),
        ssl = parseSsl(item["ssl"]),
        raw = item
    )
}

/** 到期时间：`0000-00-00` 在面板里就是「永久」，不是脏数据。 */
fun btExpireText(edate: String): String =
    if (edate.isBlank() || edate.startsWith("0000")) "永久" else edate

/** `GetSiteDomains`：壳是 `{domains:[…], email:"…"}`，`email` 是 DNS API 的邮箱，不露。 */
fun btSiteDomains(root: JsonElement?): List<BtSiteDomain> {
    val list = (root as? JsonObject)?.let { it["domains"] as? JsonArray } ?: root as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.textOf("name", "domain")
        if (name.isBlank()) return@mapNotNull null
        BtSiteDomain(
            name = name,
            rootDomain = item.textOf("root_domain_id", "root_domain"),
            isSubDirectoryBinding = (item["binding"] as? JsonPrimitive)?.booleanOrNull ?: false,
            dnsOk = (item["dns_status"] as? JsonPrimitive)?.let {
                // ?: 比 == 结合得更紧，这一对括号不给比较包起来，布尔位会被拿去和 0.0 比大小。
                it.booleanOrNull ?: ((it.contentOrNull?.toDoubleOrNull() ?: 0.0) == 1.0)
            },
            sslApplied = (item["apply_ssl"] as? JsonPrimitive)?.let {
                it.booleanOrNull ?: ((it.contentOrNull?.toDoubleOrNull() ?: 0.0) != 0.0)
            } == true
        )
    }
}

/** `GetSiteRunPath` / `GetDirBinding`：前者 `{runPath,dirs}`，后者 `{run_path,dirs,binding}`。 */
fun btRunPath(root: JsonElement?): BtRunPath {
    val obj = root as? JsonObject ?: return BtRunPath("/", emptyList())
    val dirs = btStringArray(obj["dirs"]).toMutableList()
    val binding = btItems(obj["binding"]).map { it.textOf("dir", "name", "domain") }.filter { it.isNotBlank() }
    binding.forEach { if (it !in dirs) dirs.add(it) }
    return BtRunPath(obj.textOf("runPath", "run_path").ifBlank { "/" }, dirs)
}

/** `GetIndex`：实测回一个裸字符串 `"index.php,index.html,…"`，没有信封。 */
fun btSiteIndex(root: JsonElement?): BtSiteIndex = when (root) {
    is JsonPrimitive -> BtSiteIndex(btText(root).split(',').map { it.trim() }.filter { it.isNotBlank() })
    is JsonArray -> BtSiteIndex(btStringArray(root))
    is JsonObject -> BtSiteIndex(
        btText(root["index"]).split(',').map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { btStringArray(root["Index"]) }
    )
    else -> BtSiteIndex(emptyList())
}

/**
 * `GetRewriteList`：实测 `{rewrite:[模板名…], default_list:[…]}`，两份内容一模一样，
 * **没有「在用」标记**，取第一份就够。「0.当前」这类占位行是面板下拉的表头，不是模板名。
 */
fun btRewrite(root: JsonElement?): BtRewrite {
    val obj = root as? JsonObject
    val array = (obj?.get("rewrite") as? JsonArray) ?: (obj?.get("default_list") as? JsonArray)
    ?: root as? JsonArray ?: return BtRewrite(emptyList())
    val templates = array.mapNotNull { element ->
        when (element) {
            is JsonObject -> element.textOf("name", "rewrite")
            else -> btText(element)
        }
    }.filter { it.isNotBlank() && !it.contains("当前") }
    return BtRewrite(templates.distinct())
}

/** `GetProxyList`：裸数组，字段名是小写连写。 */
fun btProxyRules(root: JsonElement?): List<BtProxyRule> = btItems(root).map { item ->
    BtProxyRule(
        name = item.textOf("proxyname", "name"),
        directory = item.textOf("proxydir", "dir"),
        target = item.textOf("proxysite", "url"),
        domain = item.textOf("todomain"),
        type = item.textOf("type"),
        cache = item.textOf("cache", "cachetime")
    )
}.filter { it.name.isNotBlank() || it.target.isNotBlank() }

/** `GetRedirectList`：裸数组，`redirectdomain` 是数组，`type` 是 301/302 的数字。 */
fun btRedirectRules(root: JsonElement?): List<BtRedirectRule> = btItems(root).map { item ->
    val domains = (item["redirectdomain"] as? JsonArray)?.map(::btText)?.filter { it.isNotBlank() }
        ?: listOf(btText(item["redirectdomain"])).filter { it.isNotBlank() }
    BtRedirectRule(
        name = item.textOf("redirectname", "name"),
        target = item.textOf("tourl", "url"),
        domains = domains,
        path = item.textOf("redirectpath", "path"),
        kind = item.textOf("redirecttype", "domainorpath"),
        code = item.textOf("type")
    )
}.filter { it.name.isNotBlank() || it.target.isNotBlank() }

/** `GetLimitNet`：实测 `{perserver,perip,limit_rate,value}`，`value` 是总开关。 */
fun btLimitNet(root: JsonElement?): BtLimitNet {
    val obj = root as? JsonObject ?: return BtLimitNet(null, null, null, false)
    val server = obj.intOf("perserver")
    val ip = obj.intOf("perip")
    val rate = obj.intOf("limit_rate")
    val opened = (obj["value"] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: ((it.contentOrNull?.toDoubleOrNull() ?: 0.0) != 0.0)
    } ?: false
    return BtLimitNet(server, ip, rate, opened || listOf(server, ip, rate).any { (it ?: 0) > 0 })
}

/** `Get301Status`：`{domain,src,status,url}`，没开时 status 是 false。 */
fun bt301Status(root: JsonElement?): Bt301Status {
    val obj = root as? JsonObject ?: return Bt301Status(false, "", "", "")
    return Bt301Status(
        enabled = (obj["status"] as? JsonPrimitive)?.booleanOrNull
            ?: btText(obj["status"]).let { it.isNotBlank() && it != "0" && it != "否" },
        from = obj.textOf("src", "domain"),
        to = obj.textOf("url"),
        url = obj.textOf("url")
    )
}

/** `get_security_headers`：五项全是布尔，面板没给的那项当没开。 */
fun btSecurityHeaders(root: JsonElement?): BtSecurityHeaders {
    val obj = root as? JsonObject ?: return BtSecurityHeaders(false, false, false, false, false)
    fun flag(vararg keys: String) = keys.any { (obj[it] as? JsonPrimitive)?.booleanOrNull == true }
    return BtSecurityHeaders(
        xssProtection = flag("x_xss_protection"),
        contentTypeOptions = flag("x_content_type_options"),
        referrerPolicy = flag("referrer_policy"),
        contentSecurityPolicy = flag("content_security_policy"),
        permissionsPolicy = flag("permissions_policy")
    )
}

/**
 * `GetSSL`：这个回包里有 `key`（私钥原文），解析层一次都不读它，
 * 详情模型里也就没有能装下私钥的字段。
 */
fun btSslDetail(root: JsonElement?): BtSslDetail {
    val obj = root as? JsonObject ?: return BtSslDetail(false, emptyList(), "", false, emptyList(), "")
    val domains = btItems(obj["domain"]).map { it.textOf("name") }.filter { it.isNotBlank() }
    val tls = (obj["tls_versions"] as? JsonObject)
        ?.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }
        ?.keys?.toList() ?: emptyList()
    return BtSslDetail(
        deployed = obj["status"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true,
        domains = domains,
        authType = obj.textOf("auth_type"),
        httpToHttps = (obj["httpTohttps"] as? JsonPrimitive)?.booleanOrNull ?: false,
        tlsVersions = tls,
        expiresAt = obj.textOf("endtime", "notAfter")
    )
}

/** `GetPHPVersion` / `get_site_types`：都是 `{version|id, name, status}` 的裸数组。 */
fun btPhpVersions(root: JsonElement?): List<BtPhpVersion> = btItems(root).map { item ->
    BtPhpVersion(
        version = item.textOf("version", "id"),
        name = item.textOf("name"),
        installed = (item["status"] as? JsonPrimitive)?.booleanOrNull
            ?: btText(item["status"]).let { it.isBlank() || it == "1" || it == "是" }
    )
}.filter { it.version.isNotBlank() }

fun btSiteTypes(root: JsonElement?): List<BtSiteType> = btItems(root).map { item ->
    BtSiteType(item.textOf("id", "type_id"), item.textOf("name"))
}.filter { it.id.isNotBlank() || it.name.isNotBlank() }

/**
 * `AddSite` 的回包：`siteId` 可能是数字也可能是字符串，顺带的状态位是布尔。
 * 认不出编号就回空串，界面顶多说「面板没给编号」，不说「建好了」——建站成功与否由调用方
 * 用「能不能读到这个站」来判，这一层只负责把面板给的东西搬出来。
 */
fun btSiteCreation(root: JsonElement?): BtSiteCreation {
    val obj = root as? JsonObject ?: return BtSiteCreation("", emptyList())
    val parts = listOf(
        "siteStatus" to "站点", "databaseStatus" to "数据库", "ftpStatus" to "FTP", "gitStatus" to "Git"
    ).mapNotNull { (key, label) ->
        (obj[key] as? JsonPrimitive)?.booleanOrNull?.let { BtField(label, if (it) "已建" else "没建") }
            ?: btText(obj[key]).takeIf { it.isNotBlank() }?.let { BtField(label, it) }
    }
    return BtSiteCreation(obj.textOf("siteId", "id"), parts)
}

/**
 * `GetDefaultSite`：实测 `{sites:[{name}…], defaultSite:false}`。
 * `defaultSite` 是布尔而不是站点名，`sites` 只是能当默认站的候选列表——
 * 拿第一条当「当前默认站」会说错话，所以认不出名字就照实说不知道。
 */
fun btDefaultSite(root: JsonElement?): String {
    val obj = root as? JsonObject ?: return ""
    val flag = obj["defaultSite"] ?: return ""
    // 布尔位（面板把 true/false 原样给回来也是这一支）里没有名字，别拿候选列表第一条冒充答案。
    if ((flag as? JsonPrimitive)?.booleanOrNull != null) return ""
    val text = btText(flag).trim()
    return text.takeIf { it.matches(Regex("""[\w.-]+(:\d+)?""")) }.orEmpty()
}

/**
 * `GetSitePHPVersion`：实测是 `{phpversion:"84", tomcat:-1, nginx:-1, …}`。
 * 认不出当前版本就回空串，界面上写「面板没给」而不是「未知 0」。
 */
fun btSitePhpVersion(root: JsonElement?): String {
    val obj = root as? JsonObject ?: return btText(root).takeIf { it.matches(Regex("""\d{2,3}""")) }.orEmpty()
    return obj.textOf("phpversion", "php_version", "version")
}

/**
 * `get_sites_log_path`：这一条没有实测过形状（字符串、`{path}`、`{logs}` 都有可能），
 * 所以只认「一眼像路径」的值，认不出就回空串让界面那一行消失，不猜。
 */
fun btLogPath(root: JsonElement?): String {
    val candidates = when (root) {
        is JsonObject -> listOf(btText(root["path"]), btText(root["logs"]), btText(root["msg"]), btText(root["data"]))
        is JsonPrimitive -> listOf(btText(root))
        else -> emptyList()
    }
    return candidates.map { it.trim() }.firstOrNull { it.startsWith("/") && it.length < 200 }.orEmpty()
}

/** 访问日志/错误日志：正文在 `msg`（实测是几十 KB 的一整段），有的版本给 `{logs:…}`。 */
fun btSiteLogText(root: JsonElement?): String {
    val obj = root as? JsonObject
    val inline = obj?.textOf("logs", "log", "content").orEmpty()
    if (inline.isNotBlank()) return inline
    // 正文有的版本塞在 msg 里；msg 给的是布尔或对象时不能当成日志内容念出来。
    val msg = obj?.get("msg")
    return if (msg is JsonPrimitive && msg.isString) msg.contentOrNull.orEmpty() else btText(root)
}

/**
 * `GetHasPwd`：实测**只回布尔**（开着给 `true`，没开给 `false`），用户名和密码都不给。
 * 老版本给 `{username,…}` 对象，两种都认；对象里也没有就不硬造一个用户名，
 * 界面上「改密码」因此必须账号和密码一起重填。
 */
fun btPwdProtection(root: JsonElement?): BtPwdProtection {
    if (root is JsonObject) {
        val user = root.textOf("username", "user")
        val on = (root["pwd"] as? JsonPrimitive)?.booleanOrNull
            ?: (root["status"] as? JsonPrimitive)?.booleanOrNull
            ?: user.isNotBlank()
        return BtPwdProtection(on, if (on) user else "")
    }
    val on = (root as? JsonPrimitive)?.booleanOrNull
        ?: btText(root).let { it.isNotBlank() && it != "否" && it != "0" && it != "-1" }
    return BtPwdProtection(on, "")
}

/**
 * `GetDirUserINI`：这一层只问一件事——访问日志开没开（`logs`）。
 * 面板没给这个键就回 null，界面写成「面板没给」而不是「关着」。
 */
fun btAccessLogEnabled(root: JsonElement?): Boolean? {
    val obj = root as? JsonObject ?: return null
    return (obj["logs"] as? JsonPrimitive)?.booleanOrNull
        ?: btText(obj["logs"]).takeIf { it.isNotBlank() }?.let { it != "否" && it != "0" && !it.equals("false", true) }
}

/** `get_sites_ftp`：给的是这个站绑的 FTP 账户，可能一项也可能一串；密码字段一律不读。 */
fun btSiteFtp(root: JsonElement?): String = when (root) {
    is JsonObject -> root.textOf("ftp_name", "name", "username")
    is JsonArray -> btItems(root).firstOrNull()?.textOf("ftp_name", "name", "username").orEmpty()
    else -> ""
}
