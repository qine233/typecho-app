package noline.zhouzhou.data.sync

import android.content.Context
import kotlinx.coroutines.flow.first
import noline.zhouzhou.data.repository.BillsLocalStore
import noline.zhouzhou.data.repository.BillsRepository
import noline.zhouzhou.data.repository.DraftStore
import noline.zhouzhou.data.repository.HealthLocalStore
import noline.zhouzhou.data.repository.HealthRepository
import noline.zhouzhou.data.repository.SettingsRepository
import noline.zhouzhou.data.repository.WriteOutcome

/**
 * 队列状态的唯一出口：读、后台补交、把结果写进通知。
 *
 * 这里只碰本机队列和已有的 Repository，不自己发请求，所以补交走的还是
 * HealthRepository/BillsRepository 那套幂等重放，不会出现「后台交一份、前台又交一份」。
 */
class StatusRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val healthStore: HealthLocalStore,
    private val billsStore: BillsLocalStore,
    private val draftStore: DraftStore,
    private val healthRepository: HealthRepository,
    private val billsRepository: BillsRepository
) {

    suspend fun snapshot(): WorkStatus {
        val (at, note) = settings.lastFlush.first()
        return WorkStatus(
            autoSyncOn = settings.autoSync.first(),
            pendingHealth = healthStore.loadQueue().ops.size,
            pendingBills = billsStore.loadQueue().ops.size,
            drafts = draftStore.listAll().size,
            lastFlushAt = at,
            lastFlushNote = note,
            // 预算超支是「这个月」的事，队列一有变动就得跟着重算，不能等到下一轮周期任务。
            budgetAlert = billsRepository.currentBudgetStatus().alert()
        )
    }

    /** 关掉开关时这条通知就该消失，所以两边都走这里，别让调用方自己判断。 */
    suspend fun publish() {
        if (!settings.persistentStatus.first()) {
            StatusNotifier.hide(context)
            return
        }
        StatusNotifier.publish(context, snapshot())
    }

    /**
     * 补交两条队列，返回给用户看的一句话。[forced] 为假时，后台补交开关关着就只报数不交。
     */
    suspend fun flushQueues(forced: Boolean = false): String {
        val before = snapshot()
        if (before.pendingTotal == 0) return ""
        if (!forced && !before.autoSyncOn) return ""

        val notes = mutableListOf<String>()
        if (before.pendingHealth > 0) notes += "健康" + describe(healthRepository.flushAndSync())
        if (before.pendingBills > 0) notes += "记账" + describe(billsRepository.flushAndSync())

        val after = snapshot()
        val text = notes.joinToString(" · ")
        if (text.isNotEmpty()) settings.recordFlush(System.currentTimeMillis(), text)
        // 交失败也要把最新条数说清楚，否则通知会停在补交前的数字上。
        return if (after.pendingTotal > 0 && after.pendingTotal < before.pendingTotal) {
            "$text，还剩 ${after.pendingTotal} 条"
        } else text
    }

    suspend fun setEnabled(on: Boolean) {
        settings.setPersistentStatus(on)
        if (on) {
            StatusNotifier.ensureChannel(context)
            StatusSync.schedule(context)
        } else {
            StatusSync.cancel(context)
        }
        publish()
    }

    suspend fun setAutoSync(on: Boolean) {
        settings.setAutoSync(on)
        publish()
    }

    /** 冷启动时对一遍账：开关开着就把周期任务补上（被系统清掉过也能恢复），通知跟着当前队列刷新。 */
    suspend fun resumeFromSettings() {
        if (settings.persistentStatus.first()) StatusSync.schedule(context)
        publish()
    }

    private fun describe(outcome: WriteOutcome): String = when (outcome) {
        is WriteOutcome.Saved -> "已交完"
        is WriteOutcome.Queued -> "还是没网，剩 ${outcome.pending} 条"
        // 被拒是站点那边的问题（只读、密钥错、接口关了），别在通知里编成网络问题。
        else -> "站点没收"
    }
}
