package noline.zhouzhou.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Releases 接口层。仓库是公开的，所以这里只测解析和状态码处置，
 * 不测鉴权——真跑一次网络的用例留给设置页里那个手动按钮。
 */
class GithubApiTest {

    private class Fake(private val responses: MutableMap<String, GithubRaw>) : GithubTransport {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String): GithubRaw {
            urls += url
            return responses.getValue(url)
        }
    }

    private val latestUrl = "https://api.github.com/repos/qine233/typecho-app/releases/latest"
    private val assetUrl = "https://github.com/qine233/typecho-app/releases/download/v1.0.1/a.apk"

    private val releaseBody = """
        {
          "tag_name": "v1.0.1",
          "name": "NLecho 1.0.1",
          "html_url": "https://github.com/qine233/typecho-app/releases/tag/v1.0.1",
          "published_at": "2026-10-12T09:00:00Z",
          "prerelease": false,
          "draft": false,
          "assets": [
            {"name": "NLecho-1.0.1-debug.apk", "size": 19512690,
             "browser_download_url": "https://objects.githubusercontent.com/a.apk",
             "content_type": "application/vnd.android.package-archive"},
            {"name": "sourcemap.zip", "size": 100, "browser_download_url": "https://x/s.zip"}
          ],
          "author": {"login": "qine233", "id": 1},
          "unexpected_nested": {"a": [1, 2, {"b": null}]}
        }
    """.trimIndent()

    @Test
    fun `reads the latest release and ignores unknown fields`() {
        val api = GithubApi(Fake(mutableMapOf(latestUrl to GithubRaw(200, releaseBody))))
        val release = runBlocking { api.latestRelease() }

        assertEquals("v1.0.1", release.tagName)
        assertEquals("NLecho 1.0.1", release.name)
        assertEquals(2, release.assets.size)
        assertEquals("NLecho-1.0.1-debug.apk", release.assets.first().name)
        assertEquals(19512690L, release.assets.first().size)
        assertEquals("https://objects.githubusercontent.com/a.apk", release.assets.first().downloadUrl)
    }

    @Test
    fun `missing release is not a crash`() {
        val api = GithubApi(Fake(mutableMapOf(latestUrl to GithubRaw(404, """{"message":"Not Found"}"""))))
        val message = runCatching { runBlocking { api.latestRelease() } }.exceptionOrNull()?.message
        assertEquals("这个仓库还没有发布过版本", message)
    }

    @Test
    fun `rate limit tells the user to wait`() {
        val api = GithubApi(Fake(mutableMapOf(latestUrl to GithubRaw(403, """{"message":"rate limited"}"""))))
        val message = runCatching { runBlocking { api.latestRelease() } }.exceptionOrNull()?.message
        assertEquals("GitHub 接口限流了，过一小时再试", message)
    }

    @Test
    fun `broken json degrades to a readable message`() {
        val api = GithubApi(Fake(mutableMapOf(latestUrl to GithubRaw(200, "<html>502</html>"))))
        val error = runCatching { runBlocking { api.latestRelease() } }.exceptionOrNull()
        assertTrue(error is GithubException)
        assertEquals("发布信息读不懂，试了 2 个来源都不像正常的版本数据", error?.message)
    }

    // ── 镜像来源 ──

    /** 记录访问过哪些来源；map 里没有的地址当成「连不上」。 */
    private class Recorder(private val responses: Map<String, GithubRaw>) : GithubTransport {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String): GithubRaw {
            urls += url
            return responses[url] ?: throw java.io.IOException("connect failed for $url")
        }
    }

    private val mirrorLatestUrl = "https://gh-proxy.com/$latestUrl"
    private val mirrorAssetUrl = "https://gh-proxy.com/$assetUrl"

    @Test
    fun `direct being unreachable falls through to the mirror`() {
        val transport = Recorder(mapOf(mirrorLatestUrl to GithubRaw(200, releaseBody)))
        val release = runBlocking { GithubApi(transport).latestRelease() }

        assertEquals("v1.0.1", release.tagName)
        assertEquals(listOf(latestUrl, mirrorLatestUrl), transport.urls)
    }

    @Test
    fun `a mirror answering with a html page keeps the chain going`() {
        val transport = Recorder(mapOf(
            latestUrl to GithubRaw(200, "<html>blocked</html>"),
            mirrorLatestUrl to GithubRaw(200, releaseBody),
        ))
        val release = runBlocking { GithubApi(transport).latestRelease() }

        assertEquals("v1.0.1", release.tagName)
        assertEquals(listOf(latestUrl, mirrorLatestUrl), transport.urls)
    }

    @Test
    fun `every source unreachable says so instead of pretending up to date`() {
        val transport = Recorder(emptyMap())
        val message = runCatching { runBlocking { GithubApi(transport).latestRelease() } }
            .exceptionOrNull()?.message
        assertEquals("GitHub 和镜像站都没连上", message)
        assertEquals(2, transport.urls.size)
    }

    @Test
    fun `file only mirrors never get the api request`() {
        // ghfast.top 与 ghproxy.net 实测对 api.github.com 回 403，试它们只会拖慢检查更新。
        assertEquals(listOf(GithubMirror.Direct, GithubMirror.GhProxyCom), apiChain())

        val transport = Recorder(mapOf(mirrorLatestUrl to GithubRaw(200, releaseBody)))
        val release = runBlocking { GithubApi(transport).latestRelease() }
        assertEquals("v1.0.1", release.tagName)
        assertEquals(listOf(latestUrl, mirrorLatestUrl), transport.urls)
    }

    @Test
    fun `asset urls are rewritten through the chosen mirror`() {
        assertEquals(assetUrl, viaMirror(GithubMirror.Direct, assetUrl))
        assertEquals(mirrorAssetUrl, viaMirror(GithubMirror.GhProxyCom, assetUrl))
        assertEquals(
            "https://ghfast.top/https://github.com/qine233/typecho-app/releases/download/v1.0.1/a.apk",
            viaMirror(GithubMirror.GhfastTop, assetUrl)
        )
        // 下载链是全部来源都能转的，顺序只由用户选的排，其余留作兜底。
        assertEquals(
            listOf(GithubMirror.GhproxyNet, GithubMirror.Direct, GithubMirror.GhProxyCom, GithubMirror.GhfastTop),
            downloadChain(GithubMirror.GhproxyNet)
        )
    }

    @Test
    fun `an unknown stored source id falls back to the default mirror`() {
        // 镜像站会失效，选项也可能改名；存了个认不出的 id 不能把检查更新卡死。
        assertEquals(GithubMirror.GhProxyCom, GithubMirror.fromId("ghp.ci"))
        assertEquals(GithubMirror.GhProxyCom, GithubMirror.fromId(null))
        assertEquals(GithubMirror.GhfastTop, GithubMirror.fromId("ghfast-top"))
    }
}
