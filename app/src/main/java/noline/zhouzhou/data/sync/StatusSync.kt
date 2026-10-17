package noline.zhouzhou.data.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 排期都集中在这里。周期任务用 WorkManager 而不是自己的 Service：JobScheduler 把这条
 * 记录存在 system_server 里，App 进程被杀它照样会到点唤醒，这比我们自己保活可靠得多。
 * 代价是最短 15 分钟一次、Doze 期间还会被合并，所以通知上从不说「实时」。
 */
object StatusSync {

    private const val PERIODIC = "nline-status-periodic"
    private const val ONCE = "nline-status-once"
    const val KEY_FORCED = "forced"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** [forced] 是用户从通知上亲手点的，这时就算后台补交开关是关的也要跑一次。 */
    fun requestOnce(context: Context, forced: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<StatusWorker>()
            .setInputData(workDataOf(KEY_FORCED to forced))
            // 用户点了「立即补交」，排在下一个空闲窗口才跑就等于没响应。额度用尽时
            // 退回普通任务，宁可慢一点也不要抛异常。
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(ONCE)
    }
}
