package noline.zhouzhou.data.remote

import net.schmizz.sshj.transport.TransportException
import noline.zhouzhou.data.model.SshAuthKind
import noline.zhouzhou.data.model.SshConnectionFailure
import noline.zhouzhou.data.model.SshFailureKind
import noline.zhouzhou.data.model.SshHostKey
import noline.zhouzhou.data.model.SshProfile
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拿真 SSH 服务器（进程内 Apache SSHD）钉 SSHJ 这层的四条行为：
 *
 * 1. 第一次见面必须问用户，问了才算数，问出来的指纹要和 OpenSSH 的算法对得上；
 * 2. 固定下来之后再来，不该再问；
 * 3. 服务器换了密钥要硬失败，且不能退回「问一下」——那是中间人最想看到的分支；
 * 4. PTY 起来了，键盘字节进得去、服务器回包读得出来。
 */
class SshLinkTest {

    private lateinit var server: SshServer
    private lateinit var hostKey: java.security.KeyPair
    private lateinit var alternateKey: java.security.KeyPair

    private fun freshKeys(): Pair<java.security.KeyPair, java.security.KeyPair> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair() to generator.generateKeyPair()
    }

    @Before
    fun setUp() {
        SshCrypto.ensureProvider()
        val (first, second) = freshKeys()
        hostKey = first
        alternateKey = second
        server = SshServer.setUpDefaultServer()
        server.port = 0
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey))
        server.setPasswordAuthenticator(object : PasswordAuthenticator {
            override fun authenticate(user: String, password: String, session: org.apache.sshd.server.session.ServerSession) =
                user == TestUser && password == TestPassword
        })
        server.shellFactory = ShellFactory { EchoCommand() }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.stop(true) }
    }

    private val port: Int get() = server.port

    private fun profile(password: String = TestPassword) = SshProfile(
        host = "127.0.0.1",
        port = port,
        user = TestUser,
        authKind = SshAuthKind.Password,
        secret = password
    )

    /** 等指纹弹窗，默认点「信」。 */
    private class Harness(val accept: Boolean = true) {
        val asked = AtomicInteger(0)
        var candidate: SshHostKey? = null

        fun approve(key: SshHostKey): Boolean {
            asked.incrementAndGet()
            candidate = key
            return accept
        }
    }

    @Test
    fun firstContactAsksAndMatchesOpenSshFingerprint() {
        val rig = Harness()
        val link = SshLink(profile(), emptyList(), rig::approve)
        link.connect(Cols, Rows)
        assertEquals(1, rig.asked.get())
        val candidate = rig.candidate
        assertNotNull(candidate)
        assertEquals("127.0.0.1", candidate!!.host)
        assertEquals(port, candidate.port)
        assertEquals(expectedFingerprint(hostKey), candidate.fingerprint)
        assertEquals(candidate.fingerprint, link.acceptedKey?.fingerprint)
        assertTrue(link.connected)
        link.close()
    }

    @Test
    fun pinnedKeyIsNotAskedAgain() {
        val pinned = SshHostKey.of("127.0.0.1", port, SshCrypto.publicKeyBlob(hostKey.public))
        val rig = Harness()
        val link = SshLink(profile(), listOf(pinned), rig::approve)
        link.connect(Cols, Rows)
        assertEquals(0, rig.asked.get())
        assertEquals(null, link.acceptedKey)
        link.close()
    }

    @Test
    fun changedHostKeyFailsHardWithoutAsking() {
        val pinned = SshHostKey.of("127.0.0.1", port, SshCrypto.publicKeyBlob(alternateKey.public))
        val rig = Harness()
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey))
        val link = SshLink(profile(), listOf(pinned), rig::approve)
        val failure = runCatching { link.connect(Cols, Rows) }.exceptionOrNull()
        assertTrue(failure is SshConnectionFailure)
        assertEquals(SshFailureKind.HostKeyChanged, (failure as SshConnectionFailure).kind)
        assertEquals(0, rig.asked.get())
        assertEquals(expectedFingerprint(hostKey), link.mismatchedKey?.fingerprint)
        link.close()
    }

    @Test
    fun userRefusalStopsTheHandshake() {
        val rig = Harness(accept = false)
        val link = SshLink(profile(), emptyList(), rig::approve)
        val failure = runCatching { link.connect(Cols, Rows) }.exceptionOrNull()
        assertTrue(failure is SshConnectionFailure)
        assertEquals(SshFailureKind.Declined, (failure as SshConnectionFailure).kind)
        assertEquals(1, rig.asked.get())
        link.close()
    }

    @Test
    fun wrongPasswordIsAuthDenied() {
        val rig = Harness()
        val link = SshLink(profile(password = "不对"), emptyList(), rig::approve)
        val failure = runCatching { link.connect(Cols, Rows) }.exceptionOrNull()
        assertTrue(failure is SshConnectionFailure)
        assertEquals(SshFailureKind.AuthDenied, (failure as SshConnectionFailure).kind)
        link.close()
    }

    @Test
    fun unreachableHostReportsNetworkProblem() {
        val rig = Harness()
        val link = SshLink(profile().copy(port = 1), emptyList(), rig::approve)
        val failure = runCatching { link.connect(Cols, Rows) }.exceptionOrNull()
        assertTrue(failure is SshConnectionFailure)
        assertEquals(SshFailureKind.Unreachable, (failure as SshConnectionFailure).kind)
        assertEquals(0, rig.asked.get())
        link.close()
    }

    @Test
    fun ptyRoundTripEchoesInput() {
        val rig = Harness()
        val link = SshLink(profile(), emptyList(), rig::approve)
        link.connect(Cols, Rows)
        val greeting = readUntil(link, "ready")
        assertTrue(greeting.contains("ready"))

        link.write("ls\r".toByteArray(Charsets.UTF_8))
        val echoed = readUntil(link, "ls")
        assertTrue(echoed.contains("ls"))

        // 转屏、键盘收起都会改行列，只要别把会话弄炸
        link.resize(Cols + 10, Rows + 4)
        link.write("st\r".toByteArray(Charsets.UTF_8))
        assertTrue(readUntil(link, "st").contains("st"))
        link.close()
    }

    /** 一直读到出现某个片段为止，读不到就抛，免得测试挂在那儿。 */
    private fun readUntil(link: SshLink, needle: String): String {
        val collected = StringBuilder()
        val buffer = ByteArray(512)
        val deadline = System.currentTimeMillis() + 8000
        while (collected.indexOf(needle) < 0 && System.currentTimeMillis() < deadline) {
            val read = link.read(buffer)
            if (read < 0) break
            collected.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return collected.toString()
    }

    /**
     * 用 SSHD 自己的编码器算一遍指纹，独立验一次 SSHJ 那条路。
     *
     * SSHD 的 `putPublicKey` 是「按字段写」，前面多带 4 字节长度；线上格式和指纹算的都是
     * 去掉长度那一截，所以要比就先剥掉。
     */
    private fun expectedFingerprint(key: java.security.KeyPair): String {
        val buffer = org.apache.sshd.common.util.buffer.ByteArrayBuffer()
        buffer.putPublicKey(key.public)
        val framed = buffer.compactData
        val blob = framed.copyOfRange(4, framed.size)
        return SshHostKey.of("127.0.0.1", port, blob).fingerprint
    }

    /** 假装是个 shell：先报个到，然后把进来的字节原样吐回去，看到 exit 就收摊。 */
    private class EchoCommand : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var callback: ExitCallback? = null
        private var pump: Thread? = null

        override fun setInputStream(stream: InputStream) {
            input = stream
        }

        override fun setOutputStream(stream: OutputStream) {
            output = stream
        }

        override fun setErrorStream(stream: OutputStream) = Unit

        override fun setExitCallback(exitCallback: ExitCallback?) {
            callback = exitCallback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            val from = input ?: return
            val to = output ?: return
            to.write("aa-test shell ready\r\n".toByteArray(Charsets.UTF_8))
            to.flush()
            pump = Thread {
                val buffer = ByteArray(256)
                while (true) {
                    val read = runCatching { from.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    val chunk = buffer.copyOfRange(0, read)
                    runCatching {
                        to.write(chunk)
                        to.flush()
                    }
                    if (String(chunk, Charsets.UTF_8).contains("exit")) break
                }
                runCatching { callback?.onExit(0) }
            }.also { it.isDaemon = true; it.start() }
        }

        override fun destroy(channel: ChannelSession) {
            pump?.interrupt()
        }
    }



    private companion object {
        const val TestUser = "aa-test"
        const val TestPassword = "aa-test-secret"
        const val Cols = 60
        const val Rows = 18
    }
}
