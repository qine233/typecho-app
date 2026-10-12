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

    private val fileOk = """{"code":0,"data":{"id":"Ab1","name":"1.jpg","path":"cloudreve://my/blog/2026/10/1.jpg"}}"""

    private val sourceOk =
        """{"code":0,"data":[{"link":"https://pan.work/f/Vx9/1.jpg","file_url":"https://pan.work/f/Vx9/1.jpg?sign=x"}]}"""

    private fun failure(code: Int, msg: String = "") = ok("""{"code":$code,"msg":"$msg"}""")

    private fun errorOf(transport: Fake, block: suspend () -> Unit): CloudreveException =
        assertThrows(CloudreveException::class.java) { runBlocking { block() } } as CloudreveException

    @Test
    fun `builds v4 api urls`() {
        assertEquals(
            "https://pan.work/api/v4/file/content",
            CloudreveApi.apiUrl("https://pan.work/", "file/content")
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
    fun `upload puts raw bytes to the encoded uri with the bearer token`() {
        val transport = Fake { ok(fileOk) }
        val bytes = byteArrayOf(1, 2, 3, 4)
        val node = runBlocking {
            CloudreveApi(transport).upload("https://pan.work", "T1", "cloudreve://my/blog/2026/10/1.jpg", bytes)
        }

        assertEquals("Ab1", node.id)
        val request = transport.calls.single()
        assertEquals("PUT", request.method)
        assertEquals(
            "https://pan.work/api/v4/file/content?uri=cloudreve%3A%2F%2Fmy%2Fblog%2F2026%2F10%2F1.jpg",
            request.url
        )
        assertContentEquals(bytes, request.bytes)
        assertEquals("Bearer T1", request.headers["Authorization"])
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

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray?) {
        assertTrue("bytes forwarded", actual != null && actual.contentEquals(expected))
    }
}
