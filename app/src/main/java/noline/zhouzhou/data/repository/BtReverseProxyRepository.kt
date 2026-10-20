package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.BtProxyEngine
import noline.zhouzhou.data.model.BtReverseProxyConf
import noline.zhouzhou.data.model.BtReverseProxyDomain
import noline.zhouzhou.data.model.BtReverseProxyEntry
import noline.zhouzhou.data.model.BtReverseProxyForm
import noline.zhouzhou.data.model.BtReverseProxyLog
import noline.zhouzhou.data.model.BtReverseProxyRule
import noline.zhouzhou.data.model.btReverseProxyConf
import noline.zhouzhou.data.model.btReverseProxyDomains
import noline.zhouzhou.data.model.btReverseProxyEntries
import noline.zhouzhou.data.model.btReverseProxyLog
import noline.zhouzhou.data.model.btReverseProxyRules
import noline.zhouzhou.data.model.btTotal
import noline.zhouzhou.data.remote.BtProxyApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** 代理项目列表一页。总数只存在于 `data.page` 那串分页 HTML 里，所以可能数不出来。 */
data class BtProxyPage(val rows: List<BtReverseProxyEntry>, val total: Int?)

/**
 * 一个代理站点的详情：规则、全局设置、域名三块一起来。
 *
 * 三块是三个接口，任何一块挂了另外两块照样要摊出来，所以带着 [BtProxyDetail.failures] 而不是
 * 整页报错——这一页最常见的情况就是「规则读到了，全局设置那口这个版本没有」。
 */
data class BtProxyDetail(
    val rules: List<BtReverseProxyRule>,
    val conf: BtReverseProxyConf,
    val domains: List<BtReverseProxyDomain>,
    val failures: List<BtSourceFailure>
) {
    val isEmpty: Boolean get() = rules.isEmpty() && conf.raw.isEmpty() && domains.isEmpty()
}

/**
 * 反向代理的仓库层：把面板的 JSON 翻成模型，并且在**发出去之前**把能拦的表单错误拦掉。
 *
 * 拦表单不是锦上添花：面板那边一次失败的调用也算一次调用，攒够 20 次签名或调用失败会把
 * 整个 API 锁一小时（见 `bt-panel-api-map`）。所以 [create] / [addRule] 先看
 * [BtReverseProxyForm.problem]，不通过就一句本地话返回，一个字节都不发。
 *
 * 这一层没有做的一件事是「改一条代理的转发目标」：文档里根本没有这个接口，面板端是靠
 * 加一条新的、删掉旧的拼出来的。要真做成一步，得先把「旧的删失败、新的已经加上」这个
 * 半截状态想清楚，所以这一版不提供，界面上也不假装能改。
 */
class BtReverseProxyRepository(private val panel: BtPanelRepository) {

    private suspend fun client(): BtProxyApi = panel.proxyClient()

    fun describeFailure(error: Throwable): BtFailure = panel.describeFailure(error)

    /** 当前选的 web 服务器；没选过按 Nginx 起手（宝塔装反代绝大多数就是 nginx）。 */
    suspend fun engine(): BtProxyEngine = BtProxyEngine.from(panel.proxyEngineFlow.first())

    suspend fun saveEngine(engine: BtProxyEngine) = panel.saveProxyEngine(engine.wire)

    /**
     * `engine` 传 null 时现读设置里那一份。界面每次操作都从设置里取，不用一路传参；
     * 只有「这条写要用另一个引擎试」的时候才显式给。
     */
    private suspend fun engineOr(engine: BtProxyEngine?): BtProxyEngine = engine ?: this.engine()

    suspend fun entries(
        keyword: String,
        page: Int = 1,
        limit: Int = PageLimit,
        engine: BtProxyEngine? = null
    ): Result<BtProxyPage> = runCatching {
        val reply = client().entries(engineOr(engine), page, limit, keyword.trim())
        BtProxyPage(btReverseProxyEntries(reply.root), btTotal(reply.root))
    }

    suspend fun detail(entry: BtReverseProxyEntry, engine: BtProxyEngine? = null): Result<BtProxyDetail> =
        runCatching {
            val api = client()
            val which = engineOr(engine)
            coroutineScope {
                val rules = async { runCatching { btReverseProxyRules(api.rules(entry.name, which)) } }
                val conf = async { runCatching { btReverseProxyConf(api.globalConf(entry.name, which)) } }
                val domains = async {
                    runCatching { btReverseProxyDomains(api.domains(entry.id, entry.name, which)) }
                }
                val failed = mutableListOf<BtSourceFailure>()
                val ruleRows = rules.await().getOrElse {
                    failed += BtSourceFailure("代理规则", describeFailure(it))
                    emptyList()
                }
                val confRows = conf.await().getOrElse {
                    failed += BtSourceFailure("全局设置", describeFailure(it))
                    BtReverseProxyConf.Empty
                }
                val domainRows = domains.await().getOrElse {
                    failed += BtSourceFailure("域名", describeFailure(it))
                    emptyList()
                }
                BtProxyDetail(ruleRows, confRows, domainRows, failed)
            }
        }

    suspend fun logs(entry: BtReverseProxyEntry, engine: BtProxyEngine? = null): Result<BtReverseProxyLog> =
        runCatching { btReverseProxyLog(client().siteLogs(entry.name, engineOr(engine))) }

    // ── 写 ──

    suspend fun create(form: BtReverseProxyForm, engine: BtProxyEngine? = null): Result<String> {
        form.problem()?.let { return Result.failure(IllegalStateException(it)) }
        return runCatching {
            client().createProxy(
                engine = engineOr(engine),
                domains = form.domains.trim(),
                proxyPass = form.proxyPass.trim(),
                proxyPath = form.proxyPath.trim(),
                proxyHost = form.proxyHost.trim(),
                proxyType = form.proxyType,
                remark = form.remark.trim()
            )
        }
    }

    suspend fun addRule(
        siteName: String,
        form: BtReverseProxyForm,
        engine: BtProxyEngine? = null
    ): Result<String> {
        form.problem()?.let { return Result.failure(IllegalStateException(it)) }
        return runCatching {
            client().addRule(
                engine = engineOr(engine),
                siteName = siteName,
                proxyPath = form.proxyPath.trim(),
                proxyPass = form.proxyPass.trim(),
                proxyHost = form.proxyHost.trim(),
                proxyType = form.proxyType,
                remark = form.remark.trim()
            )
        }
    }

    suspend fun setRemark(entry: BtReverseProxyEntry, remark: String, engine: BtProxyEngine? = null): Result<String> =
        runCatching { client().setRemark(entry.name, entry.id, remark, engineOr(engine)) }

    /** 整站停用/启用走的是 `set_site_stop`，它要的是站点 id，不是代理条目 id。 */
    suspend fun setEnabled(
        entry: BtReverseProxyEntry,
        enabled: Boolean,
        engine: BtProxyEngine? = null
    ): Result<String> = runCatching { client().setSiteStop(entry.name, entry.id, enabled, engineOr(engine)) }

    suspend fun setCache(
        entry: BtReverseProxyEntry,
        enabled: Boolean,
        expires: String,
        engine: BtProxyEngine? = null
    ): Result<String> = runCatching {
        client().setCache(entry.name, enabled, expires.trim(), engineOr(engine))
    }

    suspend fun setGzip(
        entry: BtReverseProxyEntry,
        enabled: Boolean,
        minLength: String,
        compLevel: String,
        types: String,
        engine: BtProxyEngine? = null
    ): Result<String> = runCatching {
        client().setGzip(entry.name, enabled, minLength.trim(), compLevel.trim(), types.trim(), engineOr(engine))
    }

    suspend fun setWebsocket(
        entry: BtReverseProxyEntry,
        enabled: Boolean,
        engine: BtProxyEngine? = null
    ): Result<String> = runCatching { client().setWebsocket(entry.name, enabled, engineOr(engine)) }

    /**
     * 日志去向。`file` 和 `rsyslog` 必须带路径，这一条是文档写明的条件必填，
     * 所以也拦在发出去之前。
     */
    suspend fun setLog(
        entry: BtReverseProxyEntry,
        logType: String,
        logPath: String,
        engine: BtProxyEngine? = null
    ): Result<String> {
        if ((logType == "file" || logType == "rsyslog") && logPath.isBlank()) {
            return Result.failure(IllegalStateException("选了「写到文件」或「rsyslog」，路径不能空着"))
        }
        return runCatching { client().setLog(entry.name, logType, logPath.trim(), engineOr(engine)) }
    }

    suspend fun clearCache(entry: BtReverseProxyEntry, engine: BtProxyEngine? = null): Result<String> =
        runCatching { client().clearCache(entry.name, engineOr(engine)) }

    suspend fun delete(entry: BtReverseProxyEntry, engine: BtProxyEngine? = null): Result<String> =
        runCatching { client().deleteProxy(entry.id, entry.name, engineOr(engine)) }

    companion object {
        private const val PageLimit = 30
    }
}
