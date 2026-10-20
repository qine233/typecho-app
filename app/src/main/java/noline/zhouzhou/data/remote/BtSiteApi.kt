package noline.zhouzhou.data.remote

import kotlinx.serialization.json.JsonElement

/**
 * 网站模块（`POST /site`）的有类型调用。
 *
 * 这里每一个 action 名和参数名都在正式面板 13.1.0 上实测过（建一个一次性站点，跑完
 * 建→改→删再删干净）。三条实测出来的规矩决定了这一层的写法：
 * 1. 面板对**不认识的 action** 只回一句「指定参数无效!」，文档里没写的一律按这句话判生死，
 *    所以「删域名」「改伪静态」这两件面板端有的事在这里是**没有对应方法**的——真发不出去；
 * 2. `SetIndex` 的键名是大写 `Index`，发小写面板直接回 HTTP 400；
 * 3. 列表口 `getData table=sites` 一旦带上 `sort`/`order` 就回空数组，所以这里一个字都不多带。
 *
 * 只读的返回原始 JSON（由仓库层翻成模型），会改的返回面板那句话。
 */
class BtSiteApi(private val api: BtPanelApi) {

    private suspend fun get(action: String, params: Map<String, String> = emptyMap()): JsonElement =
        api.call(BtModule.Site, action, params).data

    /**
     * 回包没有 `data` 这一层的接口（GetSSL 是平铺对象、日志正文装在 `msg` 里）从这里取整棵树，
     * 不然 `.data` 会是个空对象，看着像「面板什么都没说」。
     */
    private suspend fun getWhole(action: String, params: Map<String, String> = emptyMap()): JsonElement =
        api.call(BtModule.Site, action, params).root

    private suspend fun put(action: String, params: Map<String, String>): String {
        val reply = api.call(BtModule.Site, action, params)
        return reply.msg.ifBlank { "面板已收下这条指令" }
    }

    // ── 站点列表与建站来源 ──

    /**
     * 站点列表走 `/data` 那张表：只有它能一次给到 `domain` 个数、`backup_count`、`ssl` 到期天数
     * 这些列表要用的字段，`/site` 侧没有等价的列表口。
     */
    suspend fun sites(page: Int, limit: Int, keyword: String): BtReply = api.call(
        BtModule.Data, "getData",
        buildMap {
            put("table", "sites")
            put("p", page.toString())
            put("limit", limit.toString())
            put("filtered", if (keyword.isBlank()) "false" else "true")
            if (keyword.isNotBlank()) put("search.value", keyword)
        }
    )

    /** 可切的 PHP 版本（`00` = 纯静态）。 */
    suspend fun phpVersions(): JsonElement = get("GetPHPVersion")

    /** 站点分类，建站表单的「网站分类」下拉。 */
    suspend fun siteTypes(): JsonElement = get("get_site_types")

    /** 默认站点标记；面板这边给的是布尔，站点列表只是候选，不能拿第一条当答案。 */
    suspend fun defaultSite(): JsonElement = getWhole("GetDefaultSite")

    // ── 站点本身的开关 ──

    /**
     * 建站。`domain` 只填主域名，`sql`/`ftp` 是「顺带建一个」的开关，面板那边会连着调
     * 数据库和 FTP 的接口，任何一半失败整站就不成立。
     *
     * 回包**没有 `msg`**，是 `{siteStatus,siteId,ftpStatus,databaseStatus,gitStatus}`：
     * 新站的 id 只在这棵树里，所以要整棵返回给仓库层。
     */
    suspend fun addSite(
        domain: String,
        path: String,
        phpVersion: String,
        port: String = "80",
        remark: String = "",
        typeId: String = "0",
        withDatabase: Boolean = false,
        withFtp: Boolean = false,
        charset: String = "utf8"
    ): JsonElement {
        val static = phpVersion == StaticPhpVersion
        val reply = api.call(
            BtModule.Site,
            "AddSite",
            buildMap {
                // 面板要的是整个域名列表的 JSON，只有主域名时 domainlist 就是空数组。
                put("webname", """{"domain":"$domain","domainlist":[],"count":0}""")
                put("path", path)
                put("type", if (static) "" else "PHP")
                put("version", phpVersion)
                put("port", port)
                put("type_id", typeId)
                put("ps", remark)
                put("sql", if (withDatabase) "true" else "false")
                put("ftp", if (withFtp) "true" else "false")
                put("codeing", charset)
            }
        )
        return reply.root
    }

    /** 删站。面板会把站点配置、证书、日志一起清掉，`webname` 要的是站点主域名。 */
    suspend fun deleteSite(id: String, webname: String): String =
        put("DeleteSite", mapOf("id" to id, "webname" to webname))

    /** 停用/启用站点。`name` 是站点主域名，不是域名列表里的某一个。 */
    suspend fun stopSite(id: String, name: String): String =
        put("SiteStop", mapOf("id" to id, "name" to name))

    suspend fun startSite(id: String, name: String): String =
        put("SiteStart", mapOf("id" to id, "name" to name))

    /** 站点备注名（面板列表上显示的那行小字）；传 `{domain,remarks}` 那套会回「参数错误」。 */
    suspend fun setRemark(id: String, remark: String): String =
        put("site_rname", mapOf("id" to id, "rname" to remark))

    /** 到期日期；`0000-00-00` 是面板里的「永久」。 */
    suspend fun setExpireDate(id: String, date: String): String =
        put("SetEdate", mapOf("id" to id, "edate" to date))

    /** 立刻备份一次站点文件。产物落在 `/www/backup/site/<站点名>/`，这里只回一句成没成。 */
    suspend fun backup(id: String): String = put("ToBackup", mapOf("id" to id))

    /**
     * 访问日志开关。**这是「切换」不是「设为开」**（实测带 `open` 参数也不改变翻转行为），
     * 当前状态要读 [dirUserIni] 里的 `logs`。
     */
    suspend fun toggleAccessLog(id: String): String = put("logsOpen", mapOf("id" to id))

    // ── 域名 ──

    suspend fun domains(id: String): JsonElement = get("GetSiteDomains", mapOf("id" to id))

    /** `webname` 是站点主域名，`domain` 是要加的那一个。 */
    suspend fun addDomain(id: String, webname: String, domain: String): String = put(
        "AddDomain", mapOf("id" to id, "webname" to webname, "domain" to domain)
    )

    // ── 网站设置 ──

    suspend fun sitePhpVersion(siteName: String): JsonElement =
        get("GetSitePHPVersion", mapOf("siteName" to siteName))

    /** 实测不需要先把站点停用，面板自己会切。 */
    suspend fun setPhpVersion(siteName: String, version: String): String =
        put("SetPHPVersion", mapOf("siteName" to siteName, "version" to version))

    suspend fun runPath(id: String): JsonElement = get("GetSiteRunPath", mapOf("id" to id))

    /** 文档没写这一条，参数名按正式面板实测：`id` + `runPath`（和 GetSiteRunPath 的回包同名）。 */
    suspend fun setRunPath(id: String, runPath: String): String =
        put("SetSiteRunPath", mapOf("id" to id, "runPath" to runPath))

    suspend fun index(id: String): JsonElement = get("GetIndex", mapOf("id" to id))

    /**
     * 改默认文档。**键名是大写 `Index`**（实测小写 `index` 会让面板回 HTTP 400），
     * 值是整个列表拼成的逗号串，顺序就是匹配优先级。
     */
    suspend fun setIndex(id: String, files: List<String>): String =
        put("SetIndex", mapOf("id" to id, "Index" to files.joinToString(",")))

    suspend fun dirBinding(id: String): JsonElement = get("GetDirBinding", mapOf("id" to id))

    /** 目录级配置：`logs` 布尔就是访问日志开没开，这一层只把它当状态读回来。 */
    suspend fun dirUserIni(id: String, path: String): JsonElement =
        get("GetDirUserINI", mapOf("id" to id, "path" to path))

    /** 站点绑的 FTP 账户（改绑要去 `set_sites_ftp`，没实测过，这里只读）。 */
    suspend fun siteFtp(siteName: String, siteId: String): JsonElement =
        get("get_sites_ftp", mapOf("siteName" to siteName, "site_id" to siteId))

    // ── 伪静态（只读）──

    /**
     * 该站可用的模板名单。回包 `{rewrite,default_list}` 两份一模一样，
     * **没有任何「在用哪个」的标记**，所以这一页只能列名单。
     *
     * 切换模板发不出去：`SetRewriteLists` 带 JSON 数组 `sites` 一律 HTTP 400，
     * 换成 `siteName` 只回一个 `[]` 且不写文件（rewrite conf 大小还是 0）。
     * 面板上点一下就能改的事，这个接口没给可用的口子，界面上也就不给按钮。
     */
    suspend fun rewrite(siteName: String): JsonElement = get("GetRewriteList", mapOf("siteName" to siteName))

    // ── SSL / HTTPS ──

    /**
     * 证书信息。回包是平铺对象，`key` 是私钥原文，解析层一次都不读，
     * 界面上也就没有能把私钥显示出来的路径。
     */
    suspend fun ssl(siteName: String): JsonElement = getWhole("GetSSL", mapOf("siteName" to siteName))

    /** 自己贴证书 + 私钥部署。这两个值只进签名后的表单体，不进日志。 */
    suspend fun setSsl(siteName: String, certificate: String, privateKey: String): String =
        put("SetSSL", mapOf("siteName" to siteName, "csr" to certificate, "key" to privateKey))

    /** 关掉 SSL。`updateOf` 文档只给了「固定 1」。 */
    suspend fun closeSsl(siteName: String): String =
        put("CloseSSLConf", mapOf("siteName" to siteName, "updateOf" to "1"))

    suspend fun enableForceHttps(siteName: String): String = put("HttpToHttps", mapOf("siteName" to siteName))

    /** 没开证书时面板会回一句「当前未开启SSL」，这句原样露出，不翻译成「失败」。 */
    suspend fun disableForceHttps(siteName: String): String = put("CloseToHttps", mapOf("siteName" to siteName))

    // ── 重定向 ──

    suspend fun redirects(siteName: String): JsonElement = get("GetRedirectList", mapOf("sitename" to siteName))

    /**
     * 建重定向。`domains` 是来源域名列表（JSON 数组字符串），`path` 是来源路径，
     * `kind` 决定按路径还是按域名匹配，`holdPath` 表示要不要把原路径拼到目标后面。
     */
    suspend fun createRedirect(
        siteName: String,
        name: String,
        target: String,
        domains: List<String>,
        path: String,
        kind: String,
        httpStatus: String,
        holdPath: Boolean
    ): String = put(
        "CreateRedirect",
        redirectForm(siteName, name, target, domains, path, kind, httpStatus, holdPath)
    )

    /** 改重定向：参数与建的同形，用 `sitename` + `redirectname` 定位那一条。 */
    suspend fun modifyRedirect(
        siteName: String,
        name: String,
        target: String,
        domains: List<String>,
        path: String,
        kind: String,
        httpStatus: String,
        holdPath: Boolean
    ): String = put("ModifyRedirect", redirectForm(siteName, name, target, domains, path, kind, httpStatus, holdPath))

    suspend fun deleteRedirect(siteName: String, name: String): String =
        put("DeleteRedirect", mapOf("sitename" to siteName, "redirectname" to name))

    private fun redirectForm(
        siteName: String,
        name: String,
        target: String,
        domains: List<String>,
        path: String,
        kind: String,
        httpStatus: String,
        holdPath: Boolean
    ) = mapOf(
        "sitename" to siteName,
        "redirectname" to name,
        "tourl" to target,
        // 文档要求 JSON 数组，空列表会让面板匹配不到来源，界面层负责保证不为空。
        "redirectdomain" to "[" + domains.joinToString(",") { "\"$it\"" } + "]",
        "redirectpath" to path,
        "redirecttype" to kind,
        "type" to httpStatus,
        "domainorpath" to kind,
        "holdpath" to if (holdPath) "1" else "0"
    )

    // ── 反向代理 ──

    suspend fun proxies(siteName: String): JsonElement = get("GetProxyList", mapOf("sitename" to siteName))

    /**
     * 建反向代理。`directory` 是被转发的那段路径（如 `/api`），`target` 是后端地址。
     * `subfilter` 是三条替换规则的 JSON，面板表单总会带上，这里按它自己的默认值给空壳。
     */
    suspend fun createProxy(
        siteName: String,
        name: String,
        directory: String,
        target: String,
        cache: Boolean,
        cacheSeconds: String = "0"
    ): String = put("CreateProxy", proxyForm(siteName, name, directory, target, cache, cacheSeconds))

    suspend fun modifyProxy(
        siteName: String,
        name: String,
        directory: String,
        target: String,
        cache: Boolean,
        cacheSeconds: String = "0"
    ): String = put("ModifyProxy", proxyForm(siteName, name, directory, target, cache, cacheSeconds))

    /** 删代理：只有 `RemoveProxy` 这个名字面板认识（`DeleteProxy`/`DelProxy`/`del_proxy` 全回「指定参数无效!」）。 */
    suspend fun removeProxy(siteName: String, name: String): String =
        put("RemoveProxy", mapOf("sitename" to siteName, "proxyname" to name))

    private fun proxyForm(
        siteName: String,
        name: String,
        directory: String,
        target: String,
        cache: Boolean,
        cacheSeconds: String
    ) = mapOf(
        "sitename" to siteName,
        "proxyname" to name,
        "proxydir" to directory,
        "proxysite" to target,
        "type" to "1",
        "cache" to if (cache) "1" else "0",
        "cachetime" to cacheSeconds,
        "todomain" to siteName,
        "subfilter" to """[{"sub1":"","sub2":""},{"sub1":"","sub2":""},{"sub1":"","sub2":""}]""",
        "advanced" to "0"
    )

    // ── 日志 ──

    /** 访问日志：正文实测装在 `msg` 位，一次能回 190KB，界面只截尾部。 */
    suspend fun accessLog(siteName: String): JsonElement = getWhole("GetSiteLogs", mapOf("siteName" to siteName))

    /** 错误日志：实测单次 1.7MB，仓库层必须截尾。 */
    suspend fun errorLog(siteName: String): JsonElement = getWhole("get_site_errlog", mapOf("siteName" to siteName))

    suspend fun logPath(siteName: String): JsonElement = get("get_sites_log_path", mapOf("siteName" to siteName))

    // ── 安全 ──

    suspend fun limitNet(id: String): JsonElement = get("GetLimitNet", mapOf("id" to id))

    /** 开限流：`SetLimitNet` 三个值必须都大于 0，全 0 面板回「必需大于0」——关掉走 [closeLimitNet]。 */
    suspend fun setLimitNet(id: String, perServer: Int, perIp: Int, rateKb: Int): String = put(
        "SetLimitNet",
        mapOf(
            "id" to id,
            "perserver" to perServer.toString(),
            "perip" to perIp.toString(),
            "limit_rate" to rateKb.toString()
        )
    )

    /** 关限流走批量口：`multiple_limit_net` 带 `close_limit_net`，面板回「已关闭流量限制!」。 */
    suspend fun closeLimitNet(id: String): String = put(
        "multiple_limit_net",
        mapOf(
            "site_ids" to "[$id]",
            "perserver" to "0",
            "perip" to "0",
            "limit_rate" to "0",
            "close_limit_net" to "true"
        )
    )

    /** 密码保护的当前状态：实测只回布尔，**不给用户名**，所以界面上「改密码」得账号密码一起重填。 */
    suspend fun hasPwd(id: String): JsonElement = get("GetHasPwd", mapOf("id" to id))

    /** 面板限制密码不超过 8 位，超了它自己会回一句，界面提前挡住。 */
    suspend fun setHasPwd(id: String, username: String, password: String): String =
        put("SetHasPwd", mapOf("id" to id, "username" to username, "password" to password))

    suspend fun closeHasPwd(id: String): String = put("CloseHasPwd", mapOf("id" to id))

    suspend fun status301(siteName: String): JsonElement = get("Get301Status", mapOf("siteName" to siteName))

    /**
     * 域名级 301：把 `from` 整域跳到 `to`。两个值都要**带协议的完整 URL**，
     * 光给域名面板回「Url地址不正确!」。写完 `Get301Status` 仍可能显示未开启，别拿它当凭据。
     */
    suspend fun set301(siteName: String, from: String, to: String, type: String = "1"): String = put(
        "Set301Status",
        mapOf("siteName" to siteName, "srcDomain" to from, "toDomain" to to, "type" to type)
    )

    suspend fun securityHeaders(siteName: String): JsonElement =
        get("get_security_headers", mapOf("site_name" to siteName))

    /** `security_info` 是那五项布尔的 JSON，键名要和读回来的一致。 */
    suspend fun setSecurityHeaders(siteName: String, enabled: Map<String, Boolean>): String = put(
        "set_security_headers",
        mapOf(
            "site_name" to siteName,
            "security_info" to enabled.entries.joinToString(",") { (key, value) -> "\"$key\":${value.lowercaseWire()}" }
                .let { "{$it}" }
        )
    )

    /** 目录级密码保护列表（一个站可能有多段目录加了锁）。 */
    suspend fun dirAuth(id: String, siteName: String): JsonElement =
        get("get_dir_auth", mapOf("id" to id, "siteName" to siteName))

    private fun Boolean.lowercaseWire() = if (this) "true" else "false"

    companion object {
        /** 面板里「纯静态」这个选项的 version 值。 */
        const val StaticPhpVersion = "00"
    }
}
