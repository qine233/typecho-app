package noline.zhouzhou.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import noline.zhouzhou.MainActivity
import noline.zhouzhou.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻的那条工作状态通知。IMPORTANCE_LOW + setOngoing：它是要长期挂在那儿告诉用户
 * 「还有东西没交上去」的，不该响、不该被一不小心划掉，但可以在设置里整个关掉。
 */
object StatusNotifier {

    const val CHANNEL_ID = "nline_status"
    const val NOTIFICATION_ID = 4201
    const val ACTION_FLUSH = "noline.zhouzhou.action.FLUSH_PENDING"
    const val DEEP_LINK = "nline://status"
    const val DEEP_LINK_BILLS = "nline://bills"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "工作状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示还有多少记录没提交到站点"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun publish(context: Context, status: WorkStatus) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = build(context, status)
        // 没给 POST_NOTIFICATIONS 时 notify() 会直接抛 SecurityException，
        // 而这条路径是后台 Worker 走的，为一条通知崩掉整个进程不值。
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun build(context: Context, status: WorkStatus): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse(status.targetLink()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val flush = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, StatusActionReceiver::class.java).setAction(ACTION_FLUSH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val timeText = if (status.lastFlushAt > 0L) timeFormat.format(Date(status.lastFlushAt)) else ""
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_status)
            .setContentTitle("NLecho · ${status.headline()}")
            .setContentText(status.detail(timeText))
            .setStyle(NotificationCompat.BigTextStyle().bigText(status.detail(timeText)))
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_status, "立即补交", flush)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
