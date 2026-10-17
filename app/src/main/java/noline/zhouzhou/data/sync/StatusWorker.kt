package noline.zhouzhou.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import noline.zhouzhou.TypechoApplication

/**
 * 到点把队列交一交，然后把通知刷成最新条数。
 *
 * 失败一律算成功：交不上是「没网」这个事实，队列自己会留着，下次再来一遍；
 * 返回 retry 只会让系统拿更长的退避再等一轮，对一条状态通知没意义。
 */
class StatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TypechoApplication).container
        val forced = inputData.getBoolean(StatusSync.KEY_FORCED, false)
        runCatching { container.statusRepository.flushQueues(forced) }
        runCatching { container.statusRepository.publish() }
        return Result.success()
    }
}
