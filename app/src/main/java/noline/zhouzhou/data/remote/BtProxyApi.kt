package noline.zhouzhou.data.remote

import kotlinx.serialization.json.JsonElement
import noline.zhouzhou.data.model.BtProxyEngine

/**
 * 反向代理模块（`POST /mod/proxy/com/<方法>/<stype>`）的有类型调用。
 *
 * 读侧的四个口（列表 / 规则 / 全局设置 / 域名）已经在 13.1.0 的正式面板上逐口对过，回包能解析、
 * 没有报错；写侧只在自建 mock 面板上走过一遍，正式面板一口都没发。下面三条从文档里钉下来的规矩
 * 决定了写法：
 *
 * 1. 全部是 POST，动作既在路径最后一段、也要在请求里再带一份 `action`（`get_global_conf`
 *    那页把 `action` 明列为必填），所以每个调用都走 [post]；
 * 2. 路径里那段 `stype` 是**这台服务器装的 web 软件**（nginx / apache / openresty），
 *    不是参数。装错了整块就是空的，所以引擎由界面选、写进仓库层，不写死；
 * 3. 方法名有三处不按直觉来：`set_remak`（文档就是少一个 r）、`GetSiteLogs`（大驼峰）、
 *    以及删除叫 `delete`。这三个一律照文档原样拼，改成「正确拼写」只会换来面板一句报错。
 *
 * 还有一件文档里钉死的事：**15 个 action 里没有「改一条规则」，也没有「删一条规则」**。
 * 规则的入口只有 [rules]（读）和 [addRule]（加），能删的只有整个站点 [deleteProxy]。
 * 所以详情页对「改转发目标」只给一句解释，不假装提供一个 modify 接口。
 */
class BtProxyApi(private val api: BtPanelApi) {

    private fun path(method: String, engine: BtProxyEngine) =
        "/mod/proxy/com/$method/${engine.wire}"

    /**
     * 代理这一整块只有 POST。返回整个 [BtReply] 而不是 `.data`：列表口的总条数在
     * `data.page` 那串分页 HTML 里（和 `/data` 那张表一个套路），只拿 data 就永远数不出总数；
     * 写操作的产出又全在 `msg` 那一格。
     */
    private suspend fun post(
        method: String,
        engine: BtProxyEngine,
        params: Map<String, String>
    ): BtReply = api.callPath(path(method, engine), method, params, "POST")

    private suspend fun data(method: String, engine: BtProxyEngine, params: Map<String, String>): JsonElement =
        post(method, engine, params).data

    private suspend fun say(
        method: String,
        engine: BtProxyEngine,
        params: Map<String, String>,
        fallback: String
    ): String = post(method, engine, params).msg.ifBlank { fallback }

    // ── 只读 ──

    /**
     * 代理项目列表。分页参数就叫 `p` / `limit`，搜索叫 `search`，三个都可省。
     *
     * 回包裹了两层 `data`：`{"data":{"where":…,"page":"共2条…","data":[…]}}`。
     */
    suspend fun entries(
        engine: BtProxyEngine,
        page: Int,
        limit: Int,
        keyword: String
    ): BtReply = post(
        "get_list",
        engine,
        buildMap {
            put("p", page.toString())
            put("limit", limit.toString())
            if (keyword.isNotBlank()) put("search", keyword)
        }
    )

    /** 一个站点下的代理规则（一个站点可以有好几条，按 `proxy_path` 区分）。 */
    suspend fun rules(siteName: String, engine: BtProxyEngine): JsonElement =
        data("get_proxy_list", engine, mapOf("site_name" to siteName))

    /** 站点的代理全局设置：gzip、websocket、日志、缓存、basic_auth、IP 限制都在这一份里。 */
    suspend fun globalConf(siteName: String, engine: BtProxyEngine): JsonElement =
        data("get_global_conf", engine, mapOf("site_name" to siteName))

    /** 站点挂的域名和探活状态。`id` 是列表里那条代理的 id。 */
    suspend fun domains(id: String, siteName: String, engine: BtProxyEngine): JsonElement =
        data("get_domain_list", engine, mapOf("id" to id, "site_name" to siteName))

    /** 访问日志：正文装在 `data.msg`，大小装在 `data.size`。 */
    suspend fun siteLogs(siteName: String, engine: BtProxyEngine): JsonElement =
        data("GetSiteLogs", engine, mapOf("site_name" to siteName))

    // ── 低风险写 ──

    /**
     * 新建一个反向代理站点。`domains` 只填主域名，文档明写**不能带泛解析的 `*`**。
     */
    suspend fun createProxy(
        engine: BtProxyEngine,
        domains: String,
        proxyPass: String,
        proxyPath: String,
        proxyHost: String,
        proxyType: String,
        remark: String
    ): String = say(
        "create",
        engine,
        buildMap {
            put("domains", domains)
            put("proxy_pass", proxyPass)
            put("proxy_path", proxyPath)
            put("proxy_host", proxyHost)
            put("proxy_type", proxyType)
            put("remark", remark)
        },
        "反向代理项目已交给面板创建"
    )

    /** 往已有站点上再加一条代理规则。同一个 `proxy_path` 不能重复添加。 */
    suspend fun addRule(
        engine: BtProxyEngine,
        siteName: String,
        proxyPath: String,
        proxyPass: String,
        proxyHost: String,
        proxyType: String,
        remark: String
    ): String = say(
        "add_proxy",
        engine,
        buildMap {
            put("site_name", siteName)
            put("proxy_path", proxyPath)
            put("proxy_pass", proxyPass)
            put("proxy_host", proxyHost)
            put("proxy_type", proxyType)
            put("remark", remark)
        },
        "代理规则已提交"
    )

    /** 改备注。方法名文档里就叫 `set_remak`，少一个 r。 */
    suspend fun setRemark(siteName: String, id: String, remark: String, engine: BtProxyEngine): String =
        say(
            "set_remak",
            engine,
            mapOf("site_name" to siteName, "id" to id, "remark" to remark),
            "备注已提交"
        )

    /** 代理缓存开关。`expires` 是文档给的缓存时长写法（如 `1d`），关的时候不必带。 */
    suspend fun setCache(siteName: String, enabled: Boolean, expires: String, engine: BtProxyEngine): String =
        say(
            "set_global_cache",
            engine,
            buildMap {
                put("site_name", siteName)
                put("cache_status", if (enabled) "1" else "0")
                if (enabled && expires.isNotBlank()) put("expires", expires)
            },
            if (enabled) "已开启代理缓存" else "已关闭代理缓存"
        )

    /** gzip：开关必带，另外三格是压缩阈值/级别/类型，留空就按面板默认。 */
    suspend fun setGzip(
        siteName: String,
        enabled: Boolean,
        minLength: String,
        compLevel: String,
        types: String,
        engine: BtProxyEngine
    ): String = say(
        "set_global_gzip",
        engine,
        buildMap {
            put("site_name", siteName)
            put("gzip_status", if (enabled) "1" else "0")
            if (enabled) {
                if (minLength.isNotBlank()) put("gzip_min_length", minLength)
                if (compLevel.isNotBlank()) put("gzip_comp_level", compLevel)
                if (types.isNotBlank()) put("gzip_types", types)
            }
        },
        if (enabled) "已开启 gzip" else "已关闭 gzip"
    )

    /** websocket 升级开关。这一口文档也要求带 `websocket_status`，只给 site_name 会被当参数缺失。 */
    suspend fun setWebsocket(siteName: String, enabled: Boolean, engine: BtProxyEngine): String =
        say(
            "set_global_websocket",
            engine,
            mapOf("site_name" to siteName, "websocket_status" to if (enabled) "1" else "0"),
            if (enabled) "已开启 websocket" else "已关闭 websocket"
        )

    /**
     * 访问日志去向：`default` / `file` / `rsyslog` / `off`。
     * 后两种要落路径，所以 [logPath] 在 `file`、`rsyslog` 下是必填 —— 校验放在仓库层。
     */
    suspend fun setLog(
        siteName: String,
        logType: String,
        logPath: String,
        engine: BtProxyEngine
    ): String = say(
        "set_global_log",
        engine,
        buildMap {
            put("site_name", siteName)
            put("log_type", logType)
            if (logPath.isNotBlank()) put("log_path", logPath)
        },
        "日志设置已提交"
    )

    /** 整站停用/启用。`site_status` 是 1 启用、0 停用，传的是站点 id 而不是代理 id。 */
    suspend fun setSiteStop(siteName: String, siteId: String, enabled: Boolean, engine: BtProxyEngine): String =
        say(
            "set_site_stop",
            engine,
            mapOf(
                "site_name" to siteName,
                "site_id" to siteId,
                "site_status" to if (enabled) "1" else "0"
            ),
            if (enabled) "站点已启用" else "站点已停用"
        )

    // ── 危险写 ──

    /** 清空代理缓存目录。只丢缓存，源站还在，所以算低风险里最重的一档。 */
    suspend fun clearCache(siteName: String, engine: BtProxyEngine): String =
        say("clear_cache", engine, mapOf("site_name" to siteName), "缓存已清理")

    /**
     * 删掉一条反向代理站点：Nginx 配置、日志、面板数据库里的记录一起走。
     *
     * `id` 和 `site_name` 文档都标了必填，所以两个都带。另外两格 `remove_path`（是否连网站目录一起删）
     * 和 `reload`（是否重载 Nginx）一律不发：前者默认 0 保留目录，删错了人找不回来；后者默认 1，
     * 不重载配置就不生效，没有理由替人关掉。
     */
    suspend fun deleteProxy(id: String, siteName: String, engine: BtProxyEngine): String =
        say(
            "delete",
            engine,
            mapOf("id" to id, "site_name" to siteName),
            "删除指令已发给面板"
        )
}
