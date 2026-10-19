package noline.zhouzhou.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import noline.zhouzhou.MainActivity
import noline.zhouzhou.R

/**
 * 下载更新那两条通知。
 *
 * 进度那条挂在前台服务上、setSilent：用户已经知道自己点了「下载并安装」，不需要再响一声。
 * 结果那条换个 id 单独发，所以服务一 stopSelf 它不会跟着消失——慢下载下完的时候人多半已经
 * 不在 App 里了，那条通知是唯一的入口，所以要响。
 */
object UpdateNotifier {

    const val CHANNEL_ID = "nline_update"
    /** 进度那条是前台服务的凭据，服务一停系统就会把它收走。 */
    const val PROGRESS_ID = 4202
    /** 下完/失败那条换个号，所以服务停了它还在，人还能点开装。 */
    const val RESULT_ID = 4203
    const val DEEP_LINK_UPDATE = "nline://update"

    private const val REQUEST_OPEN = 43

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "更新下载",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "下载新版本 apk 的进度和装它的入口"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 没给 POST_NOTIFICATIONS 时 notify 会抛 SecurityException，一条进度通知不该把下载带崩。 */
    fun show(context: Context, id: Int, notification: Notification) {
        ensureChannel(context)
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun hide(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    fun progress(context: Context, phase: UpdatePhase.Downloading): Notification {
        val percent = updatePercent(phase.progress)
        return base(context, phase.version)
            .setContentText(updateNoticeText(phase.progress))
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 点它回到设置页，由页面再把包交给系统安装器。
     * 通知自己不去起安装器：那个意图是隐式的，而后台起 Activity 从 Android 10 起就被系统拦，
     * 静默失败比不响还糟。回到前台再走 App 里那条已经通的安装路径。
     */
    fun ready(context: Context, phase: UpdatePhase.Ready): Notification {
        val text = updateReadyText(phase.version)
        return base(context, phase.version)
            .setContentTitle("NLecho · 更新已下好")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, 100, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    fun failed(context: Context, phase: UpdatePhase.Failed): Notification {
        val text = updateFailedText(phase.message)
        return base(context, phase.version)
            .setContentTitle("NLecho · 更新没分成")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun base(context: Context, version: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_status)
        .setContentTitle(updateNoticeTitle(version))
        .setContentIntent(openSettings(context))
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)

    private fun openSettings(context: Context) = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(DEEP_LINK_UPDATE))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
