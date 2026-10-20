package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.BtDatabase
import noline.zhouzhou.data.model.BtDbBackup
import noline.zhouzhou.data.model.BtDbGrant
import noline.zhouzhou.data.model.BtDbInfo
import noline.zhouzhou.data.model.BtField
import noline.zhouzhou.data.model.BtGuardTask
import noline.zhouzhou.data.model.BtLoginGuard
import noline.zhouzhou.data.model.BtBinlogFile
import noline.zhouzhou.data.model.BtMysqlAccount
import noline.zhouzhou.data.model.BtMysqlServer
import noline.zhouzhou.data.model.BtMysqlTimeout
import noline.zhouzhou.data.model.BtServerOption
import noline.zhouzhou.data.model.btBinlogFiles
import noline.zhouzhou.data.model.btDbBackups
import noline.zhouzhou.data.model.btDbGrants
import noline.zhouzhou.data.model.btDbInfo
import noline.zhouzhou.data.model.btDatabases
import noline.zhouzhou.data.model.btGuardTask
import noline.zhouzhou.data.model.btLogBody
import noline.zhouzhou.data.model.btLoginGuard
import noline.zhouzhou.data.model.btMysqlAccounts
import noline.zhouzhou.data.model.btMysqlMemory
import noline.zhouzhou.data.model.btMysqlRuntime
import noline.zhouzhou.data.model.btMysqlServer
import noline.zhouzhou.data.model.btMysqlTimeout
import noline.zhouzhou.data.model.btNestedNames
import noline.zhouzhou.data.model.btServerOptions
import noline.zhouzhou.data.model.btTotal
import noline.zhouzhou.data.model.btUserHosts
import noline.zhouzhou.data.remote.BtDatabaseApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 数据库列表一页：行 + 面板报的总条数（没报就是 null）。 */
data class BtDatabasePage(val rows: List<BtDatabase>, val total: Int?)

/**
 * 一个库的详情页。
 *
 * `tableNames` 走的是 MySQL 原生 `SHOW TABLES`，`info.tables` 走面板那张表带大小和行数的清单——
 * 两边对不上是有可能的（有人在命令行里建的表），所以两个都留着，界面上分开写。
 * `backups` 是从全量备份里按路径挑出属于这个库的那些。
 */
data class BtDatabaseDetail(
    val info: BtDbInfo,
    val tableNames: List<String>,
    val grants: List<BtDbGrant>,
    val backups: List<BtDbBackup>,
    val failures: List<BtSourceFailure>
)

/**
 * MySQL 这一页的总览：一台服务器上能读到的运行时信息全在这。
 *
 * 七个来源并发取，一个挂了只少一块（这台面板没装 `connection_control` 插件，
 * 登录失败那一格实测就是空的，不该因此让整页没数据）。
 */
data class BtMysqlOverview(
    val server: BtMysqlServer,
    val installStatus: String,
    val memory: List<BtField>,
    val runtime: List<BtField>,
    val timeout: BtMysqlTimeout,
    val guard: BtGuardTask,
    val loginGuard: BtLoginGuard,
    val binlogs: List<BtBinlogFile>,
    val accounts: List<BtMysqlAccount>,
    val userHosts: List<String>,
    val servers: List<BtServerOption>,
    val failures: List<BtSourceFailure>
)

/** 两份日志：错误日志和慢查询日志。`errorPath` 是面板只给了路径、没给正文时的那条路径。 */
data class BtDbLogs(val error: String, val slow: String, val errorPath: String)

/**
 * 某一路只读来源失败了的记录：`label` 说清是哪一块没读到，
 * 界面上把它摆在对应位置而不是笼统一句「部分数据缺失」。
 */
data class BtSourceFailure(val label: String, val failure: BtFailure)

/**
 * 数据库模块的仓库层。
 *
 * 两条这一层特有的规矩：
 * 1. **关键词只能本地过滤**。`getData table=databases` 的 `search.value` 实测是摆设，
 *    带任何词都回全表，所以带词的时候一次把表扫完（上限 [ScanLimit] 行）再本地筛、本地分页；
 * 2. **界面上永远看不到密码**。这一层拿到的原始 JSON 里每行都带明文 `password`，
 *    翻成模型的过程把它整个丢掉，`raw` 也不往上传——网站的模型留 `raw` 是为了摊出面板还说了什么，
 *    这里摊出来就是泄密。
 */
class BtDatabaseRepository(private val panel: BtPanelRepository) {

    private suspend fun client(): BtDatabaseApi = panel.databaseClient()

    fun describeFailure(error: Throwable): BtFailure = panel.describeFailure(error)

    // ── 列表 ──

    suspend fun databases(keyword: String, page: Int = 1, limit: Int = PageLimit): Result<BtDatabasePage> =
        runCatching {
            val api = client()
            val word = keyword.trim()
            if (word.isBlank()) {
                val reply = api.databases(page, limit)
                BtDatabasePage(btDatabases(reply.root), btTotal(reply.root))
            } else {
                val reply = api.databases(1, ScanLimit)
                val all = btDatabases(reply.root).filter { it.matches(word) }
                val from = (page - 1) * limit
                BtDatabasePage(all.drop(from).take(limit), all.size)
            }
        }

    /** MySQL 原生库清单。系统库（information_schema、mysql、sys、performance_schema）也在这里。 */
    suspend fun nativeDatabases(sid: String): Result<List<String>> = runCatching {
        btNestedNames(client().nativeDatabases(sid))
    }

    // ── 详情 ──

    suspend fun detail(db: BtDatabase): Result<BtDatabaseDetail> = runCatching {
        val api = client()
        coroutineScope {
            val info = async { runCatching { btDbInfo(api.info(db.name)) } }
            val tables = async { runCatching { btNestedNames(api.tables(db.sid, db.name)) } }
            val grants = async { runCatching { btDbGrants(api.panelGrants(db.sid)) } }
            val backups = async { runCatching { btDbBackups(api.backups()) } }
            val failed = mutableListOf<BtSourceFailure>()
            BtDatabaseDetail(
                info = pick("大小与表", info, BtDbInfo(db.name, "", emptyList()), failed),
                tableNames = pick("表清单", tables, emptyList(), failed),
                // 授权口给的是这台服务器上所有用户能碰哪些库，取和本库同名那几条。
                grants = pick("授权范围", grants, emptyList(), failed)
                    .filter { it.database == db.name || it.database == "*" },
                backups = pick("备份文件", backups, emptyList(), failed)
                    .filter { it.path.contains("/${db.name}/") || it.name.startsWith(db.name) },
                failures = failed
            )
        }
    }

    // ── MySQL 总览 ──

    suspend fun overview(sid: String = LocalSid): Result<BtMysqlOverview> = runCatching {
        val api = client()
        coroutineScope {
            val server = async { runCatching { btMysqlServer(api.serverInfo()) } }
            val install = async { runCatching { api.installStatus() } }
            val memory = async { runCatching { btMysqlMemory(api.memoryConfig()) } }
            val runtime = async { runCatching { btMysqlRuntime(api.runStatus()) } }
            val timeout = async { runCatching { btMysqlTimeout(api.timeout()) } }
            val guard = async { runCatching { btGuardTask(api.guardTask()) } }
            val login = async { runCatching { btLoginGuard(api.loginFailed()) } }
            val binlogs = async { runCatching { btBinlogFiles(api.binlogs()) } }
            val accounts = async { runCatching { btMysqlAccounts(api.mysqlUsers(sid)) } }
            val hosts = async { runCatching { btUserHosts(api.userHosts(sid)) } }
            val servers = async { runCatching { btServerOptions(api.servers()) } }
            val failed = mutableListOf<BtSourceFailure>()
            suspend fun <T> pick(name: String, source: kotlinx.coroutines.Deferred<Result<T>>, fallback: T): T =
                source.await().getOrNull() ?: run {
                    val error = source.await().exceptionOrNull() ?: IllegalStateException("面板这一格没给数据")
                    failed += BtSourceFailure(name, describeFailure(error))
                    fallback
                }
            BtMysqlOverview(
                server = pick("服务器信息", server, BtMysqlServer("", "")),
                installStatus = pick("安装状态", install, ""),
                memory = pick("内存参数", memory, emptyList()),
                runtime = pick("运行计数", runtime, emptyList()),
                timeout = pick("超时设置", timeout, BtMysqlTimeout(null, null, null, null, null)),
                guard = pick("守护进程", guard, BtGuardTask(false, "", "", "")),
                loginGuard = pick("登录失败处理", login, BtLoginGuard(false, null, null, null)),
                binlogs = pick("Binlog", binlogs, emptyList()),
                accounts = pick("账户权限", accounts, emptyList()),
                userHosts = pick("账户清单", hosts, emptyList()),
                servers = pick("服务器清单", servers, emptyList()),
                failures = failed
            )
        }
    }

    /**
     * 两份日志。实测 `GetErrorLog` 这台直接回整份 `.err` 正文（能到几百 KB），
     * 而文档说它只回路径——两种都认：像路径就当路径，正文留空让界面写清楚。
     */
    suspend fun logs(): Result<BtDbLogs> = runCatching {
        val api = client()
        coroutineScope {
            val error = async { runCatching { btLogBody(api.errorLog()) } }
            val slow = async { runCatching { btLogBody(api.slowLogs()) } }
            val text = error.await().getOrNull().orEmpty()
            val looksLikePath = !text.contains('\n') && text.startsWith("/") && text.endsWith(".err")
            BtDbLogs(
                error = if (looksLikePath) "" else tail(text),
                slow = tail(slow.await().getOrNull().orEmpty()),
                errorPath = if (looksLikePath) text else ""
            )
        }
    }

    // ── 写 ──

    suspend fun addDatabase(
        sid: String,
        name: String,
        user: String,
        password: String,
        address: String,
        charset: String,
        remark: String
    ): Result<String> = runCatching {
        client().addDatabase(sid, name, user, password, address, charset, remark)
    }

    /** 删库。界面上这一条要亲手打「删除」，而且必须写清备份目录不会跟着走。 */
    suspend fun deleteDatabase(db: BtDatabase): Result<String> = runCatching {
        client().deleteDatabase(db.id, db.sid, db.name, db.user)
    }

    suspend fun backup(db: BtDatabase): Result<String> = runCatching {
        client().backup(db.id, db.sid)
    }

    suspend fun addMysqlUser(sid: String, username: String, password: String, address: String): Result<String> =
        runCatching { client().addMysqlUser(sid, username, password, address) }

    /** 删账户：`host` 从 `GeUserHostList` 那串 `user@host` 里拆出来，不能拿 `%` 猜。 */
    suspend fun deleteMysqlUser(sid: String, username: String, host: String): Result<String> = runCatching {
        client().deleteMysqlUser(sid, username, host)
    }

    suspend fun setTimeout(
        interactive: String,
        wait: String,
        expireDays: String,
        passwordLifetime: String
    ): Result<String> = runCatching {
        client().setTimeout(interactive, wait, expireDays, passwordLifetime)
    }

    suspend fun clearBinlogs(days: String): Result<String> = runCatching { client().clearBinlogs(days) }

    /**
     * 开守护进程。上面注释里那句实测结论在这里再讲一遍：`set_restart_task` **不看 `status`**，
     * 发一次就建一条每 5 分钟的 `[勿删]MySQL守护进程` 计划任务。
     */
    suspend fun enableGuard(): Result<String> = runCatching { client().enableGuard() }

    /** 关守护进程只能删任务，所以先把任务 id 读回来；读不到就不发，别拿空 id 去撞。 */
    suspend fun disableGuard(): Result<String> = runCatching {
        val api = client()
        val id = btGuardTask(api.guardTask()).taskId
        if (id.isBlank()) error("面板这条回包里没给任务编号，删不了那条守护任务；去面板的计划任务列表里删「[勿删]MySQL守护进程」。")
        api.deleteGuardTask(id)
    }

    /** `user@host` → 账号 + 主机；拆不开的话主机留空，让界面原样露出这一行。 */
    fun splitHost(entry: String): Pair<String, String> {
        val at = entry.lastIndexOf('@')
        return if (at <= 0) entry to "" else entry.substring(0, at) to entry.substring(at + 1)
    }

    /**
     * 等一路来源，失败就记进 `failed` 并回退到 `fallback`。
     *
     * 这一页的规矩是「谁挂了谁那一格空着，顺便说清是哪一格」，而不是整个详情打不开。
     */
    private suspend fun <T> pick(
        name: String,
        source: kotlinx.coroutines.Deferred<Result<T>>,
        fallback: T,
        failed: MutableList<BtSourceFailure>
    ): T = source.await().getOrNull() ?: run {
        val error = source.await().exceptionOrNull() ?: IllegalStateException("面板这一格没给数据")
        failed += BtSourceFailure(name, describeFailure(error))
        fallback
    }

    private fun BtDatabase.matches(keyword: String): Boolean =
        listOf(name, user, accept, remark, sid, engine)
            .any { it.contains(keyword, ignoreCase = true) }

    /** 日志只留尾部这些行：整份 `.err` 交上去会把这一页卡死。 */
    private fun tail(text: String, maxLines: Int = 300): String {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.size <= maxLines) return lines.joinToString("\n")
        return "（前面 ${lines.size - maxLines} 行没显示，只留最新 $maxLines 行）\n" +
            lines.takeLast(maxLines).joinToString("\n")
    }

    companion object {
        private const val PageLimit = 30

        /** 带关键词时本地扫的行数上限；这台正式环境只有几个库，面板的库数也不会靠这个口翻盘。 */
        private const val ScanLimit = 500

        /** `0` 就是面板里的「本地 MySQL」。远程实例的 sid 从 `GetCloudServer` 来。 */
        const val LocalSid = "0"
    }
}
