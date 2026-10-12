package noline.zhouzhou.data.remote

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

@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = ""
)

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

    /** 最新正式版；草稿和预发布 GitHub 自己会排除。 */
    suspend fun latestRelease(): Release {
        val raw = transport.get(
            "https://api.github.com/repos/$ReleaseRepoOwner/$ReleaseRepoName/releases/latest"
        )
        when {
            raw.status == 404 -> throw GithubException("这个仓库还没有发布过版本")
            raw.status == 403 || raw.status == 429 ->
                throw GithubException("GitHub 接口限流了，过一小时再试")
            raw.status !in 200..299 -> throw GithubException("GitHub 返回了 ${raw.status}")
        }
        return runCatching { json.decodeFromString(Release.serializer(), raw.body) }
            .getOrElse { throw GithubException("发布信息读不懂，可能接口改了字段") }
    }
}
