package noline.zhouzhou.data.repository

import kotlinx.coroutines.runBlocking
import noline.zhouzhou.data.model.CloudreveConfig
import noline.zhouzhou.data.remote.CloudreveApi
import noline.zhouzhou.data.remote.CloudreveException
import noline.zhouzhou.data.remote.CloudreveIssue
import noline.zhouzhou.data.remote.CloudreveRaw
import noline.zhouzhou.data.remote.CloudreveRequest
import noline.zhouzhou.data.remote.CloudreveTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 令牌缓存、过期重登一次、目标路径 —— 都靠假传输驱动真实的 CloudreveApi 跑出来。 */
class CloudreveRepositoryTest {

    private class Server(
        var rejectAuthTimes: Int = 0,
        var rejectAuthCode: Int = 401
    ) : CloudreveTransport {
        var logins = 0
        var uploads = 0
        var links = 0
        val uploadUrls = mutableListOf<String>()

        override suspend fun execute(request: CloudreveRequest): CloudreveRaw {
            val path = request.url.substringAfter("/api/v4/").substringBefore('?')
            if (path == "session/token") {
                logins++
                return CloudreveRaw(
                    200,
                    """{"code":0,"data":{"token":{"access_token":"T$logins",""" +
                        """"access_expires":"2099-01-01T00:00:00+08:00"}}}"""
                )
            }
            if (rejectAuthTimes > 0) {
                rejectAuthTimes--
                return CloudreveRaw(200, """{"code":$rejectAuthCode,"msg":"server says so"}""")
            }
            return when (path) {
                "file/content" -> {
                    uploads++
                    uploadUrls += request.url
                    CloudreveRaw(200, """{"code":0,"data":{"id":"Ab1","name":"1.jpg"}}""")
                }
                "file/source" -> {
                    links++
                    CloudreveRaw(200, """{"code":0,"data":[{"link":"https://pan.work/f/Vx9/1.jpg"}]}""")
                }
                else -> CloudreveRaw(200, """{"code":404,"msg":"no such endpoint"}""")
            }
        }
    }

    private val configured = CloudreveConfig("https://pan.work", "me@x.work", "pw")

    private fun repository(server: Server, config: CloudreveConfig = configured) =
        CloudreveRepository(CloudreveApi(server)) { config }

    @Test
    fun `one login covers a batch of uploads`() {
        val server = Server()
        val repo = repository(server)
        val urls = runBlocking { listOf(repo.uploadImage(byteArrayOf(1)), repo.uploadImage(byteArrayOf(2))) }

        assertEquals(1, server.logins)
        assertEquals(2, server.uploads)
        assertEquals(2, server.links)
        urls.forEach { assertEquals("https://pan.work/f/Vx9/1.jpg", it) }
        server.uploadUrls.forEach {
            assertTrue(it.startsWith("https://pan.work/api/v4/file/content?uri=cloudreve%3A%2F%2Fmy%2Fblog%2F"))
        }
    }

    @Test
    fun `an expired token costs exactly one re-login`() {
        val server = Server(rejectAuthTimes = 1)
        val link = runBlocking { repository(server).uploadImage(byteArrayOf(1)) }

        assertEquals("https://pan.work/f/Vx9/1.jpg", link)
        assertEquals(2, server.logins)
        assertEquals(1, server.uploads)
    }

    @Test
    fun `keeps failing when re-login does not help`() {
        val server = Server(rejectAuthTimes = 3)
        val e = assertThrows(CloudreveException::class.java) {
            runBlocking { repository(server).uploadImage(byteArrayOf(1)) }
        }

        assertEquals(CloudreveIssue.NotLoggedIn, e.issue)
        assertEquals(2, server.logins)
        assertEquals(0, server.uploads)
    }

    @Test
    fun `a capacity error is not retried by re-logging in`() {
        val server = Server(rejectAuthTimes = 1, rejectAuthCode = 40051)
        val e = assertThrows(CloudreveException::class.java) {
            runBlocking { repository(server).uploadImage(byteArrayOf(1)) }
        }

        assertEquals(CloudreveIssue.NoCapacity, e.issue)
        assertEquals(1, server.logins)
        assertEquals(0, server.links)
    }

    @Test
    fun `nothing is sent before the image host is configured`() {
        val server = Server()
        val repo = repository(server, CloudreveConfig())

        assertFalse(runBlocking { repo.isConfigured() })
        val e = assertThrows(CloudreveException::class.java) { runBlocking { repo.uploadImage(byteArrayOf(1)) } }
        assertEquals(CloudreveIssue.BadInput, e.issue)
        assertEquals(0, server.logins)
    }

    @Test
    fun `verify always logs in again`() {
        val server = Server()
        val repo = repository(server)
        runBlocking {
            repo.uploadImage(byteArrayOf(1))
            repo.verify()
        }

        assertEquals(2, server.logins)
    }

    @Test
    fun `target uri files by year and pads the month`() {
        val january = CloudreveRepository.targetUri(LocalDateTime.of(2026, 1, 9, 8, 0))
        val december = CloudreveRepository.targetUri(LocalDateTime.of(2026, 12, 31, 23, 59, 59))

        assertTrue(january, Regex("""cloudreve://my/blog/2026/01/\d{13}\.jpg""").matches(january))
        assertTrue(december, Regex("""cloudreve://my/blog/2026/12/\d{13}\.jpg""").matches(december))
        // 时间戳递增，同一天连拍也不会互相盖掉
        val stamp = { uri: String -> uri.substringAfterLast('/').substringBeforeLast('.').toLong() }
        assertTrue("stamp must advance", stamp(december) > stamp(january))
    }
}
