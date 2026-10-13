package noline.zhouzhou.data.repository

import kotlinx.coroutines.runBlocking
import noline.zhouzhou.data.model.CloudreveConfig
import noline.zhouzhou.data.remote.CloudreveApi
import noline.zhouzhou.data.remote.CloudreveException
import noline.zhouzhou.data.remote.CloudreveFileNode
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

/** 令牌缓存、过期重登一次、三步分片上传 —— 都靠假传输驱动真实的 CloudreveApi 跑出来。 */
class CloudreveRepositoryTest {

    private class Server(
        var rejectAuthTimes: Int = 0,
        var rejectAuthCode: Int = 401,
        var chunkSize: Long = 0,
        var remote: Boolean = false,
        var chunkCode: Int = 0
    ) : CloudreveTransport {
        var logins = 0
        var sessions = 0
        var chunks = 0
        var callbacks = 0
        var cancels = 0
        var links = 0
        var listings = 0
        var capacities = 0
        val sessionUrls = mutableListOf<String>()
        val chunkBodies = mutableListOf<ByteArray>()
        val remoteHeaders = mutableListOf<String>()
        val listQueries = mutableListOf<String>()
        val mutations = mutableListOf<String>()

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
            if (request.url.startsWith("https://graph.work")) {
                chunks++
                remoteHeaders += request.headers["Content-Range"] ?: ""
                chunkBodies += request.bytes ?: ByteArray(0)
                return CloudreveRaw(200, """{"size":1}""")
            }
            if (rejectAuthTimes > 0) {
                rejectAuthTimes--
                return CloudreveRaw(200, """{"code":$rejectAuthCode,"msg":"server says so"}""")
            }
            return when {
                path == "file/upload" && request.method == "PUT" -> {
                    sessions++
                    sessionUrls += request.url
                    val urls = if (remote) """["https://graph.work/upload-session/1"]""" else "[]"
                    val type = if (remote) "onedrive" else "local"
                    CloudreveRaw(
                        200,
                        """{"code":0,"data":{"session_id":"S$sessions","chunk_size":$chunkSize,""" +
                            """"upload_urls":$urls,"callback_secret":"sec$sessions",""" +
                            """"storage_policy":{"type":"$type"}}}"""
                    )
                }
                path == "file/upload" && request.method == "DELETE" -> {
                    cancels++
                    CloudreveRaw(200, """{"code":0,"data":null}""")
                }
                path.startsWith("file/upload/") -> {
                    chunks++
                    chunkBodies += request.bytes ?: ByteArray(0)
                    CloudreveRaw(200, """{"code":$chunkCode,"msg":""}""")
                }
                path.startsWith("callback/onedrive/") -> {
                    callbacks++
                    CloudreveRaw(200, """{"code":0,"data":null}""")
                }
                path == "file/source" -> {
                    links++
                    CloudreveRaw(200, """{"code":0,"data":[{"link":"https://pan.work/f/Vx9/1.jpg"}]}""")
                }
                path == "file" && request.method == "GET" -> {
                    listings++
                    listQueries += request.url.substringAfter("?", "")
                    CloudreveRaw(
                        200,
                        // 一整块原始字符串，分行只是排版：拼接片段时收尾引号会和分隔符粘成四个，编译器直接看不懂。
                        """{"code":0,"data":{"files":[
                            {"type":1,"id":"a1","name":"blog","path":"cloudreve://my/blog"},
                            {"type":0,"id":"b2","name":"1.jpg","size":2048,"path":"cloudreve://my/1.jpg"}
                            ],"pagination":{"page_size":100,"next_token":"TK","is_cursor":true}}}"""
                    )
                }
                path == "user/capacity" -> {
                    capacities++
                    CloudreveRaw(200, """{"code":0,"data":{"total":5497558138880,"used":79958973997}}""")
                }
                path == "file" || path == "file/create" || path == "file/rename" || path == "file/move" -> {
                    mutations += "$path ${request.method} ${request.body}"
                    CloudreveRaw(200, """{"code":0,"data":null}""")
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
        assertEquals(2, server.sessions)
        assertEquals(2, server.links)
        urls.forEach { assertEquals("https://pan.work/f/Vx9/1.jpg", it) }
        server.sessionUrls.forEach {
            assertTrue(it, it.startsWith("https://pan.work/api/v4/file/upload?uri=cloudreve%3A%2F%2Fmy%2Fblog%2F"))
        }
    }

    @Test
    fun `a session without upload urls posts every chunk to the master`() {
        val server = Server(chunkSize = 2)
        runBlocking { repository(server).uploadImage(byteArrayOf(1, 2, 3, 4, 5)) }

        assertEquals(3, server.chunks)
        assertEquals(listOf(2, 2, 1), server.chunkBodies.map { it.size })
        assertEquals(0, server.callbacks)
    }

    @Test
    fun `a session with upload urls goes straight to storage then tells master`() {
        val server = Server(chunkSize = 4, remote = true)
        runBlocking { repository(server).uploadImage(byteArrayOf(1, 2, 3, 4, 5)) }

        assertEquals(2, server.chunks)
        assertEquals(listOf("bytes 0-3/5", "bytes 4-4/5"), server.remoteHeaders)
        assertEquals(1, server.callbacks)
        // 主机这一侧只建了一次会话，字节不能又发一遍
        assertEquals(1, server.sessions)
    }

    @Test
    fun `a rejected chunk cancels the session and surfaces the code`() {
        val server = Server(chunkCode = 40013)
        val e = assertThrows(CloudreveException::class.java) {
            runBlocking { repository(server).uploadImage(byteArrayOf(1, 2)) }
        }

        assertEquals(CloudreveIssue.ChunkMismatch, e.issue)
        assertEquals(1, server.cancels)
        assertEquals(0, server.links)
    }

    @Test
    fun `an expired token costs exactly one re-login`() {
        val server = Server(rejectAuthTimes = 1)
        val link = runBlocking { repository(server).uploadImage(byteArrayOf(1)) }

        assertEquals("https://pan.work/f/Vx9/1.jpg", link)
        assertEquals(2, server.logins)
        // 会话只在重登之后建了一次，被拒的那一回没碰到写入
        assertEquals(1, server.sessions)
    }

    @Test
    fun `keeps failing when re-login does not help`() {
        val server = Server(rejectAuthTimes = 3)
        val e = assertThrows(CloudreveException::class.java) {
            runBlocking { repository(server).uploadImage(byteArrayOf(1)) }
        }

        assertEquals(CloudreveIssue.NotLoggedIn, e.issue)
        assertEquals(2, server.logins)
        assertEquals(0, server.sessions)
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
    fun `a silent server failure is reported as such`() {
        val server = Server(rejectAuthTimes = 1, rejectAuthCode = -1)
        val e = assertThrows(CloudreveException::class.java) {
            runBlocking { repository(server).uploadImage(byteArrayOf(1)) }
        }

        assertEquals(CloudreveIssue.SilentFailure, e.issue)
        assertTrue(e.message, e.message!!.contains("-1"))
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
    fun `a drive listing rides on the login the upload already did`() {
        val server = Server()
        val repo = repository(server)
        val page = runBlocking {
            repo.uploadImage(byteArrayOf(1))
            repo.list("cloudreve://my").getOrThrow()
        }

        assertEquals(1, server.logins)
        assertEquals(2, page.files.size)
        assertEquals("TK", page.pagination?.nextToken)
        assertTrue(server.listQueries.single().contains("uri=cloudreve%3A%2F%2Fmy"))
    }

    @Test
    fun `the move picker only offers directories`() {
        val server = Server()
        val folders = runBlocking { repository(server).listFolders("cloudreve://my").getOrThrow() }

        assertEquals(listOf("blog"), folders.map { it.name })
        assertTrue(folders.single().isFolder)
    }

    @Test
    fun `a new folder lands under the directory the user is looking at`() {
        val server = Server()
        runBlocking { repository(server).createFolder("cloudreve://my/", "blog").getOrThrow() }

        assertEquals(
            """file/create POST {"uri":"cloudreve://my/blog","type":"folder"}""",
            server.mutations.single()
        )
    }

    /** 界面层靠 Result 显示错误条，仓库层不能再把异常抛出去。 */
    @Test
    fun `drive operations report failures instead of throwing`() {
        val repo = repository(Server(rejectAuthTimes = 99), CloudreveConfig())
        val file = CloudreveFileNode(name = "a", path = "cloudreve://my/a")

        val failure = runBlocking { repo.list("cloudreve://my") }.exceptionOrNull() as CloudreveException
        assertEquals(CloudreveIssue.BadInput, failure.issue)
        assertTrue(runBlocking { repo.rename(file, "b") }.isFailure)
        assertTrue(runBlocking { repo.move(file, "cloudreve://my/blog") }.isFailure)
        assertTrue(runBlocking { repo.softDelete(file) }.isFailure)
        assertTrue(runBlocking { repo.directLink(file) }.isFailure)
    }

    @Test
    fun `capacity is a quiet extra line so a failure just hides it`() {
        val server = Server()
        val capacity = runBlocking { repository(server).capacity() }

        assertEquals(1, server.capacities)
        assertEquals(79958973997L, capacity?.used)
        assertEquals(5497558138880L, capacity?.total)

        // 没配 Cloudreve 时这行不该冒出来，也不该把整页带崩。
        assertTrue(runBlocking { repository(server, CloudreveConfig()).capacity() } == null)
    }

    @Test
    fun `target uri files by year and pads the month`() {
        val january = CloudreveRepository.targetUri(LocalDateTime.of(2026, 1, 9, 8, 0))
        val december = CloudreveRepository.targetUri(LocalDateTime.of(2026, 12, 31, 23, 59, 59))

        assertTrue(january, Regex("""cloudreve://my/blog/2026/01/\d{13}\.jpg""").matches(january))
        assertTrue(december, Regex("""cloudreve://my/blog/2026/12/\d{13}\.jpg""").matches(december))
        // 时间戳递增，同一天连拍也不会互相盖掉
        val stamp = { uri: String -> uri.substringAfterLast('/').substringBeforeLast('.').toLong() }
        assertTrue("stamp must increase", stamp(december) > stamp(january))
    }
}
