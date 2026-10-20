package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtProxyRule
import noline.zhouzhou.data.model.BtRedirectRule
import noline.zhouzhou.data.model.BtRewrite
import noline.zhouzhou.data.model.BtSite
import noline.zhouzhou.data.model.BtSiteDomain
import noline.zhouzhou.data.model.BtSiteSecurity
import noline.zhouzhou.data.model.BtSiteSettings
import noline.zhouzhou.data.model.BtSiteSsl
import noline.zhouzhou.data.model.BtSslDetail
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.data.repository.BtSiteLogs

/**
 * 站点详情的页签，顺序照面板网站设置那一排。
 * `Rewrite` 是只读的一格：面板没有可用的「套模板」action（见 `BtSiteApi.rewrite` 的说明）。
 */
enum class BtSiteTab(val label: String) {
    Domains("域名"),
    Settings("网站设置"),
    Rewrite("伪静态"),
    Ssl("SSL"),
    Redirect("重定向"),
    Proxy("反向代理"),
    Log("日志"),
    Security("安全"),
    Backup("备份")
}

/**
 * 每个页签一份可空的数据位：null 是「这一页还没读到」，空列表是「面板说它是空的」，
 * 两者在界面上文案不同——后者才敢说「这个站没配反向代理」。
 */
data class BtSiteDetailUiState(
    val site: BtSite,
    val tab: BtSiteTab = BtSiteTab.Domains,
    val busy: Boolean = false,
    val failure: BtFailure? = null,
    val note: String = "",
    val domains: List<BtSiteDomain>? = null,
    val settings: BtSiteSettings? = null,
    val rewrite: BtRewrite? = null,
    val ssl: BtSslDetail? = null,
    val redirects: List<BtRedirectRule>? = null,
    val proxies: List<BtProxyRule>? = null,
    val logs: BtSiteLogs? = null,
    val accessLogEnabled: Boolean? = null,
    val security: BtSiteSecurity? = null
)

/**
 * 重定向/反代/301 表单的校验。面板的规矩是「名称 3~40 字节 + 目标带协议 + 路径以 / 开头」，
 * 名字撞了会盖掉同名的另一条规则，所以发出去之前先挡住。
 */
object BtSiteRuleForm {
    fun ruleNameError(name: String): String? = when {
        name.isBlank() -> "要给这条规则起个名字"
        name.trim().length < 3 -> "名字太短，面板要求 3 个字符以上"
        name.trim().length > 40 -> "名字太长，面板只收 40 个字节以内"
        else -> null
    }

    fun urlError(url: String): String? = when {
        url.isBlank() -> "目标地址是空的"
        !url.startsWith("http://") && !url.startsWith("https://") -> "地址要带 http:// 或 https:// 前缀"
        else -> null
    }

    fun pathError(path: String): String? =
        if (path.isBlank() || path.startsWith("/")) null else "路径要以 / 开头，比如 /api"
}

class BtSiteDetailViewModel(
    container: AppContainer,
    siteId: String,
    siteName: String,
    sitePath: String
) : ViewModel() {

    private val repository = container.btSiteRepository
    private var writeJob: Job? = null

    private val _uiState = MutableStateFlow(
        BtSiteDetailUiState(
            // 路由只带得到编号、域名和根目录；状态、证书剩余天数那些等站点表刷新回来再补。
            site = BtSite(
                id = siteId,
                name = siteName,
                path = sitePath,
                remark = "",
                addTime = "",
                expireDate = "",
                typeLabel = "",
                phpVersion = "",
                running = true,
                stopReason = "",
                domainCount = null,
                backupCount = null,
                hasProxy = false,
                hasRedirect = false,
                ssl = BtSiteSsl(false, "", "", null),
                raw = JsonObject(emptyMap())
            )
        )
    )
    val uiState: StateFlow<BtSiteDetailUiState> = _uiState.asStateFlow()

    init {
        refreshHeader()
        load(_uiState.value.tab)
    }

    fun selectTab(tab: BtSiteTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update { it.copy(tab = tab) }
        load(tab)
    }

    fun refresh() {
        refreshHeader()
        load(_uiState.value.tab)
    }

    /** 表头那行「在跑 / 已停用 · 证书剩几天」来自站点表本身，详情页这几个接口给不了。 */
    private fun refreshHeader() {
        viewModelScope.launch {
            val site = _uiState.value.site
            val rows = repository.sites(keyword = site.name).getOrNull()?.rows ?: return@launch
            val row = rows.firstOrNull { it.id == site.id } ?: rows.firstOrNull { it.name == site.name }
            if (row != null) _uiState.update { it.copy(site = row) }
        }
    }

    private fun load(tab: BtSiteTab) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val site = _uiState.value.site
            fun fail(error: Throwable?) = error?.let(repository::describeFailure)

            // 接口在这里就发掉，回来那一坨只是「往 state 上怎么贴」；
            // 读失败时保留上一次的结果，别让整页刷成空白。
            val domains = repository.domains(site).takeIf { tab == BtSiteTab.Domains }
            val settings = repository.settings(site).takeIf { tab == BtSiteTab.Settings }
            val rewrite = repository.rewrite(site).takeIf { tab == BtSiteTab.Rewrite }
            val ssl = repository.ssl(site).takeIf { tab == BtSiteTab.Ssl }
            val redirects = repository.redirects(site).takeIf { tab == BtSiteTab.Redirect }
            val proxies = repository.proxies(site).takeIf { tab == BtSiteTab.Proxy }
            val logs = repository.logs(site).takeIf { tab == BtSiteTab.Log }
            val accessLogEnabled = if (tab == BtSiteTab.Log) repository.accessLogEnabled(site) else null
            val security = repository.security(site).takeIf { tab == BtSiteTab.Security }

            val failure = when (tab) {
                BtSiteTab.Domains -> fail(domains?.exceptionOrNull())
                BtSiteTab.Settings -> fail(settings?.exceptionOrNull())
                BtSiteTab.Rewrite -> fail(rewrite?.exceptionOrNull())
                BtSiteTab.Ssl -> fail(ssl?.exceptionOrNull())
                BtSiteTab.Redirect -> fail(redirects?.exceptionOrNull())
                BtSiteTab.Proxy -> fail(proxies?.exceptionOrNull())
                BtSiteTab.Log -> fail(logs?.exceptionOrNull())
                BtSiteTab.Security -> fail(security?.exceptionOrNull())
                BtSiteTab.Backup -> null
            }
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    failure = failure,
                    domains = domains?.getOrNull() ?: state.domains,
                    settings = settings?.getOrNull() ?: state.settings,
                    rewrite = rewrite?.getOrNull() ?: state.rewrite,
                    ssl = ssl?.getOrNull() ?: state.ssl,
                    redirects = redirects?.getOrNull() ?: state.redirects,
                    proxies = proxies?.getOrNull() ?: state.proxies,
                    logs = logs?.getOrNull() ?: state.logs,
                    accessLogEnabled = accessLogEnabled?.getOrNull() ?: state.accessLogEnabled,
                    security = security?.getOrNull() ?: state.security
                )
            }
        }
    }

    /**
     * 写操作串行；`affectsList` 的动作（改备注、改到期、切 PHP）会把站点表那行重取一次，
     * 免得表头还挂着改之前的旧值。
     */
    private fun write(
        label: String,
        affectsList: Boolean = false,
        block: suspend () -> Result<String>
    ) {
        if (writeJob?.isActive == true) return
        writeJob = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, note = "") }
            val result = block()
            _uiState.update {
                it.copy(
                    busy = false,
                    note = result.getOrNull()?.let { text -> "$label：$text" }.orEmpty(),
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
            if (result.isSuccess) {
                if (affectsList) refreshHeader()
                load(_uiState.value.tab)
            }
        }
    }

    // ── 各页签的动作 ──

    fun addDomain(domain: String) {
        val error = BtNewSiteForm.domainError(domain)
        if (error != null) {
            _uiState.update { it.copy(failure = BtFailure("域名这格先修一下", error, "面板加域名也只吃裸主机名")) }
            return
        }
        write("加域名 $domain") { repository.addDomain(_uiState.value.site, BtNewSiteForm.cleanDomain(domain)) }
    }

    fun setPhpVersion(version: String) =
        write("切换 PHP", affectsList = true) { repository.setPhpVersion(_uiState.value.site, version) }

    fun setRunPath(runPath: String) =
        write("改运行目录") { repository.setRunPath(_uiState.value.site, runPath) }

    fun setIndex(files: List<String>) =
        write("改默认文档") { repository.setIndex(_uiState.value.site, files) }

    fun deploySsl(certificate: String, privateKey: String) {
        if (certificate.isBlank() || privateKey.isBlank()) {
            _uiState.update {
                it.copy(failure = BtFailure("两格都要填", "证书（PEM）和私钥（PEM）少一个都不算部署", "面板那边是两半一起验的"))
            }
            return
        }
        write("部署证书") { repository.deploySsl(_uiState.value.site, certificate, privateKey) }
    }

    fun closeSsl() = write("关闭 SSL") { repository.closeSsl(_uiState.value.site) }

    fun setForceHttps(enabled: Boolean) =
        write(if (enabled) "开启强制 HTTPS" else "关闭强制 HTTPS") {
            repository.setForceHttps(_uiState.value.site, enabled)
        }

    fun saveRedirect(
        name: String,
        target: String,
        domain: String,
        path: String,
        kind: String,
        httpStatus: String,
        holdPath: Boolean,
        existing: Boolean
    ) {
        val problem = BtSiteRuleForm.ruleNameError(name) ?: BtSiteRuleForm.urlError(target)
            ?: BtSiteRuleForm.pathError(path)
            ?: domain.takeIf { it.isBlank() }?.let { "来源域名不能空着，面板会匹配不到任何请求" }
        if (problem != null) {
            _uiState.update {
                it.copy(failure = BtFailure("这条重定向先修一下", problem, "改完再发，免得发出去一条没人命中的规则"))
            }
            return
        }
        write(if (existing) "改重定向" else "建重定向") {
            repository.saveRedirect(
                site = _uiState.value.site,
                name = name.trim(),
                target = target.trim(),
                domains = domain.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() },
                path = path.trim().ifBlank { "/" },
                kind = kind,
                httpStatus = httpStatus,
                holdPath = holdPath,
                existing = existing
            )
        }
    }

    fun deleteRedirect(name: String) =
        write("删重定向 $name") { repository.deleteRedirect(_uiState.value.site, name) }

    fun saveProxy(
        name: String,
        directory: String,
        target: String,
        cache: Boolean,
        cacheMinutes: String,
        existing: Boolean
    ) {
        val problem = BtSiteRuleForm.ruleNameError(name) ?: BtSiteRuleForm.urlError(target)
            ?: BtSiteRuleForm.pathError(directory)
        if (problem != null) {
            _uiState.update { it.copy(failure = BtFailure("这条反向代理先修一下", problem, "转发路径要以 / 开头，后端地址要带协议")) }
            return
        }
        write(if (existing) "改反向代理" else "建反向代理") {
            repository.saveProxy(
                site = _uiState.value.site,
                name = name.trim(),
                directory = directory.trim(),
                target = target.trim(),
                cache = cache,
                cacheMinutes = cacheMinutes.ifBlank { "0" },
                existing = existing
            )
        }
    }

    fun deleteProxy(name: String) =
        write("删反向代理 $name") { repository.deleteProxy(_uiState.value.site, name) }

    /** 面板这个 action 是翻转，所以发完必须把 `GetDirUserINI` 再读一次，界面上不自己猜反。 */
    fun toggleAccessLog() = write("切换访问日志") { repository.toggleAccessLog(_uiState.value.site) }

    fun setLimitNet(perServer: Int, perIp: Int, rateKb: Int) {
        if (perServer <= 0 || perIp <= 0 || rateKb <= 0) {
            _uiState.update {
                it.copy(
                    failure = BtFailure(
                        "三个值都要大于 0",
                        "面板对 SetLimitNet 的原话是「并发限制，IP限制，流量限制必需大于0」",
                        "要关掉限流请用旁边那颗「关闭限流」，它走的是另一个接口"
                    )
                )
            }
            return
        }
        write("开启限流") { repository.setLimitNet(_uiState.value.site, perServer, perIp, rateKb) }
    }

    fun closeLimitNet() = write("关闭限流") { repository.closeLimitNet(_uiState.value.site) }

    fun setPasswordProtect(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.update {
                it.copy(
                    failure = BtFailure(
                        "账号和密码都要填",
                        "面板只回布尔不回用户名，改密码只能整对重填",
                        "用户名就是访问这个站时要输的那个账号"
                    )
                )
            }
            return
        }
        if (password.length > 8) {
            _uiState.update {
                it.copy(failure = BtFailure("密码太长", "面板那边限制密码不超过 8 位", "换个 8 位以内的，超了面板会直接拒绝"))
            }
            return
        }
        write("设置目录密码") { repository.setPasswordProtect(_uiState.value.site, username, password) }
    }

    fun closePasswordProtect() = write("关闭目录密码") { repository.closePasswordProtect(_uiState.value.site) }

    fun setSecurityHeaders(enabled: Map<String, Boolean>) =
        write("保存安全响应头") { repository.setSecurityHeaders(_uiState.value.site, enabled) }

    fun set301(from: String, to: String) {
        val problem = BtSiteRuleForm.urlError(from) ?: BtSiteRuleForm.urlError(to)
        if (problem != null) {
            _uiState.update {
                it.copy(
                    failure = BtFailure(
                        "301 两个地址都要写全",
                        problem,
                        "实测：只给域名面板回「Url地址不正确!」，得带 http:// 前缀"
                    )
                )
            }
            return
        }
        write("设置 301") { repository.set301(_uiState.value.site, from.trim(), to.trim()) }
    }

    fun backup() = write("立即备份", affectsList = true) { repository.backup(_uiState.value.site) }

    fun setExpireDate(date: String) =
        write("改到期时间", affectsList = true) { repository.setExpireDate(_uiState.value.site, date) }

    fun setRemark(remark: String) =
        write("改备注", affectsList = true) { repository.setRemark(_uiState.value.site, remark) }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
