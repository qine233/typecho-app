package noline.zhouzhou.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudreve v4 几乎所有响应（连鉴权失败）都是 HTTP 200，成败只看 body 的 code，
 * 所以用例一律用 body 造响应；错误码取自源码的常量表，不是博客里的说法。
 */
class CloudreveApiTest {

    private class Fake(val handler: (CloudreveRequest) -> CloudreveRaw) : CloudreveTransport {
        val calls = mutableListOf<CloudreveRequest>()
        override suspend fun execute(request: CloudreveRequest): CloudreveRaw {
            calls += request
            return handler(request)
        }
    }

    private fun ok(body: String) = CloudreveRaw(200, body)

    private val loginOk =
        """{"code":0,"data":{"user":{"id":"1"},"token":{"access_token":"T1",""" +
            """"refresh_token":"R1","access_expires":"2099-01-01T00:00:00+08:00"}}}"""

    private val localSessionOk =
        """{"code":0,"data":{"session_id":"S1","chunk_size":0,"callback_secret":"sec1",""" +
            """"storage_policy":{"type":"local"}}}"""

    private val sourceOk =
        """{"code":0,"data":[{"link":"https://pan.work/f/Vx9/1.jpg","file_url":"https://pan.work/f/Vx9/1.jpg?sign=x"}]}"""

    private fun failure(code: Int, msg: String = "") = ok("""{"code":$code,"msg":"$msg"}""")

    private fun errorOf(transport: Fake, block: suspend () -> Unit): CloudreveException =
        assertThrows(CloudreveException::class.java) { runBlocking { block() } } as CloudreveException

    @Test
    fun `builds v4 api urls`() {
        assertEquals(
            "https://pan.work/api/v4/file/upload",
            CloudreveApi.apiUrl("https://pan.work/", "file/upload")
        )
        assertEquals("https://pan.work/api/v4/session/token", CloudreveApi.apiUrl("https://pan.work", "session/token"))
    }

    @Test
    fun `login unwraps the token out of the user envelope`() {
        val transport = Fake { ok(loginOk) }
        val token = runBlocking { CloudreveApi(transport).login("https://pan.work", "me@x.work", "secret") }

        assertEquals("T1", token.accessToken)
        val request = transport.calls.single()
        assertEquals("POST", request.method)
        assertEquals("https://pan.work/api/v4/session/token", request.url)
        assertTrue(request.body!!.contains("\"email\":\"me@x.work\""))
        // 登录接口本身不该带上一轮的 Authorization
        assertFalse(request.headers.containsKey("Authorization"))
    }

    @Test
    fun `maps the password and login-flow error codes`() {
        val cases = listOf(
            40080 to CloudreveIssue.WrongPassword,
            40069 to CloudreveIssue.WrongPassword,
            40026 to CloudreveIssue.Captcha,
            203 to CloudreveIssue.TwoFactor,
            401 to CloudreveIssue.NotLoggedIn,
            40011 to CloudreveIssue.SessionExpired,
            40005 to CloudreveIssue.SignExpired,
            40007 to CloudreveIssue.NoPermission,
            40049 to CloudreveIssue.TooLarge,
            40051 to CloudreveIssue.NoCapacity,
            40050 to CloudreveIssue.TypeNotAllowed,
            40052 to CloudreveIssue.IllegalName,
            40019 to CloudreveIssue.FeatureOff,
            40001 to CloudreveIssue.BadInput,
            40044 to CloudreveIssue.NotFound,
            40999 to CloudreveIssue.Unknown
        )
        cases.forEach { (code, issue) ->
            val transport = Fake { failure(code, "server says so") }
            val e = errorOf(transport) { CloudreveApi(transport).login("https://p", "a@b.c", "pw") }
            assertEquals("code $code", issue, e.issue)
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `auth failures are the ones a re-login can fix`() {
        assertTrue(CloudreveIssue.NotLoggedIn.isAuthFailure)
        assertTrue(CloudreveIssue.SessionExpired.isAuthFailure)
        assertTrue(CloudreveIssue.SignExpired.isAuthFailure)
        assertFalse(CloudreveIssue.WrongPassword.isAuthFailure)
        assertFalse(CloudreveIssue.Captcha.isAuthFailure)
        assertFalse(CloudreveIssue.NoCapacity.isAuthFailure)
    }

    @Test
    fun `chinese messages replace the english ones`() {
        val transport = Fake { failure(40080, "Incorrect password or email address") }
        val e = errorOf(transport) { CloudreveApi(transport).login("https://p", "a@b.c", "pw") }
        assertEquals("Cloudreve 邮箱或密码不对", e.message)
    }

    @Test
    fun `login without a token is an error, not an empty string`() {
        val transport = Fake { ok("""{"code":0,"data":{"user":{"id":"1"},"token":{"access_token":""}}}""") }
        val e = errorOf(transport) { CloudreveApi(transport).login("https://p", "a@b.c", "pw") }
        assertEquals(CloudreveIssue.Unknown, e.issue)
    }

    @Test
    fun `local upload opens a session then posts the whole file as one chunk`() {
        val transport = Fake { request ->
            when {
                request.method == "PUT" -> ok(localSessionOk)
                else -> ok("""{"code":0,"data":null}""")
            }
        }
        val bytes = byteArrayOf(1, 2, 3, 4)
        runBlocking {
            CloudreveApi(transport)
                .uploadFile("https://pan.work", "T1", "cloudreve://my/blog/2026/10/1.jpg", bytes, "image/jpeg")
        }

        val session = transport.calls[0]
        assertEquals("PUT", session.method)
        assertEquals(
            "https://pan.work/api/v4/file/upload?uri=cloudreve%3A%2F%2Fmy%2Fblog%2F2026%2F10%2F1.jpg",
            session.url
        )
        assertTrue(session.body!!, session.body!!.contains("""{"uri":"cloudreve://my/blog/2026/10/1.jpg","size":4,"mime_type":"image/jpeg"}"""))

        val chunk = transport.calls[1]
        assertEquals("POST", chunk.method)
        assertEquals("https://pan.work/api/v4/file/upload/S1/0", chunk.url)
        assertEquals(bytes.toList(), chunk.bytes?.toList())
        assertEquals("Bearer T1", chunk.headers["Authorization"])
        // 本机策略不需要回调收尾
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `remote upload signs the range and never sends bytes to the master`() {
        val transport = Fake { request ->
            when {
                request.url.startsWith("https://graph.work") -> CloudreveRaw(200, """{"size":2}""")
                request.method == "PUT" && request.url.contains("file/upload") ->
                    ok("""{"code":0,"data":{"session_id":"S1","chunk_size":2,"upload_urls":["https://graph.work/u1"],"callback_secret":"sec1","storage_policy":{"type":"onedrive"}}}""")
                else -> ok("""{"code":0,"data":null}""")
            }
        }
        runBlocking {
            CloudreveApi(transport).uploadFile("https://pan.work", "T1", "cloudreve://my/a.jpg", byteArrayOf(1, 2, 3), "image/jpeg")
        }

        val ranges = transport.calls.filter { it.url.startsWith("https://graph.work") }.map { it.headers["Content-Range"] }
        assertEquals(listOf("bytes 0-1/3", "bytes 2-2/3"), ranges)
        // 预签名地址只带 Content-Range，Cloudreve 的 Bearer 一贴上去存储端就拒收
        val remote = transport.calls.first { it.url.startsWith("https://graph.work") }
        assertFalse(remote.headers.containsKey("Authorization"))
        assertEquals(
            "https://pan.work/api/v4/callback/onedrive/S1/sec1",
            transport.calls.last().url
        )
    }

    @Test
    fun `a broken session is cancelled instead of leaving a placeholder behind`() {
        val transport = Fake { request ->
            when {
                request.method == "PUT" -> ok(localSessionOk)
                request.method == "POST" -> failure(50004, "io failed")
                else -> ok("""{"code":0,"data":null}""")
            }
        }
        val e = errorOf(transport) {
            CloudreveApi(transport).uploadFile("https://pan.work", "T1", "cloudreve://my/a.jpg", byteArrayOf(1), "image/jpeg")
        }

        assertEquals(CloudreveIssue.IoFailed, e.issue)
        val cancel = transport.calls.last()
        assertEquals("DELETE", cancel.method)
        assertTrue(cancel.body!!, cancel.body!!.contains("\"id\":\"S1\""))
    }

    @Test
    fun `direct link reads the permanent link, not the signed one`() {
        val transport = Fake { ok(sourceOk) }
        val link = runBlocking { CloudreveApi(transport).directLink("https://pan.work", "T1", "cloudreve://my/a.jpg") }

        assertEquals("https://pan.work/f/Vx9/1.jpg", link)
        val request = transport.calls.single()
        assertEquals("PUT", request.method)
        assertTrue(request.body!!.contains("\"uris\":[\"cloudreve://my/a.jpg\"]"))
    }

    @Test
    fun `empty direct link list is reported instead of blank url`() {
        listOf("""{"code":0,"data":[]}""", """{"code":0,"data":[{"file_url":"x"}]}""").forEach { body ->
            val transport = Fake { ok(body) }
            val e = errorOf(transport) { CloudreveApi(transport).directLink("https://p", "T1", "cloudreve://my/a.jpg") }
            assertEquals(CloudreveIssue.Unknown, e.issue)
        }
    }

    // ── 网盘浏览与整理 ──

    /**
     * 实测回来的时间戳小数位长度不固定（3 位到 9 位都有），照抄站点返回。
     * 一整块原始字符串：片段拼接时，行尾的引号会和 `"""` 粘成四个，编译器会把片段截断。
     */
    private val listingOk =
        """{"code":0,"data":{"files":[
            {"type":1,"id":"a1","name":"image","size":0,"path":"cloudreve://my/image",
             "updated_at":"2025-06-12T12:09:58.937586723+08:00"},
            {"type":0,"id":"b2","name":"mimi.png","size":1177657,"shared":true,"path":"cloudreve://my/image/mimi.png",
             "created_at":"2025-06-18T02:11:10.391070503+08:00"}
            ],"parent":{"type":1,"id":"anCK","name":"image","path":"cloudreve://my/image"},
            "pagination":{"page":0,"page_size":100,"next_token":"eyJpZCI6ImIyIn0=","is_cursor":true}}}"""

    @Test
    fun `listing asks for a whole page by name and keeps folder and file apart`() {
        val transport = Fake { ok(listingOk) }
        val page = runBlocking { CloudreveApi(transport).list("https://pan.work", "T1", "cloudreve://my/image") }

        val url = transport.calls.single().url
        assertTrue(url, url.startsWith("https://pan.work/api/v4/file?uri=cloudreve%3A%2F%2Fmy%2Fimage"))
        assertTrue(url, url.contains("page_size=100"))
        assertTrue(url, url.contains("order_by=name"))
        assertFalse(url, url.contains("next_page_token"))

        assertEquals(2, page.files.size)
        assertTrue(page.files[0].isFolder)
        assertFalse(page.files[1].isFolder)
        assertEquals(1177657L, page.files[1].size)
        assertEquals("cloudreve://my/image", page.parent?.path)
        assertEquals("eyJpZCI6ImIyIn0=", page.pagination?.nextToken)
    }

    @Test
    fun `the cursor is url encoded on the way back`() {
        val transport = Fake { ok(listingOk) }
        runBlocking {
            CloudreveApi(transport).list("https://pan.work", "T1", "cloudreve://my", nextToken = "eyJpZCI6ImIyIn0=")
        }
        assertTrue(
            transport.calls.single().url,
            transport.calls.single().url.endsWith("next_page_token=eyJpZCI6ImIyIn0%3D")
        )
    }

    /** 翻到底时 pagination 里根本没有 next_token 这个键，不是空串。 */
    @Test
    fun `a finished page has no cursor left`() {
        val transport = Fake {
            ok("""{"code":0,"data":{"files":[],"pagination":{"page":0,"page_size":100,"is_cursor":true}}}""")
        }
        val page = runBlocking { CloudreveApi(transport).list("https://p", "T1", "cloudreve://my") }

        assertTrue(page.files.isEmpty())
        assertEquals("", page.pagination?.nextToken)
    }

    @Test
    fun `drive mutations post the bodies v4 expects`() {
        val transport = Fake { ok("""{"code":0,"data":null}""") }
        val api = CloudreveApi(transport)
        runBlocking {
            api.createFolder("https://p", "T1", "cloudreve://my/blog/2026")
            api.rename("https://p", "T1", "cloudreve://my/a.jpg", "b.jpg")
            api.move("https://p", "T1", listOf("cloudreve://my/a.jpg"), "cloudreve://my/blog")
            api.softDelete("https://p", "T1", listOf("cloudreve://my/a.jpg", "cloudreve://my/b.jpg"))
        }

        val create = transport.calls[0]
        assertEquals("POST", create.method)
        assertEquals("https://p/api/v4/file/create", create.url)
        assertEquals("""{"uri":"cloudreve://my/blog/2026","type":"folder"}""", create.body)

        val rename = transport.calls[1]
        assertEquals("https://p/api/v4/file/rename", rename.url)
        assertEquals("""{"uri":"cloudreve://my/a.jpg","new_name":"b.jpg"}""", rename.body)

        val move = transport.calls[2]
        assertEquals("https://p/api/v4/file/move", move.url)
        assertEquals("""{"uris":["cloudreve://my/a.jpg"],"dst":"cloudreve://my/blog"}""", move.body)

        val delete = transport.calls[3]
        assertEquals("DELETE", delete.method)
        assertEquals("https://p/api/v4/file", delete.url)
        assertEquals("""{"uris":["cloudreve://my/a.jpg","cloudreve://my/b.jpg"]}""", delete.body)
    }

    /** 4.14.1 上容量只有 user/capacity 这条路，user 与 user/quota 都是 404。 */
    @Test
    fun `capacity reads the users own bookkeeping`() {
        val transport = Fake { ok("""{"code":0,"data":{"total":5497558138880,"used":79958973997}}""") }
        val capacity = runBlocking { CloudreveApi(transport).capacity("https://p", "T1") }

        assertEquals("https://p/api/v4/user/capacity", transport.calls.single().url)
        assertEquals("GET", transport.calls.single().method)
        assertEquals(5497558138880L, capacity.total)
        assertEquals(79958973997L, capacity.used)
        assertEquals(0.0145f, capacity.usedFraction, 0.0001f)
    }

    /** total 不是正数时（无限配额、老版本、字段缺失）没有上限可言，别算出「剩 -1 B」。 */
    @Test
    fun `a total that is not positive has no usable fraction`() {
        val transport = Fake { ok("""{"code":0,"data":{"total":-1,"used":123}}""") }
        val capacity = runBlocking { CloudreveApi(transport).capacity("https://p", "T1") }

        assertFalse(capacity.hasQuota)
        assertEquals(0f, capacity.usedFraction, 0f)
    }

    /** 站点没装 Cloudreve / 地址填成博客本体时，回来的是 HTML。 */
    @Test
    fun `non json body falls back to the http status`() {
        val notFound = Fake { CloudreveRaw(404, "<html>not found</html>") }
        val missing = errorOf(notFound) { CloudreveApi(notFound).login("https://p", "a@b.c", "pw") }
        assertEquals(CloudreveIssue.NotFound, missing.issue)

        val weird = Fake { CloudreveRaw(200, "<html><?php echo 'hi' ?></html>") }
        val e = errorOf(weird) { CloudreveApi(weird).login("https://p", "a@b.c", "pw") }
        assertEquals(CloudreveIssue.Unknown, e.issue)
        assertTrue(e.message.contains("HTTP 200"))
    }
}
