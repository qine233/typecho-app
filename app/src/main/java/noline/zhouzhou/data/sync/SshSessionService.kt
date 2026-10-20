package noline.zhouzhou.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import noline.zhouzhou.MainActivity
import noline.zhouzhou.R
import noline.zhouzhou.TypechoApplication

private const val ACTION_STOP = "nline.zhouzhou.action.STOP_SSH"
private const val EXTRA_HOST = "host"
private const val EXTRA_PORT = "port"
private const val EXTRA_USER = "user"

/**
 * SSH 会话的前台服务。
 *
 * 为什么要它：实测 App 一退到后台，进程被系统冻结，socket 跟着被拆，二十秒左右会话就报
 * 「Software caused connection abort」；前台空转一分钟什么都不掉。也就是说「切后台不断」
 * 这件事光把会话挂在 App 级单例上是不够的——单例保的是不被页面重建带走，冻进程是另一回事。
 * 挂一条前台通知，进程就留在前台优先级上，那条 TCP 才真能一直开着。
 *
 * 类型选 `specialUse` 而不是 `dataSync`：这里没有「往设备传数据」这件事，是一段人开着
 * 的交互会话，Google 给 dataSync 的定义（下载/上传/同步）套不上。代价是要在清单里写明用途。
 */
class SshSessionService : Service() {

    /**
     * 这条前台通知现在代表着一段真实会话吗。
     * [SshRepository.close] 收尾时也会叫一次 stop，两边走的是同一个 ACTION_STOP；
     * 没有这个标记，那次回头调用会把服务再起一遍、再 close 一遍，来回没完。
     */
    @Volatile
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知上那颗「断开」：先真把会话收掉，再收通知，顺序反了会留一条点不开的僵尸。
            val session = holding
            holding = false
            if (session) (application as? TypechoApplication)?.container?.sshRepository?.close()
            stopEverything()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        if (host.isNullOrBlank()) {
            stopEverything()
            return START_NOT_STICKY
        }
        holding = true
        startForeground(NOTIFICATION_ID, notification(host, intent.getIntExtra(EXTRA_PORT, 22),
            intent.getStringExtra(EXTRA_USER).orEmpty()))
        return START_STICKY
    }

    override fun onDestroy() {
        holding = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(host: String, port: Int, user: String): Notification {
        val who = if (user.isBlank()) host else "$user@$host"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_status)
            .setContentTitle("SSH 会话 · $who:$port")
            .setContentText("会话保持中，点开回到终端")
            .setContentIntent(openTerminal(this))
            .addAction(0, "断开", disconnect(this))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "SSH 会话", NotificationManager.IMPORTANCE_LOW).apply {
            description = "终端连着服务器时保持这条会话，退到后台也不被系统掐掉"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "nline_ssh_session"
        const val NOTIFICATION_ID = 4210
        const val DEEP_LINK_SSH = "nline://ssh"

        fun start(context: Context, host: String, port: Int, user: String) {
            val intent = Intent(context, SshSessionService::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_USER, user)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 收场。走 `startService` 而不是 `startForegroundService`：这条只是来通知服务「你可以停了」，
         * 而服务这时多半还挂在前台上（进程因此算前台，起服务不受后台限制）；
         * 用 startForegroundService 反而逼着一个再也不会 startForeground 的实例起来。
         * 整个调用包一层：会话正在崩的收尾路上，一条通知收不掉不该再抛一次。
         */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, SshSessionService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        private fun openTerminal(context: Context) = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(DEEP_LINK_SSH))
                // NEW_TASK|CLEAR_TOP|SINGLE_TOP：不带 CLEAR_TOP 时每次点通知都会在任务里
                // 再压一个 MainActivity 进来（实测 sz 一路涨到 3），旧的还挡在下面。
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun disconnect(context: Context) = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, SshSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private const val REQUEST_OPEN = 61
        private const val REQUEST_STOP = 62
    }
}
