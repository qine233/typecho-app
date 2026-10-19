package noline.zhouzhou.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

const val ReleaseRepoOwner = "qine233"
const val ReleaseRepoName = "typecho-app"

/** 仓库主页。「检测更新」问的就是这个仓库，关于页里的开源地址也用它，别分成两处写。 */
val ReleaseRepoUrl = "https://github.com/$ReleaseRepoOwner/$ReleaseRepoName"

@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    /** GitHub 给资产带的校验和，形如 `sha256:hex`；老仓库或第三方发布可能没有。 */
    val digest: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = ""
)

/** 只留十六进制部分，前缀不是 sha256 就当没有（没法比就不比）。 */
fun ReleaseAsset.sha256Hex(): String? {
    val parts = digest.trim().split(':', limit = 2)
    if (parts.size != 2) return null
    if (!parts[0].equals("sha256", ignoreCase = true)) return null
    return parts[1].lowercase().takeIf { it.isNotBlank() }
}

@Serializable
data class Release(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val assets: List<ReleaseAsset> = emptyList()
)

class GithubException(override val message: String) : Exception(message)

data class GithubRaw(val status: Int, val body: String)

/** 传输缝，单测里塞假的响应就能覆盖 404 / 限流 / 字段缺失。 */
fun interface GithubTransport {
    suspend fun get(url: String): GithubRaw
}

class OkHttpGithubTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : GithubTransport {

    override suspend fun get(url: String): GithubRaw = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // GitHub API 认 UA，缺了就 403
            .header("User-Agent", "NLecho-Android")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            GithubRaw(response.code, response.body?.string() ?: "")
        }
    }
}

class GithubApi(
    private val transport: GithubTransport = OkHttpGithubTransport()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * 最新正式版；草稿和预发布 GitHub 自己会排除。
     *
     * 逐来源试，因为镜像站的可能结果没有一种是「没更新」：只转文件的会把 api 请求挡成 403，
     * 挂了的给你个 200 的 HTML 错误页，所以解析失败也得往下一家走，最后再合并成人话。
     * 这里不看用户挑的更新来源：接口一次几百字节，直连实测 0.7 秒就回，
     * 而 apk 的 sha256 是从这份返回里读的，接口尽量信得过。
     */
    suspend fun latestRelease(): Release {
        val chain = apiChain()
        val url = "https://api.github.com/repos/$ReleaseRepoOwner/$ReleaseRepoName/releases/latest"
        var sawNotFound = false
        var sawRateLimit = false
        var sawGarbage = false
        var sawNetworkFailure = false
        for (mirror in chain) {
            val raw = try {
                transport.get(viaMirror(mirror, url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sawNetworkFailure = true
                continue
            }
            when (raw.status) {
                404 -> {
                    sawNotFound = true
                    continue
                }
                403, 429 -> {
                    sawRateLimit = true
                    continue
                }
            }
            if (raw.status !in 200..299) {
                sawGarbage = true
                continue
            }
            val release = runCatching { json.decodeFromString(Release.serializer(), raw.body) }.getOrNull()
            if (release != null && release.tagName.isNotBlank()) return release
            sawGarbage = true
        }
        throw GithubException(
            when {
                sawNotFound -> "这个仓库还没有发布过版本"
                sawRateLimit -> "GitHub 接口限流了，过一小时再试"
                sawGarbage -> "发布信息读不懂，试了 ${chain.size} 个来源都不像正常的版本数据"
                else -> "GitHub 和镜像站都没连上"
            }
        )
    }
}
