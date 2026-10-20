package noline.zhouzhou.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import noline.zhouzhou.data.model.Bt301Status
import noline.zhouzhou.data.model.BtLimitNet
import noline.zhouzhou.data.model.BtProxyRule
import noline.zhouzhou.data.model.BtPwdProtection
import noline.zhouzhou.data.model.BtRedirectRule
import noline.zhouzhou.data.model.BtRewrite
import noline.zhouzhou.data.model.BtSecurityHeaders
import noline.zhouzhou.data.model.BtSite
import noline.zhouzhou.data.model.BtSiteDomain
import noline.zhouzhou.data.model.BtSiteLog
import noline.zhouzhou.data.model.BtSiteOptions
import noline.zhouzhou.data.model.BtSiteSecurity
import noline.zhouzhou.data.model.BtSiteSettings
import noline.zhouzhou.data.model.BtSslDetail
import noline.zhouzhou.data.model.bt301Status
import noline.zhouzhou.data.model.btAccessLogEnabled
import noline.zhouzhou.data.model.btDefaultSite
import noline.zhouzhou.data.model.btLimitNet
import noline.zhouzhou.data.model.btLogPath
import noline.zhouzhou.data.model.btPhpVersions
import noline.zhouzhou.data.model.btProxyRules
import noline.zhouzhou.data.model.btPwdProtection
import noline.zhouzhou.data.model.btRedirectRules
import noline.zhouzhou.data.model.btRewrite
import noline.zhouzhou.data.model.btRunPath
import noline.zhouzhou.data.model.btSecurityHeaders
import noline.zhouzhou.data.model.btSiteCreation
import noline.zhouzhou.data.model.btSiteDomains
import noline.zhouzhou.data.model.btSiteFtp
import noline.zhouzhou.data.model.btSiteIndex
import noline.zhouzhou.data.model.btSiteLogText
import noline.zhouzhou.data.model.btSitePhpVersion
import noline.zhouzhou.data.model.btSiteTypes
import noline.zhouzhou.data.model.btSites
import noline.zhouzhou.data.model.btSslDetail
import noline.zhouzhou.data.model.btTotal
import noline.zhouzhou.data.remote.BtSiteApi

/** 站点列表一页：行 + 面板自己报的总条数（没报就是 null，界面写「面板没给」而不是 0）。 */
data class BtSitePage(val rows: List<BtSite>, val total: Int?)

/** 日志页签一次给两段正文 + 日志目录。 */
data class BtSiteLogs(val access: BtSiteLog, val error: BtSiteLog, val path: String)

/**
 * 网站管理的仓库层：把 [BtSiteApi] 的原始 JSON 翻成读模型，把失败统一包成 Result。
 *
 * 和 [BtPanelRepository] 共用同一份配置、同一个签名客户端，也共用它的失败分诊——
 * 面板那句原文只该有一种翻法，两个页面各翻一遍就会说出两套说法。
 */
class BtSiteRepository(private val panel: BtPanelRepository) {

    /** 每次拿到的都是包在同一个签名客户端上的薄壳；改配置时那个客户端会跟着换。 */
    private suspend fun client(): BtSiteApi = panel.siteClient()

    fun describeFailure(error: Throwable): BtFailure = panel.describeFailure(error)

    // ── 列表 ──

    /** 站点列表。关键词交给面板的 `search.value`，它既认域名也认备注。 */
    suspend fun sites(keyword: String, page: Int = 1, limit: Int = 30): Result<BtSitePage> = runCatching {
        val reply = client().sites(page, limit, keyword)
        BtSitePage(btSites(reply.root), btTotal(reply.root))
    }

    /** 建站表单要的三个来源一次并发拉齐；谁失败谁那一格空着，不让整个表单打不开。 */
    suspend fun options(): Result<BtSiteOptions> = runCatching {
        val api = client()
        coroutineScope {
            val php = async { runCatching { btPhpVersions(api.phpVersions()) }.getOrDefault(emptyList()) }
            val types = async { runCatching { btSiteTypes(api.siteTypes()) }.getOrDefault(emptyList()) }
            val def = async { runCatching { btDefaultSite(api.defaultSite()) }.getOrDefault("") }
            BtSiteOptions(php.await(), types.await(), def.await())
        }
    }

    // ── 详情各页签 ──

    suspend fun domains(site: BtSite): Result<List<BtSiteDomain>> = runCatching {
        btSiteDomains(client().domains(site.id))
    }

    /** 「网站设置」这一页的六样并发取，一个接口挂了只少一行。 */
    suspend fun settings(site: BtSite): Result<BtSiteSettings> = runCatching {
        val api = client()
        coroutineScope {
            val current = async { runCatching { api.sitePhpVersion(site.name) }.getOrNull() }
            val versions = async { runCatching { api.phpVersions() }.getOrNull() }
            val runPath = async { api.runPath(site.id) }
            val index = async { api.index(site.id) }
            val binding = async { runCatching { api.dirBinding(site.id) }.getOrNull() }
            val ftp = async { runCatching { api.siteFtp(site.name, site.id) }.getOrNull() }
            BtSiteSettings(
                phpCurrent = btSitePhpVersion(current.await()),
                phpVersions = versions.await()?.let(::btPhpVersions).orEmpty(),
                runPath = btRunPath(runPath.await()),
                index = btSiteIndex(index.await()),
                binding = btRunPath(binding.await()),
                ftp = ftp.await()?.let(::btSiteFtp).orEmpty()
            )
        }
    }

    suspend fun rewrite(site: BtSite): Result<BtRewrite> = runCatching {
        btRewrite(client().rewrite(site.name))
    }

    suspend fun ssl(site: BtSite): Result<BtSslDetail> = runCatching {
        btSslDetail(client().ssl(site.name))
    }

    suspend fun proxies(site: BtSite): Result<List<BtProxyRule>> = runCatching {
        btProxyRules(client().proxies(site.name))
    }

    suspend fun redirects(site: BtSite): Result<List<BtRedirectRule>> = runCatching {
        btRedirectRules(client().redirects(site.name))
    }

    /**
     * 日志页签。实测错误日志单次 1.7MB、访问日志 190KB，
     * 整段交给 Compose 会把这一页卡死，所以只留尾部。
     */
    suspend fun logs(site: BtSite): Result<BtSiteLogs> = runCatching {
        val api = client()
        coroutineScope {
            val access = async { runCatching { btSiteLogText(api.accessLog(site.name)) }.getOrDefault("") }
            val error = async { runCatching { btSiteLogText(api.errorLog(site.name)) }.getOrDefault("") }
            val path = async { runCatching { btLogPath(api.logPath(site.name)) }.getOrDefault("") }
            BtSiteLogs(
                access = BtSiteLog(tail(access.await()), ""),
                error = BtSiteLog(tail(error.await()), ""),
                path = path.await()
            )
        }
    }

    /** 「安全」这一页：限流、目录密码、301、安全响应头。 */
    suspend fun security(site: BtSite): Result<BtSiteSecurity> = runCatching {
        val api = client()
        coroutineScope {
            val limit = async { runCatching { api.limitNet(site.id) }.getOrNull() }
            val pwd = async { runCatching { api.hasPwd(site.id) }.getOrNull() }
            val redirect301 = async { runCatching { api.status301(site.name) }.getOrNull() }
            val headers = async { runCatching { api.securityHeaders(site.name) }.getOrNull() }
            BtSiteSecurity(
                limit = limit.await()?.let(::btLimitNet) ?: BtLimitNet(null, null, null, false),
                password = pwd.await()?.let(::btPwdProtection) ?: BtPwdProtection(false, ""),
                redirect301 = redirect301.await()?.let(::bt301Status) ?: Bt301Status(false, "", "", ""),
                headers = headers.await()?.let(::btSecurityHeaders)
                    ?: BtSecurityHeaders(false, false, false, false, false)
            )
        }
    }

    /** 访问日志开没开：读目录级配置里的 `logs`；面板没给就回 null，界面写「面板没给」。 */
    suspend fun accessLogEnabled(site: BtSite): Result<Boolean?> = runCatching {
        btAccessLogEnabled(client().dirUserIni(site.id, site.path))
    }

    // ── 写 ──

    /**
     * 建站。根目录按面板自己的规矩拼 `/www/wwwroot/<域名>`。
     * 成功时面板不给 `msg`，只给 `{siteId,siteStatus,…}`，所以这句话由我们拼。
     */
    suspend fun addSite(
        domain: String,
        phpVersion: String,
        remark: String,
        typeId: String,
        withDatabase: Boolean,
        withFtp: Boolean
    ): Result<String> = runCatching {
        val root = client().addSite(
            domain = domain,
            path = "/www/wwwroot/$domain",
            phpVersion = phpVersion,
            remark = remark,
            typeId = typeId,
            withDatabase = withDatabase,
            withFtp = withFtp
        )
        btSiteCreation(root).summary
            .ifBlank { "建站指令已经发给面板，回列表确认站点在不在" }
    }

    /** 删站：站点配置、证书、日志一起清掉，界面上这一条要亲手打「删除」。 */
    suspend fun deleteSite(site: BtSite): Result<String> = runCatching {
        client().deleteSite(site.id, site.name)
    }

    suspend fun setRunning(site: BtSite, running: Boolean): Result<String> = runCatching {
        val api = client()
        if (running) api.startSite(site.id, site.name) else api.stopSite(site.id, site.name)
    }

    suspend fun setRemark(site: BtSite, remark: String): Result<String> = runCatching {
        client().setRemark(site.id, remark)
    }

    suspend fun setExpireDate(site: BtSite, date: String): Result<String> = runCatching {
        client().setExpireDate(site.id, date)
    }

    suspend fun backup(site: BtSite): Result<String> = runCatching { client().backup(site.id) }

    suspend fun addDomain(site: BtSite, domain: String): Result<String> = runCatching {
        client().addDomain(site.id, site.name, domain)
    }

    suspend fun setPhpVersion(site: BtSite, version: String): Result<String> = runCatching {
        client().setPhpVersion(site.name, version)
    }

    suspend fun setRunPath(site: BtSite, runPath: String): Result<String> = runCatching {
        client().setRunPath(site.id, runPath)
    }

    suspend fun setIndex(site: BtSite, files: List<String>): Result<String> = runCatching {
        client().setIndex(site.id, files)
    }

    /** 面板这个 action 是翻转，界面上写完必须把状态读回来，不能自己猜反。 */
    suspend fun toggleAccessLog(site: BtSite): Result<String> = runCatching {
        client().toggleAccessLog(site.id)
    }

    /** 证书和私钥只进签名后的表单体，这两个参数一路到网络层都不落日志。 */
    suspend fun deploySsl(site: BtSite, certificate: String, privateKey: String): Result<String> = runCatching {
        client().setSsl(site.name, certificate, privateKey)
    }

    suspend fun closeSsl(site: BtSite): Result<String> = runCatching { client().closeSsl(site.name) }

    suspend fun setForceHttps(site: BtSite, enabled: Boolean): Result<String> = runCatching {
        val api = client()
        if (enabled) api.enableForceHttps(site.name) else api.disableForceHttps(site.name)
    }

    suspend fun saveRedirect(
        site: BtSite,
        name: String,
        target: String,
        domains: List<String>,
        path: String,
        kind: String,
        httpStatus: String,
        holdPath: Boolean,
        existing: Boolean
    ): Result<String> = runCatching {
        val api = client()
        if (existing) {
            api.modifyRedirect(site.name, name, target, domains, path, kind, httpStatus, holdPath)
        } else {
            api.createRedirect(site.name, name, target, domains, path, kind, httpStatus, holdPath)
        }
    }

    suspend fun deleteRedirect(site: BtSite, name: String): Result<String> = runCatching {
        client().deleteRedirect(site.name, name)
    }

    suspend fun saveProxy(
        site: BtSite,
        name: String,
        directory: String,
        target: String,
        cache: Boolean,
        cacheMinutes: String,
        existing: Boolean
    ): Result<String> = runCatching {
        val api = client()
        if (existing) {
            api.modifyProxy(site.name, name, directory, target, cache, cacheMinutes)
        } else {
            api.createProxy(site.name, name, directory, target, cache, cacheMinutes)
        }
    }

    suspend fun deleteProxy(site: BtSite, name: String): Result<String> = runCatching {
        client().removeProxy(site.name, name)
    }

    /** 开限流：三个值都要大于 0，界面提前挡住 0（面板只会回一句「必需大于0」）。 */
    suspend fun setLimitNet(site: BtSite, perServer: Int, perIp: Int, rateKb: Int): Result<String> =
        runCatching { client().setLimitNet(site.id, perServer, perIp, rateKb) }

    suspend fun closeLimitNet(site: BtSite): Result<String> = runCatching { client().closeLimitNet(site.id) }

    /** 面板只回布尔不回用户名，所以「改密码」必须账号和密码一起重填。 */
    suspend fun setPasswordProtect(site: BtSite, username: String, password: String): Result<String> =
        runCatching { client().setHasPwd(site.id, username, password) }

    suspend fun closePasswordProtect(site: BtSite): Result<String> = runCatching { client().closeHasPwd(site.id) }

    /** 域名级 301：两个地址都得带协议，实测光给域名面板回「Url地址不正确!」。 */
    suspend fun set301(site: BtSite, from: String, to: String): Result<String> = runCatching {
        client().set301(site.name, from, to)
    }

    suspend fun setSecurityHeaders(site: BtSite, enabled: Map<String, Boolean>): Result<String> = runCatching {
        client().setSecurityHeaders(site.name, enabled)
    }

    /** 日志只留尾部这么多行：一屏读不完，全量给 Compose 只会把主线程压住。 */
    private fun tail(text: String, maxLines: Int = 400): String {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.size <= maxLines) return lines.joinToString("\n")
        return "（前面 ${lines.size - maxLines} 行没显示，只留最新 $maxLines 行）\n" +
            lines.takeLast(maxLines).joinToString("\n")
    }
}
