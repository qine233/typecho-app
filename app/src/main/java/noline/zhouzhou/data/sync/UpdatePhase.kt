package noline.zhouzhou.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import noline.zhouzhou.data.repository.DownloadProgress
import noline.zhouzhou.data.repository.UpdateInfo
import java.util.Locale

/**
 * 下载走到哪一步。前台服务写、设置页读，两边都不该持有对方，所以这份状态放在
 * 一个活得比 ViewModel 久的地方（AppContainer 里那个 holder）。
 *
 * 每一段都带上 [UpdateInfo]：进程可能被回收过，回来时设置页只认得版本号，
 * 没有 downloadUrl 就没法接着下，也没有大小和校验和可报。
 */
sealed interface UpdatePhase {
    val info: UpdateInfo
    val version: String get() = info.version

    data class Downloading(override val info: UpdateInfo, val progress: DownloadProgress) : UpdatePhase
    data class Ready(override val info: UpdateInfo) : UpdatePhase
    data class Failed(override val info: UpdateInfo, val message: String) : UpdatePhase
}

/** 服务和界面之间的那块共享白板。null 表示眼下没有进行中的下载。 */
class UpdateDownloadState {
    private val _phase = MutableStateFlow<UpdatePhase?>(null)
    val phase: StateFlow<UpdatePhase?> = _phase.asStateFlow()

    fun set(phase: UpdatePhase?) {
        _phase.value = phase
    }
}

// ── 通知栏那两行字。拆成纯函数是因为「该说什么」比「怎么发通知」更容易写错。 ──

fun updateNoticeTitle(version: String): String = "NLecho · 正在下载 $version"

/**
 * 慢的时候人想知道的是还剩多少，不是还要等多久，所以字节数排在百分比前面。
 * 来源没报总长度（分块传输）时不硬编一个百分比出来。
 */
fun updateNoticeText(progress: DownloadProgress): String = when {
    !progress.hasTotal -> "已下 ${formatBytes(progress.writtenBytes)}，这条来源没报总大小"
    else -> "${formatBytes(progress.writtenBytes)} / ${formatBytes(progress.totalBytes)}" +
        " · ${(progress.fraction * 100).toInt()}%"
}

fun updateReadyText(version: String): String = "$version 下好了，点开就能装"

fun updateFailedText(message: String): String = "更新包没下成：$message"

/** 通知只在整数百分比变了以后才重发一条；没报总长时返回 -1，让调用方按「不确定」处理。 */
fun updatePercent(progress: DownloadProgress): Int =
    if (progress.hasTotal) (progress.fraction * 100).toInt() else -1

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_000 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
