package noline.zhouzhou.data.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import noline.zhouzhou.TypechoApplication
import noline.zhouzhou.data.remote.GithubMirror
import noline.zhouzhou.data.repository.DownloadProgress
import noline.zhouzhou.data.repository.UpdateInfo

// 传一个 UpdateInfo 只能靠 intent 的 extras，所以字段一一对应地摊平；
// 放在文件级是因为读侧那个扩展函数在类外面，够不着类里的 private 常量。
private const val ACTION_CANCEL = "nline.zhouzhou.action.CANCEL_UPDATE"
private const val EXTRA_VERSION = "version"
private const val EXTRA_TITLE = "title"
private const val EXTRA_ASSET = "asset"
private const val EXTRA_URL = "url"
private const val EXTRA_SIZE = "size"
private const val EXTRA_SHA = "sha256"
private const val EXTRA_PAGE = "page"
private const val EXTRA_MIRROR = "mirror"

/**
 * 更新包的下载从 ViewModel 挪到这里，为的是「切后台别断」。
 *
 * 为什么不用 WorkManager 的快速任务：那条路有约十分钟的硬额度，而这个 apk 二十来 MB，
 * 走直连实测只有 27 KB/s，一轮跑不完就被系统掐掉。前台服务没有这种时限，代价是它得自己
 * 挂一条常驻通知——这条通知正好就是用户要的进度条。
 *
 * 服务起在下载这一步之前，所以人就算把 App 划掉，下载也归 system_server 管着的这个进程跑。
 */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var runningVersion: String? = null

    private val container get() = (application as TypechoApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // 用户亲手取消的，不是失败：清状态、收通知，别再补一条「没下成」。
            job?.cancel()
            job = null
            runningVersion = null
            container.updateDownload.set(null)
            UpdateNotifier.hide(this, UpdateNotifier.PROGRESS_ID)
            UpdateNotifier.hide(this, UpdateNotifier.RESULT_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        val update = intent?.toUpdateInfo()
        if (update == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mirror = GithubMirror.fromId(intent.getStringExtra(EXTRA_MIRROR))
        // 重新下就把上一轮那条「已下好」收掉，不然它会和新的进度条并排挂着。
        UpdateNotifier.hide(this, UpdateNotifier.RESULT_ID)

        // 系统只给五分钟把前台通知挂上，所以这一步必须同步做完再开协程。
        val current = container.updateDownload.phase.value
        val opening = if (current is UpdatePhase.Downloading && current.version == update.version) {
            current
        } else {
            UpdatePhase.Downloading(update, DownloadProgress(0L, update.sizeBytes))
        }
        startForeground(UpdateNotifier.PROGRESS_ID, UpdateNotifier.progress(this, opening))

        if (job?.isActive == true && runningVersion == update.version) return START_NOT_STICKY
        job?.cancel()
        runningVersion = update.version
        job = scope.launch { download(update, mirror) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun download(update: UpdateInfo, mirror: GithubMirror) {
        val state = container.updateDownload
        var lastPercent = -2
        state.set(UpdatePhase.Downloading(update, DownloadProgress(0L, update.sizeBytes)))
        try {
            container.updateRepository.download(update, mirror) { progress ->
                val phase = UpdatePhase.Downloading(update, progress)
                state.set(phase)
                // 每 64 KB 就回调一次，通知跟着这个频率重发会被系统限流，也只在意整数百分比。
                val percent = updatePercent(progress)
                if (percent != lastPercent) {
                    lastPercent = percent
                    UpdateNotifier.show(
                        this,
                        UpdateNotifier.PROGRESS_ID,
                        UpdateNotifier.progress(this, phase)
                    )
                }
            }
            val ready = UpdatePhase.Ready(update)
            state.set(ready)
            UpdateNotifier.show(this, UpdateNotifier.RESULT_ID, UpdateNotifier.ready(this, ready))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = UpdatePhase.Failed(update, e.message ?: "下载中断")
            state.set(failed)
            UpdateNotifier.show(this, UpdateNotifier.RESULT_ID, UpdateNotifier.failed(this, failed))
        }
        // 结果那条是另一个 id，所以这里 Remove 掉的只有进度条；stopSelf 后它还在。
        runningVersion = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        fun start(context: Context, update: UpdateInfo, mirror: GithubMirror) {
            val intent = Intent(context, UpdateDownloadService::class.java)
                .putExtra(EXTRA_VERSION, update.version)
                .putExtra(EXTRA_TITLE, update.title)
                .putExtra(EXTRA_ASSET, update.assetName)
                .putExtra(EXTRA_URL, update.downloadUrl)
                .putExtra(EXTRA_SIZE, update.sizeBytes)
                .putExtra(EXTRA_PAGE, update.pageUrl)
                .putExtra(EXTRA_MIRROR, mirror.id)
            // 接口没给 digest 时这个 extra 干脆不放，读出来就是 null，和空串不是一回事。
            update.sha256Hex?.let { intent.putExtra(EXTRA_SHA, it) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}

private fun Intent.toUpdateInfo(): UpdateInfo? {
    val version = getStringExtra(EXTRA_VERSION) ?: return null
    val url = getStringExtra(EXTRA_URL) ?: return null
    return UpdateInfo(
        version = version,
        title = getStringExtra(EXTRA_TITLE).orEmpty(),
        assetName = getStringExtra(EXTRA_ASSET).orEmpty(),
        downloadUrl = url,
        sizeBytes = getLongExtra(EXTRA_SIZE, 0L),
        sha256Hex = getStringExtra(EXTRA_SHA),
        pageUrl = getStringExtra(EXTRA_PAGE).orEmpty()
    )
}
