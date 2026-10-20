package noline.zhouzhou.data.remote

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import noline.zhouzhou.data.model.SshAuthKind
import noline.zhouzhou.data.model.SshConnectionFailure
import noline.zhouzhou.data.model.SshFailureKind
import noline.zhouzhou.data.model.SshHostKey
import noline.zhouzhou.data.model.SshProfile
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.PublicKey

/**
 * 一次 SSH 会话的管子：握手 + 认证 + PTY 交互 shell + 裸字节读写 + 窗口尺寸。
 *
 * 全是阻塞调用，故意不掺协程——上层拿一条 IO 线程跑读循环就够了，
 * 也方便单测直接对着真 SSH 服务器按顺序钉。
 */
class SshLink(
    private val profile: SshProfile,
    private val pins: List<SshHostKey>,
    private val approval: (SshHostKey) -> Boolean,
    private val keyDir: File? = null
) {
    /** 第一次见面用户点了「信」，这把钥匙得让上层记下来。 */
    var acceptedKey: SshHostKey? = null
        private set

    /** 和已固定的不一致时的那一把，界面上要说「服务器换了密钥」。 */
    var mismatchedKey: SshHostKey? = null
        private set

    /** 用户在指纹弹窗上点了「不信」。 */
    var declined: Boolean = false
        private set

    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var stdin: java.io.OutputStream? = null

    /** 卡在「读私钥」这一步时抛的都算私钥问题，别按 IOException 误报成网络不通。 */
    private var loadingKey = false

    val connected: Boolean get() = shell?.isOpen == true

    fun connect(cols: Int, rows: Int) {
        SshCrypto.ensureProvider()
        val ssh = SSHClient(DefaultConfig())
        val verifier = TrustOnFirstUse(profile.host, profile.port, pins, approval)
        ssh.addHostKeyVerifier(verifier)
        ssh.connectTimeout = ConnectTimeoutMs
        try {
            ssh.connect(profile.host, profile.port)
            authenticate(ssh)
            val opened = ssh.startSession()
            opened.allocatePTY(profile.termType, cols, rows, 0, 0, emptyMap<PTYMode, Int>())
            val started = opened.startShell()
            client = ssh
            session = opened
            shell = started
            stdin = started.outputStream
            acceptedKey = verifier.accepted
        } catch (failure: Exception) {
            acceptedKey = verifier.accepted
            mismatchedKey = verifier.mismatched
            declined = verifier.declined
            runCatching { ssh.close() }
            throw classify(failure)
        }
    }

    private fun authenticate(ssh: SSHClient) {
        when (profile.authKind) {
            SshAuthKind.Password -> ssh.authPassword(profile.user, profile.secret.toCharArray())
            SshAuthKind.PrivateKey -> {
                loadingKey = true
                try {
                    withTempKeyFile(profile.secret) { path ->
                        val keys = if (profile.passphrase.isEmpty()) {
                            ssh.loadKeys(path)
                        } else {
                            ssh.loadKeys(path, profile.passphrase.toCharArray())
                        }
                        ssh.authPublickey(profile.user, keys)
                    }
                } finally {
                    loadingKey = false
                }
            }
        }
    }

    /** SSHJ 只认文件路径，私钥正文在内存里解出来后临时落个只有本应用可读的文件，认证完立刻删。 */
    private fun <T> withTempKeyFile(text: String, block: (String) -> T): T {
        val dir = keyDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val file = File.createTempFile("nline-ssh-", ".key", dir)
        try {
            file.writeText(if (text.endsWith("\n")) text else "$text\n")
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.setExecutable(false, false)
            return block(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    /** 服务器吐回来的原始字节，最多填满 `target`；-1 表示对端关了。 */
    fun read(target: ByteArray): Int = requireShell().inputStream.read(target)

    fun write(bytes: ByteArray) {
        val stream = stdin ?: throw closed()
        stream.write(bytes)
        stream.flush()
    }

    fun resize(cols: Int, rows: Int) {
        runCatching { requireShell().changeWindowDimensions(cols, rows, 0, 0) }
    }

    fun close() {
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { client?.close() }
        shell = null
        session = null
        client = null
        stdin = null
    }

    private fun requireShell(): Session.Shell = shell ?: throw closed()

    private fun closed() = SshConnectionFailure(
        SshFailureKind.Closed,
        "会话已经断了",
        advice = "回到终端页重新连接。"
    )

    /** 把 SSHJ 那一堆异常翻成用户看得懂的几类，别一律「连接失败」。 */
    private fun classify(failure: Exception): SshConnectionFailure {
        if (declined) return SshConnectionFailure(
            SshFailureKind.Declined,
            "你没有接受这台服务器的密钥",
            advice = "连接停在这儿了。核对过指纹之后再连，或者在设置里看看已固定的密钥。",
            cause = failure
        )
        if (mismatchedKey != null) return SshConnectionFailure(
            SshFailureKind.HostKeyChanged,
            "${profile.host} 这次的密钥和固定下来的不是同一把",
            advice = "除非服务器真重装过，否则先别连——这通常意味着有人在中间截。确认后要去设置里删掉旧的那把。",
            cause = failure
        )
        if (failure is UserAuthException) return SshConnectionFailure(
            SshFailureKind.AuthDenied,
            failure.message ?: "用户名或密码不对",
            advice = "核对登录用户名和密码；私钥登录要确认公钥已经放进 authorized_keys。",
            cause = failure
        )
        if (loadingKey && failure.likelyKeyMaterial()) return SshConnectionFailure(
            SshFailureKind.BadPrivateKey,
            failure.message ?: "私钥读不出来",
            advice = "支持 OpenSSH 新格式、PEM/PKCS8 和 PuTTY 的 PPK；带口令的要把口令填上。",
            cause = failure
        )
        if (failure.socketProblem()) return SshConnectionFailure(
            SshFailureKind.Unreachable,
            "连不上 ${profile.host}:${profile.port} — ${failure.reasonText()}",
            advice = "确认服务器的 22 端口开着、安全组放行；这里填的是 ssh 地址，不是面板的 9964。",
            cause = failure
        )
        if (failure.negotiationProblem()) return SshConnectionFailure(
            SshFailureKind.Negotiation,
            failure.reasonText(),
            advice = "服务器的 SSH 版本太旧，或者只开了很窄的一组算法。",
            cause = failure
        )
        return SshConnectionFailure(
            SshFailureKind.Unknown,
            failure.reasonText(),
            advice = "再试一次，还是不行就看具体报错。",
            cause = failure
        )
    }

    /** 只在第一次见面（或换过密钥）时被握手线程回调。 */
    private class TrustOnFirstUse(
        private val host: String,
        private val port: Int,
        private val pins: List<SshHostKey>,
        private val approval: (SshHostKey) -> Boolean
    ) : HostKeyVerifier {
        var accepted: SshHostKey? = null
        var mismatched: SshHostKey? = null
        var declined: Boolean = false

        override fun verify(hostname: String, ignoredPort: Int, key: PublicKey): Boolean {
            val candidate = SshHostKey.of(host, this.port, SshCrypto.publicKeyBlob(key))
            val known = pins.firstOrNull { it.host == candidate.host && it.port == candidate.port }
            return when {
                // 第一次见面：把指纹交给用户核，点了信才继续握手
                known == null -> {
                    val granted = approval(candidate)
                    if (granted) accepted = candidate else declined = true
                    granted
                }

                known.fingerprint == candidate.fingerprint -> true

                else -> {
                    mismatched = candidate
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            pins.filter { it.host == hostname && it.port == port }.map { it.algorithm }
    }

    private companion object {
        const val ConnectTimeoutMs = 15_000
    }
}

/** 底层那句原文别吞掉：是「连接被拒」还是「读超时」，用户和排障都靠这一行分辨。 */
private fun Exception.reasonText(): String {
    val head = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    val tail = cause?.message?.takeIf { it.isNotBlank() && it != head }
    return if (tail == null) head else "$head / $tail"
}

private fun Exception.socketProblem(): Boolean =
    this is UnknownHostException || this is java.net.ConnectException ||
        this is java.net.SocketTimeoutException || this is java.net.NoRouteToHostException ||
        this is java.net.SocketException ||
        (this is IOException && cause !is GeneralSecurityException)

private fun Exception.negotiationProblem(): Boolean {
    val text = ((message ?: "") + " " + (cause?.message ?: "")).lowercase()
    return text.contains("negotiat") || text.contains("no compatible") ||
        (this is TransportException && text.contains("algorithm"))
}

private fun Exception.likelyKeyMaterial(): Boolean =
    this is IOException || this is GeneralSecurityException ||
        this is IllegalArgumentException || this is net.schmizz.sshj.common.SSHRuntimeException
