package noline.zhouzhou.data.repository

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import noline.zhouzhou.data.model.BtCronAutoBackup
import noline.zhouzhou.data.model.BtCronBatchOp
import noline.zhouzhou.data.model.BtCronLogInfo
import noline.zhouzhou.data.model.BtCronPickList
import noline.zhouzhou.data.model.BtCronRotateLog
import noline.zhouzhou.data.model.BtCronTask
import noline.zhouzhou.data.model.BtSiteType
import noline.zhouzhou.data.model.btCronAutoBackup
import noline.zhouzhou.data.model.btCronCategories
import noline.zhouzhou.data.model.btCronDatabases
import noline.zhouzhou.data.model.btCronLogInfo
import noline.zhouzhou.data.model.btCronPickList
import noline.zhouzhou.data.model.btCronRotateLog
import noline.zhouzhou.data.model.btCronStringList
import noline.zhouzhou.data.model.btCronTask
import noline.zhouzhou.data.model.btCronTasks
import noline.zhouzhou.data.model.editableBody
import noline.zhouzhou.data.remote.BtCronApi
import noline.zhouzhou.data.remote.BtException
import noline.zhouzhou.data.remote.BtIssue

/** 任务列表一页。面板这个口没有分页，所以三个计数是我们自己数出来的。 */
data class BtCronPage(
    val rows: List<BtCronTask>,
    val keyword: String,
    val enabledCount: Int,
    val pausedCount: Int,
    val unknownCount: Int
) {
    val filtered: Boolean get() = keyword.isNotBlank()
}

/** 一个任务的详情：那一行 + 日志正文 + 日志文件在服务器上的位置。 */
data class BtCronDetail(
    val task: BtCronTask,
    val logText: String,
    val logInfo: BtCronLogInfo,
    val failures: List<BtSourceFailure>
) {
    val hasLog: Boolean get() = logText.isNotBlank()
}

/**
 * 建/改表单要的六个来源，每个单独取：这一格读不到只是少一个下拉，不该让整个表单打不开；
 * 但也不糊成「没有可选」，读不到的一律进 [failures]，界面上写明是谁没读到。
 */
data class BtCronOptions(
    val categories: List<BtSiteType>,
    val sites: List<BtSiteType>,
    val databases: List<BtSiteType>,
    val tables: List<Pair<String, List<String>>>,
    val backupTargets: List<BtSiteType>,
    val users: List<String>,
    val failures: List<BtSourceFailure>
)

/** 面板级三块读数。都只是显示：那三条写口实测不敢做成按钮，理由见 [BtCronApi] 类注释。 */
data class BtCronSettings(
    val autoSite: BtCronAutoBackup?,
    val autoMysql: BtCronAutoBackup?,
    val rotate: BtCronRotateLog?,
    val failures: List<BtSourceFailure>
)

/**
 * 计划任务的仓库层。
 *
 * 这一层的全部小心都来自实测：
 * 1. **面板的写口不都讲道理。** `modify_crond` 把没发的格子抹平，`set_cron_status` 是翻转开关
 *    （发什么都不看，只把状态翻一面），`set_cron_status_all` 明明改了状态却逐条报「设置失败」。
 *    所以这里每个写操作**写完都重读列表**，界面上那句「现在是启用中」来自重读，不来自面板的措辞；
 * 2. **重读只认 `GetCrontab`。** `get_crond_find` 少了 `cycle`/`type_zh`/`user` 这几列，
 *    拿它核对会把面板自己算出来的东西看丢；
 * 3. **空日志不是故障。** 实测没日志时面板回 `status=false`「当前日志为空!」，
 *    [detail] 把它翻成空正文，只有真读不到才记进 failures。
 */
class BtCronRepository(private val panel: BtPanelRepository) {

    private suspend fun client(): BtCronApi = panel.cronClient()

    fun describeFailure(error: Throwable): BtFailure = panel.describeFailure(error)

    // ── 读 ──

    suspend fun tasks(keyword: String = ""): Result<BtCronPage> = runCatching {
        val rows = btCronTasks(client().tasks())
        val word = keyword.trim()
        BtCronPage(
            rows = if (word.isBlank()) rows else rows.filter { it.matches(word) },
            keyword = word,
            enabledCount = rows.count { it.enabled == true },
            pausedCount = rows.count { it.enabled == false },
            unknownCount = rows.count { it.enabled == null }
        )
    }

    /** 列表重读。写完之后都从这里取真相。 */
    private suspend fun rows(api: BtCronApi): List<BtCronTask> = btCronTasks(api.tasks())

    /**
     * 详情：列表那一行是权威，日志正文和日志路径各拿各的，谁读不到就在 [BtCronDetail.failures]
     * 里留一句，不拿空字符串冒充「没有日志」。
     */
    suspend fun detail(id: String): Result<BtCronDetail> = runCatching {
        val api = client()
        val task = rows(api).firstOrNull { it.id == id }
            ?: runCatching { btCronTask(api.find(id)) }.getOrNull()
            ?: throw BtException(BtIssue.Unknown, "列表和查询口都没有这条任务")
        coroutineScope {
            val log = async { runCatching { api.log(id) } }
            val path = async { runCatching { btCronLogInfo(api.logPath(id)) } }
            val failed = mutableListOf<BtSourceFailure>()
            val text = log.await().getOrElse { error ->
                if (error is BtException && error.message.orEmpty().contains("日志为空")) ""
                else {
                    failed += BtSourceFailure("执行日志", describeFailure(error))
                    ""
                }
            }
            val info = path.await().getOrElse {
                failed += BtSourceFailure("日志文件", describeFailure(it))
                BtCronLogInfo("", "")
            }
            BtCronDetail(task, text, info, failed)
        }
    }

    /** 表单要的六个来源，一次并发拉齐。 */
    suspend fun options(): Result<BtCronOptions> = runCatching {
        val api = client()
        coroutineScope {
            val failed = mutableListOf<BtSourceFailure>()

            suspend fun <T> picked(label: String, block: Deferred<Result<T>>, fallback: T): T =
                block.await().getOrElse {
                    failed += BtSourceFailure(label, describeFailure(it))
                    fallback
                }

            val categories = async { runCatching { btCronCategories(api.categories()) } }
            val siteList = async { runCatching { btCronPickList(api.pickList("sites")) } }
            val dbList = async { runCatching { btCronPickList(api.pickList("databases")) } }
            val tables = async { runCatching { btCronDatabases(api.databases()) } }
            val users = async { runCatching { btCronStringList(api.systemUsers()) } }

            val sites = picked("可选站点", siteList, BtCronPickList(emptyList(), emptyList()))
            val databases = picked("可选数据库", dbList, BtCronPickList(emptyList(), emptyList()))
            BtCronOptions(
                categories = picked("分类", categories, emptyList()),
                sites = sites.targets,
                databases = databases.targets,
                tables = picked("库表清单", tables, emptyList()),
                // 备份目标在 `GetDataList` 的 orderOpt 那一格，两边并起来，谁给全听谁的。
                backupTargets = (sites.backupTargets + databases.backupTargets).distinctBy { it.id },
                users = picked("系统用户", users, emptyList()),
                failures = failed
            )
        }
    }

    /** 面板级三块读数，只是显示，没有按钮。 */
    suspend fun settings(): Result<BtCronSettings> = runCatching {
        val api = client()
        coroutineScope {
            val failed = mutableListOf<BtSourceFailure>()
            val site = async { runCatching { btCronAutoBackup(api.autoBackup("site"), "site") } }
            val mysql = async { runCatching { btCronAutoBackup(api.autoBackup("mysql"), "mysql") } }
            val rotate = async { runCatching { btCronRotateLog(api.rotateLogConfig()) } }
            BtCronSettings(
                autoSite = site.await().getOrElse {
                    failed += BtSourceFailure("网站自动备份", describeFailure(it)); null
                },
                autoMysql = mysql.await().getOrElse {
                    failed += BtSourceFailure("数据库自动备份", describeFailure(it)); null
                },
                rotate = rotate.await().getOrElse {
                    failed += BtSourceFailure("日志切割", describeFailure(it)); null
                },
                failures = failed
            )
        }
    }

    // ── 写 ──
    //
    // 每条写完都要重读再回话：面板那句原文只当引子，状态以列表为准。

    /** 新建。除了面板那句「添加成功」，还要确认列表里真出现了同名任务。 */
    suspend fun add(
        name: String,
        cycle: String,
        where1: String,
        kind: String,
        script: String,
        target: String = "",
        urlAddress: String = "",
        hour: String = "",
        minute: String = "",
        keepBackups: String = "",
        backupTo: String = "",
        runAs: String = ""
    ): Result<String> = runCatching {
        val api = client()
        val said = api.add(
            name = name, cycle = cycle, where1 = where1, kind = kind, script = script,
            target = target, urlAddress = urlAddress, hour = hour, minute = minute,
            keepBackups = keepBackups, backupTo = backupTo, runAs = runAs
        )
        val title = name.trim()
        rows(api).firstOrNull { it.name == title }?.let {
            "$said：「${it.name}」${it.cycleText}，${it.statusText}"
        } ?: "$said，但列表里还没看到这条同名任务"
    }

    /**
     * 改。整行原样带回再覆盖改过的格子，写完拿重读到的那一行回报。
     * `name` 这一格发的是界面上那个名字（[BtCronTask.name] 已经是 `rname`），
     * 照抄 raw 里的旧 `name` 会把上一次改过的名悄悄盖回去。
     */
    suspend fun save(task: BtCronTask, overrides: Map<String, String>): Result<String> = runCatching {
        val api = client()
        val said = api.modify(task.id, task.editableBody() + overrides)
        val after = rows(api).firstOrNull { it.id == task.id }
            ?: return@runCatching "$said，但重读列表时没找到这条任务"
        val text = "已改为「${after.name}」${after.cycleText} · ${after.kindText}"
        if (after.backupText.isNotBlank()) "$text · ${after.backupText}" else text
    }

    /** 删任务，写完确认它从列表里消失了。 */
    suspend fun delete(task: BtCronTask): Result<String> = runCatching {
        val api = client()
        val said = api.delete(task.id)
        if (rows(api).none { it.id == task.id }) {
            "$said：「${task.name}」已不在列表里"
        } else {
            "$said，但重读列表它还在"
        }
    }

    /**
     * 启用 / 暂停。面板这条是**翻转开关**，所以先看读到的当前状态，只在和用户要的不一样时才按；
     * 按完再读一遍，回报以重读为准。状态读不出来（`enabled == null`）时干脆不按。
     */
    suspend fun setStatus(task: BtCronTask, enabled: Boolean): Result<String> = runCatching {
        val api = client()
        when {
            task.enabled == null ->
                "面板没给这条任务的当前状态，翻转开关不敢按——先去面板网页上看一眼"
            task.enabled == enabled ->
                "它已经是${if (enabled) "启用" else "暂停"}状态，这条开关没有再按"
            else -> {
                val said = api.toggleStatus(task.id)
                val after = rows(api).firstOrNull { it.id == task.id }
                when {
                    after == null -> "$said，但重读列表时没找到这条任务"
                    after.enabled == enabled -> "已${if (enabled) "启用" else "暂停"}「${after.name}」"
                    else -> "$said，可重读到的还是「${after.statusText}」，以列表为准"
                }
            }
        }
    }

    /** 批量启停 / 删除。面板逐条报的判决实测不可信，成败一律以重读到的状态为准。 */
    suspend fun batch(op: BtCronBatchOp, tasks: List<BtCronTask>): Result<String> = runCatching {
        val api = client()
        if (tasks.isEmpty()) return@runCatching "没选任务"
        val said = api.batch(op, tasks.map { it.id })
        val after = rows(api)
        val still = tasks.mapNotNull { row -> after.firstOrNull { it.id == row.id } }
        when (op) {
            BtCronBatchOp.Delete -> "$said：${tasks.size} 条里有 ${tasks.size - still.size} 条已不在列表里"
            BtCronBatchOp.Exec -> "$said：这一条实测真会跑一次，日志里能看见"
            BtCronBatchOp.Start, BtCronBatchOp.Stop -> {
                val want = op == BtCronBatchOp.Start
                val hit = still.count { it.enabled == want }
                "重读后有 $hit/${tasks.size} 条是「${op.label}」状态" +
                    if (hit == still.size) "" else "（面板逐条报的原话是「${said.take(60)}」，以列表为准）"
            }
        }
    }

    /**
     * 立即执行一次，走批量口的 `exec`（文档那条 `set_execute_script` 实测只回裸 `false`）。
     * 它**不看启停状态**：暂停中的任务也会真跑一次，界面上必须先说清这句。
     */
    suspend fun run(task: BtCronTask): Result<String> = runCatching {
        val api = client()
        "${api.run(task.id)}——「${task.name}」已交给面板跑过一次（暂停中的任务它也会跑）"
    }

    /** 清空日志，写完重读一次确认还剩多少。 */
    suspend fun clearLogs(task: BtCronTask): Result<String> = runCatching {
        val api = client()
        val said = api.clearLogs(task.id)
        val rest = runCatching { api.log(task.id) }.getOrNull().orEmpty()
        if (rest.isBlank()) "$said：「${task.name}」的日志已清空"
        else "$said，但重读时还有 ${rest.length} 个字符"
    }

    /** 新建分类。面板没有删分类的口，建完只能去网页端删。 */
    suspend fun addCategory(name: String): Result<String> = runCatching {
        val api = client()
        val said = api.addCategory(name)
        val title = name.trim()
        if (btCronCategories(api.categories()).any { it.name == title }) {
            "$said：分类「$title」已在列表里（面板没有删分类的接口，要删只能去网页端）"
        } else {
            "$said，但重读分类时没看到它"
        }
    }

    /** 归类。实测归不回默认分类，所以界面只把自定义分类列成可点。 */
    suspend fun setCategory(category: BtSiteType, tasks: List<BtCronTask>): Result<String> = runCatching {
        val api = client()
        when {
            tasks.isEmpty() -> "没选任务"
            category.id == "0" ->
                "面板不让归回默认分类（实测原话「不能设置为系统分类或者默认分类!」）"
            else -> {
                val said = api.setCategory(category.id, tasks.map { it.id })
                val after = rows(api)
                val hit = tasks.count { row ->
                    after.firstOrNull { it.id == row.id }?.categoryId == category.id
                }
                "$said：${tasks.size} 条里有 $hit 条已归到「${category.name}」"
            }
        }
    }

    private fun BtCronTask.matches(keyword: String): Boolean =
        listOf(name, createdAtName, target, script, urlAddress, kindText, cycleText, categoryName)
            .any { it.contains(keyword, ignoreCase = true) }
}
