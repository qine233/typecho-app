package noline.zhouzhou.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知上「立即补交」的落点。PendingIntent 只能发给组件，所以留这么薄一层。 */
class StatusActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StatusNotifier.ACTION_FLUSH) return
        StatusSync.requestOnce(context.applicationContext, forced = true)
    }
}
