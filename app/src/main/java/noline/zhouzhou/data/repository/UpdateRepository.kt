package noline.zhouzhou.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.BuildConfig
import noline.zhouzhou.data.remote.GithubApi
import noline.zhouzhou.data.remote.GithubException
import noline.zhouzhou.data.remote.Release
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 一个可以装的版本。version 已从 tag 里剥掉 v 前缀。 */
data class UpdateInfo(
    val version: String,
    val title: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val pageUrl: String
)

sealed interface UpdateOutcome {
    data object UpToDate : UpdateOutcome
    data class Available(val update: UpdateInfo) : UpdateOutcome
    /** 有新版本，但挂的包不是本机这个渠道（debug 包装不进正式签名，反之亦然）。 */
    data object NoMatchingBuild : UpdateOutcome
}

/**
 * 纯函数，单测直接喂 Release 对象。渠道不匹配的 apk 一律不认——
 * 签名和包名都对不上，下下来也装不进本机这份。
 */
internal fun resolveUpdate(
    release: Release,
    currentVersion: String,
    wantsDebug: Boolean
): UpdateOutcome {
    val version = release.tagName.trim().removePrefix("v").removePrefix("V")
    if (version.isBlank() || compareVersions(version, currentVersion) <= 0) {
        return UpdateOutcome.UpToDate
    }
    val asset = release.assets
        .filter { it.name.endsWith(".apk", ignoreCase = true) }
        .firstOrNull { it.name.contains("debug", ignoreCase = true) == wantsDebug }
        ?: return UpdateOutcome.NoMatchingBuild
    return UpdateOutcome.Available(
        UpdateInfo(
            version = version,
            title = release.name.ifBlank { release.tagName },
            assetName = asset.name,
            downloadUrl = asset.downloadUrl,
            sizeBytes = asset.size,
            pageUrl = release.htmlUrl
        )
    )
}

/** 按点分数字段比大小，缺的段当 0；tag 上的 v 前缀和 1.0.1-rc1 这类尾巴都能吃下。 */
internal fun compareVersions(left: String, right: String): Int {
    val l = versionSegments(left)
    val r = versionSegments(right)
    for (i in 0 until maxOf(l.size, r.size)) {
        val a = l.getOrElse(i) { 0L }
        val b = r.getOrElse(i) { 0L }
        if (a != b) return if (a > b) 1 else -1
    }
    return 0
}

private fun versionSegments(value: String): List<Long> {
    // 只取开头的数字点段：1.0.1-rc1 里的 rc 后面那个 1 不是第四段版本号。
    val head = Regex("^\\d+(\\.\\d+)*").find(value.trim().trimStart('v', 'V')) ?: return emptyList()
    return head.value.split('.').mapNotNull { it.toLongOrNull() }
}

/**
 * 自更新：只认 GitHub Releases 上按渠道命名的那个 apk。
 * CI 目前只出 debug 包，所以正式签名的包上线之前，这条链只对 debug 渠道有效。
 */
class UpdateRepository(
    private val context: Context,
    private val api: GithubApi = GithubApi(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    val currentVersion: String = BuildConfig.VERSION_NAME
    private val wantsDebug: Boolean = BuildConfig.DEBUG

    suspend fun check(): UpdateOutcome =
        resolveUpdate(api.latestRelease(), currentVersion, wantsDebug)

    /** 下到缓存目录，边下边回调进度（0f..1f）。返回交给系统安装器的文件。 */
    suspend fun download(update: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update.apk")

            val request = Request.Builder()
                .url(update.downloadUrl)
                .header("User-Agent", "NLecho-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw GithubException("下载没成：${response.code}")
                val body = response.body ?: throw GithubException("下载没成：响应是空的")
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                var written = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                }
            }
            if (target.length() <= 0L) throw GithubException("下载下来是个空文件")
            target
        }
}
