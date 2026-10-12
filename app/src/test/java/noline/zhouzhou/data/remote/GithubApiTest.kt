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
        assertEquals("发布信息读不懂，可能接口改了字段", error?.message)
    }
}
