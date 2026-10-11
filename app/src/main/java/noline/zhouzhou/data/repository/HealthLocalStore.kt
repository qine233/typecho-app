package noline.zhouzhou.data.repository

import android.content.Context
import noline.zhouzhou.data.model.HealthOverview
import noline.zhouzhou.data.model.RecordInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 待提交操作。record 为 null 表示「删除这一天」。 */
@Serializable
data class PendingOp(
    val date: String,
    val record: RecordInput? = null,
    val queuedAt: Long = 0L
)

@Serializable
data class HealthQueue(val ops: List<PendingOp> = emptyList())

/**
 * 健康数据要能离线记：本地先落队列，联网后整批补交。
 * 接口按天整体覆盖，所以重放、乱序、重复提交都是安全的。
 */
class HealthLocalStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val cacheFile: File get() = File(context.filesDir, "health_overview.json")
    private val queueFile: File get() = File(context.filesDir, "health_queue.json")

    suspend fun loadOverview(): HealthOverview? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            json.decodeFromString(HealthOverview.serializer(), cacheFile.readText())
        }.getOrNull()
    }

    suspend fun saveOverview(overview: HealthOverview) = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(cacheFile, json.encodeToString(HealthOverview.serializer(), overview)) }
    }

    suspend fun loadQueue(): HealthQueue = withContext(Dispatchers.IO) {
        runCatching {
            if (!queueFile.exists()) return@runCatching HealthQueue()
            json.decodeFromString(HealthQueue.serializer(), queueFile.readText())
        }.getOrDefault(HealthQueue())
    }

    suspend fun saveQueue(queue: HealthQueue) = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(queueFile, json.encodeToString(HealthQueue.serializer(), queue)) }
    }

    /** 入队：同一天的旧操作直接被新的取代（取最新的那条完整对象）。 */
    suspend fun enqueue(op: PendingOp): HealthQueue {
        val current = loadQueue()
        val kept = current.ops.filterNot { it.date == op.date }
        val next = HealthQueue(ops = (kept + op).sortedBy { it.date })
        saveQueue(next)
        return next
    }

    suspend fun clearOps(dates: Set<String>): HealthQueue {
        val current = loadQueue()
        val next = HealthQueue(ops = current.ops.filterNot { it.date in dates })
        saveQueue(next)
        return next
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }
}
