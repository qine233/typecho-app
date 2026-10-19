package noline.zhouzhou.data.repository

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.BuildConfig
import noline.zhouzhou.data.remote.GithubApi
import noline.zhouzhou.data.remote.GithubException
import noline.zhouzhou.data.remote.GithubMirror
import noline.zhouzhou.data.remote.Release
import noline.zhouzhou.data.remote.downloadChain
import noline.zhouzhou.data.remote.sha256Hex
import noline.zhouzhou.data.remote.viaMirror
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 一个可以装的版本。version 已从 tag 里剥掉 v 前缀。 */
data class UpdateInfo(
    val version: String,
    val title: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** GitHub API 给的 sha256（十六进制，小写）；老发布可能没有。 */
    val sha256Hex: String?,
    val pageUrl: String
)

/**
 * 下载进度。字节数一起带上，是因为通知栏那句「12.3 MB / 22.3 MB」比一个百分比有用得多
 * ——慢的时候人想知道的是还剩多少，不是还要等多久。
 */
data class DownloadProgress(val writtenBytes: Long, val totalBytes: Long) {
    /** 来源没报长度（分块传输）时为 0f，界面据此改画不确定进度。 */
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (writtenBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val hasTotal: Boolean get() = totalBytes > 0
}

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
            sha256Hex = asset.sha256Hex(),
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

/** 版本号要进文件名，所以只留安全字符；tag 上一般不会带别的，防的是接口给回来奇怪东西。 */
internal fun apkName(version: String): String =
    "update-" + version.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

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

    /**
     * 上一次下完、还躺在缓存里的那个包。按版本命名，所以旧版残留不会被当成新版交给安装器——
     * 装错包比装不上更糟。安装器被用户划走后靠它再拉一次，不必重新下。
     */
    fun cachedApk(version: String): File? = File(updateDir(), apkName(version)).takeIf { it.length() > 0 }

    private fun updateDir() = File(context.cacheDir, "updates").apply { mkdirs() }

    suspend fun check(): UpdateOutcome =
        resolveUpdate(api.latestRelease(), currentVersion, wantsDebug)

    /**
     * 下到缓存目录，边下边回调进度。返回交给系统安装器的文件。
     *
     * 调用方是前台服务，所以这里不再自己切线程之外的东西：整段都在 IO 上跑，
     * 一个来源没成（连不上、非 2xx、大小对不上、开头的 zip magic 都不是）就换下一个，
     * 因为镜像站是会挂的，而装错了包比装不上更糟。
     */
    suspend fun download(
        update: UpdateInfo,
        preferred: GithubMirror = GithubMirror.GhProxyCom,
        onProgress: (DownloadProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = updateDir()
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, apkName(update.version))

        var failure = "所有来源都没能下下这个包"
        for (mirror in downloadChain(preferred)) {
            val size = try {
                fetch(viaMirror(mirror, update.downloadUrl), target, update.sizeBytes, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "${mirror.label}：${e.message ?: "下载中断"}"
                continue
            }
            if (update.sizeBytes > 0 && size != update.sizeBytes) {
                failure = "${mirror.label} 拿到的包大小不对（$size / ${update.sizeBytes} 字节）"
                continue
            }
            if (!looksLikeApk(target)) {
                failure = "${mirror.label} 给的不是 apk"
                continue
            }
            // 自更新走第三方镜像，唯一的凭据是 GitHub 接口里那份 sha256（接口本身经 TLS 拿的）：
            // 比对上了才交给安装器，否则换下一个来源。
            val expected = update.sha256Hex
            if (expected != null && sha256HexOf(target) != expected) {
                failure = "${mirror.label} 的包校验和对不上，可能被动过或没下完"
                continue
            }
            return@withContext target
        }
        throw GithubException(failure)
    }

    /** 落盘 [target]，返回实际写出的字节数。 */
    private suspend fun fetch(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NLecho-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GithubException("下载没成：${response.code}")
            val body = response.body ?: throw GithubException("下载没成：响应是空的")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            var written = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(DownloadProgress(written, total))
                    }
                    output.flush()
                }
            }
        }
        return target.length()
    }

    private fun looksLikeApk(file: File): Boolean {
        val magic = ByteArray(4)
        file.inputStream().use { if (it.read(magic) != magic.size) return false }
        // apk 就是 zip，头部固定 PK\x03\x04；镜像站失效时常见的是回一段 HTML。
        return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
    }

    private fun sha256HexOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
