package noline.zhouzhou.data.repository

import android.content.Context
import noline.zhouzhou.data.model.BillBudget
import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillsOverview
import noline.zhouzhou.data.model.BillMonthReport
import noline.zhouzhou.data.model.BillRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

enum class BillOp { Add, Update, Delete }

/**
 * 记账的待提交操作。
 *
 * 和健康队列最大的差别：体重是「按天整体覆盖」，重放安全；记账的 POST **永远新增**，
 * 所以一条待提交只能提交成功一次，而且必须把服务器回的商品 id 认领回来——
 * 没有 id 的本地记录改不了也删不了（APP-API-SPEC §4.10、§7.8）。
 *
 * @param uid 本地标识，从录入那一刻就有，服务器不知道它的存在
 * @param serverId 服务器给的 `20261014-003`；Add 成功后由 ids[] 填回来
 */
@Serializable
data class PendingBill(
    val uid: String,
    val serverId: String = "",
    val op: BillOp = BillOp.Add,
    /** Delete 时为 null。 */
    val input: BillInput? = null,
    val queuedAt: Long = 0L
)

@Serializable
data class BillQueue(val ops: List<PendingBill> = emptyList())

/** 品名候选：买菜翻来覆去就那些东西，选中时顺带填上次的分类和店铺。 */
@Serializable
data class TitleHint(
    val title: String = "",
    val kind: String = "other",
    val shop: String = ""
)

/**
 * 记账的本地缓存：概览原样存一份离线可看，待提交队列单独存一份，
 * 另外攒一份品名候选（接口没有「常用商品」端点，只能 App 自己记），
 * 月报按月份攒最近 [MaxReports] 份、预算存本机。
 */
class BillsLocalStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val overviewFile: File get() = File(context.filesDir, "bills_overview.json")
    private val queueFile: File get() = File(context.filesDir, "bills_queue.json")
    private val hintsFile: File get() = File(context.filesDir, "bills_hints.json")
    private val budgetFile: File get() = File(context.filesDir, "bills_budget.json")
    private val reportsFile: File get() = File(context.filesDir, "bills_reports.json")

    private val hintsSerializer = MapSerializer(String.serializer(), TitleHint.serializer())
    private val reportsSerializer = MapSerializer(String.serializer(), BillMonthReport.serializer())

    suspend fun loadOverview(): BillsOverview? = withContext(Dispatchers.IO) {
        runCatching {
            if (!overviewFile.exists()) return@runCatching null
            json.decodeFromString(BillsOverview.serializer(), overviewFile.readText())
        }.getOrNull()
    }

    suspend fun saveOverview(overview: BillsOverview) = withContext(Dispatchers.IO) {
        runCatching {
            writeAtomically(overviewFile, json.encodeToString(BillsOverview.serializer(), overview))
            learnFrom(listOf(overview.recent) + overview.byDate.map { it.items })
        }
    }

    suspend fun loadQueue(): BillQueue = withContext(Dispatchers.IO) {
        runCatching {
            if (!queueFile.exists()) return@runCatching BillQueue()
            json.decodeFromString(BillQueue.serializer(), queueFile.readText())
        }.getOrDefault(BillQueue())
    }

    suspend fun saveQueue(queue: BillQueue) = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(queueFile, json.encodeToString(BillQueue.serializer(), queue)) }
    }

    suspend fun hints(): List<TitleHint> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hintsFile.exists()) return@runCatching emptyList()
            json.decodeFromString(hintsSerializer, hintsFile.readText()).values.sortedBy { it.title }
        }.getOrDefault(emptyList())
    }

    /** 明细拉回来也拿来学品名，候选比概览里那 120 条全。 */
    suspend fun learn(records: List<BillRecord>) = withContext(Dispatchers.IO) {
        learnFrom(listOf(records))
    }

    private suspend fun learnFrom(groups: List<List<BillRecord>>) {
        val current = runCatching {
            if (!hintsFile.exists()) emptyMap()
            else json.decodeFromString(hintsSerializer, hintsFile.readText())
        }.getOrDefault(emptyMap())

        val next = current.toMutableMap()
        groups.flatten().forEach { row ->
            val title = row.title.trim()
            if (title.isEmpty() || title.length > 60) return@forEach
            next[title] = TitleHint(title, row.kind, row.shop)
        }
        // 只攒这些条，菜名再多也有限，别让它无限膨胀；按品名排序，超出就丢后面的。
        val trimmed = if (next.size <= MaxHints) next
        else next.entries.sortedBy { it.key }.take(MaxHints).associate { it.key to it.value }
        runCatching { writeAtomically(hintsFile, json.encodeToString(hintsSerializer, trimmed)) }
    }

    /**
     * 入队。同一个目标只留最新一条：
     * 同 uid 的重复新增会被新的取代；同一 id 改了又改同理；「改了又删」留删除。
     */
    suspend fun enqueue(op: PendingBill): BillQueue {
        val current = loadQueue()
        val kept = current.ops.filterNot { existing ->
            if (op.op == BillOp.Delete && existing.serverId.isNotBlank() &&
                existing.serverId == op.serverId
            ) true
            else existing.uid == op.uid || (op.serverId.isNotBlank() && existing.serverId == op.serverId)
        }
        val next = BillQueue(ops = (kept + op).sortedBy { it.queuedAt })
        saveQueue(next)
        return next
    }

    /** 一条新增还没上传就被用户删掉：直接从队列里抹掉，不用碰服务器。 */
    suspend fun dropUnsent(uid: String): BillQueue {
        val current = loadQueue()
        val next = BillQueue(ops = current.ops.filterNot { it.uid == uid && it.serverId.isBlank() })
        saveQueue(next)
        return next
    }

    /** POST 回来的 ids 按序认领回本地条目。 */
    suspend fun attachIds(uidToId: Map<String, String>): BillQueue {
        val current = loadQueue()
        val next = BillQueue(
            ops = current.ops.map { op ->
                uidToId[op.uid]?.let { op.copy(serverId = it) } ?: op
            }
        )
        saveQueue(next)
        return next
    }

    suspend fun clearOps(uids: Set<String>): BillQueue {
        val current = loadQueue()
        val next = BillQueue(ops = current.ops.filterNot { it.uid in uids })
        saveQueue(next)
        return next
    }

    suspend fun loadBudget(): BillBudget = withContext(Dispatchers.IO) {
        runCatching {
            if (!budgetFile.exists()) return@runCatching BillBudget()
            json.decodeFromString(BillBudget.serializer(), budgetFile.readText())
        }.getOrDefault(BillBudget())
    }

    suspend fun saveBudget(budget: BillBudget) = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(budgetFile, json.encodeToString(BillBudget.serializer(), budget)) }
    }

    suspend fun loadReport(month: String): BillMonthReport? = withContext(Dispatchers.IO) {
        runCatching { reports()[month] }.getOrNull()
    }

    /** 月报能整月重算，但它是最容易「点开是空的」的东西，存下来离线也能翻。 */
    suspend fun saveReport(report: BillMonthReport) = withContext(Dispatchers.IO) {
        runCatching {
            val current = runCatching {
                if (!reportsFile.exists()) emptyMap()
                else json.decodeFromString(reportsSerializer, reportsFile.readText())
            }.getOrDefault(emptyMap())
            val merged = (current + (report.month to report)).toList()
                // 月份是 YYYY-MM，字符串序就是时间序；只留最近这些份，别越攒越多。
                .sortedBy { it.first }
                .takeLast(MaxReports)
                .toMap()
            writeAtomically(reportsFile, json.encodeToString(reportsSerializer, merged))
        }
    }

    private suspend fun reports(): Map<String, BillMonthReport> = withContext(Dispatchers.IO) {
        runCatching {
            if (!reportsFile.exists()) emptyMap()
            else json.decodeFromString(reportsSerializer, reportsFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    companion object {
        private const val MaxHints = 200
        private const val MaxReports = 12
    }
}
