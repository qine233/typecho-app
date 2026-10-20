package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.BtDisk
import noline.zhouzhou.data.model.BtEndpoint
import noline.zhouzhou.data.model.BtField
import noline.zhouzhou.data.model.BtNetwork
import noline.zhouzhou.data.model.BtPanelConfig
import noline.zhouzhou.data.model.BtResultView
import noline.zhouzhou.data.model.BtRow
import noline.zhouzhou.data.model.BtSystemSnapshot
import noline.zhouzhou.data.model.BtTable
import noline.zhouzhou.data.model.btCounts
import noline.zhouzhou.data.model.btDisks
import noline.zhouzhou.data.model.btNetwork
import noline.zhouzhou.data.model.btResultView
import noline.zhouzhou.data.model.btSystemSnapshot
import noline.zhouzhou.data.remote.BtException
import noline.zhouzhou.data.remote.BtIssue
import noline.zhouzhou.data.remote.BtPanelApi
import noline.zhouzhou.data.remote.BtSiteApi
import noline.zhouzhou.data.remote.BtServiceOp
import noline.zhouzhou.data.remote.advice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 面板给的一张表的读数：行 + 面板自己报的总条数（没报就是 null）。 */
data class BtTablePage(
    val table: BtTable,
    val rows: List<BtRow>,
    val total: Int?
)

/** 通用调用台跑完一条接口留下的东西：面板那句话、脱敏后的回包、花了多久。 */
data class BtCallResult(
    val endpoint: BtEndpoint,
    val msg: String,
    val view: BtResultView,
    val tookMs: Long
)

/**
 * 看板。只有 systemSnapshot 是硬要求（它挂了整页就没意义），
 * 磁盘、网速、三张表的计数各拿各的，谁失败就少一行，不拿 0 顶上去。
 */
data class BtDashboard(
    val system: BtSystemSnapshot,
    val disks: List<BtDisk>,
    val network: BtNetwork?,
    val counts: List<BtField>,
    val fetchedAt: Long
)

/**
 * 宝塔面板仓库：把配置读出来拼成客户端，再把接口那边的「HTTP 200 + status:false」统一包成 Result。
 *
 * 界面层只该看见两种结果：数据，或者一句带原文的失败。
 */
class BtPanelRepository(
    private val settings: SettingsRepository,
    private val apiOf: (BtPanelConfig) -> BtPanelApi = { BtPanelApi(it) }
) {

    private var cachedConfig: BtPanelConfig? = null
    private var cachedApi: BtPanelApi? = null

    val configFlow: Flow<BtPanelConfig> get() = settings.btConfig

    suspend fun saveConfig(site: String, key: String, trustSelfSigned: Boolean) =
        settings.saveBtConfig(site, key.trim(), trustSelfSigned)

    suspend fun clearConfig() = settings.clearBtConfig()

    suspend fun config(): BtPanelConfig = settings.btConfig.first()

    /** 网站模块的有类型客户端：配置、签名、失败分诊都复用这一份。 */
    suspend fun siteClient(): BtSiteApi = BtSiteApi(api())

    /** 改地址、改密钥、改「信任自签」之后必须换掉客户端，否则旧设置会一直用到进程重启。 */
    private suspend fun api(): BtPanelApi {
        val config = config()
        if (!config.isComplete) {
            throw BtException(BtIssue.NoConfig, "还没有填面板地址和接口密钥")
        }
        cachedApi?.takeIf { cachedConfig == config }?.let { return it }
        cachedConfig = config
        return apiOf(config).also { cachedApi = it }
    }

    suspend fun dashboard(): Result<BtDashboard> = runCatching {
        val client = api()
        val totalRaw = client.systemTotal()
        // GetNetWork 一次给齐负载、内存、磁盘、站点计数，所以它单独失败不该让整页看板没数据。
        val netRaw = runCatching { client.networkInfo() }.getOrNull()
        BtDashboard(
            system = btSystemSnapshot(totalRaw, netRaw),
            disks = runCatching { btDisks(client.diskInfo()) }.getOrDefault(emptyList()),
            network = netRaw?.let(::btNetwork),
            counts = btCounts(totalRaw, netRaw),
            fetchedAt = System.currentTimeMillis()
        )
    }

    /** 计划任务那张表有两个来源：/crontab 的裸数组，或 /data 的 table=crontab。这里取前者，因为要拿 id 去执行。 */
    suspend fun load(table: BtTable, page: Int = 1, keyword: String = ""): Result<BtTablePage> = runCatching {
        val client = api()
        if (table == BtTable.Crontab) {
            val rows = client.crontab().filter { keyword.isBlank() || it.matches(keyword) }
            BtTablePage(table, rows, rows.size.takeIf { keyword.isBlank() })
        } else {
            val result = client.table(table, page = page, keyword = keyword)
            BtTablePage(table, result.rows, result.total)
        }
    }

    suspend fun service(name: String, op: BtServiceOp): Result<String> = runCatching {
        api().service(name, op)
    }

    suspend fun reloadPanel(): Result<String> = runCatching { api().reloadPanel() }

    suspend fun runTask(id: String): Result<String> = runCatching { api().runCrontab(id) }

    suspend fun taskLog(id: String): Result<String> = runCatching { api().crontabLog(id) }

    /**
     * 通用调用台的一条：把目录里那条接口原样发出去，回包先整棵脱敏、再决定摆不摆成表。
     *
     * 面板自己写的那句 `msg` 单独留着——多数写接口不返回数据，那一句就是唯一的结果凭据。
     */
    suspend fun call(endpoint: BtEndpoint, values: Map<String, String>): Result<BtCallResult> = runCatching {
        val started = System.currentTimeMillis()
        val reply = api().invoke(endpoint, values)
        BtCallResult(
            endpoint = endpoint,
            msg = reply.msg,
            view = btResultView(reply.root),
            tookMs = System.currentTimeMillis() - started
        )
    }

    /**
     * 「保存并自检」用的一条：把失败翻成「问题 + 面板原文 + 下一步」，
     * 没有真面板可测的时候，这三段就是用户唯一的线索。
     */
    fun describeFailure(error: Throwable): BtFailure = when (error) {
        is BtException -> BtFailure(
            title = when (error.issue) {
                BtIssue.NoConfig -> "还没配置"
                BtIssue.WrongKey -> "密钥不对"
                BtIssue.ClockSkew -> "签名过期"
                BtIssue.IpBlocked -> "IP 不在白名单"
                BtIssue.ApiOff -> "接口没开"
                BtIssue.HtmlPage -> "回的是网页"
                BtIssue.Network -> "连不上面板"
                BtIssue.Unknown -> "面板报错"
            },
            detail = error.message,
            advice = error.issue.advice,
            fromPanel = error.fromPanel
        )
        else -> BtFailure("请求没走通", error.message ?: "面板没有响应", BtIssue.Unknown.advice)
    }

    private fun BtRow.matches(keyword: String): Boolean =
        (title + subtitle + badge + fields.joinToString(" ") { it.label + it.value })
            .contains(keyword, ignoreCase = true)
}

/**
 * 自检失败时摊出来的三段：是什么、那句话、下一步做什么。
 * `fromPanel` 决定第二行敢不敢写「面板原话」——连不上、回 HTML 这类话是我们自己判断的，
 * 挂成面板说的就是假供词。
 */
data class BtFailure(
    val title: String,
    val detail: String,
    val advice: String,
    val fromPanel: Boolean = false
)
