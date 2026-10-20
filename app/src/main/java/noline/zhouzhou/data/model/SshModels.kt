package noline.zhouzhou.data.model

import java.security.MessageDigest
import java.util.Base64

/** SSH 的认证方式。宝塔那台机器 22 端口两种都收，密钥更稳。 */
enum class SshAuthKind { Password, PrivateKey }

/**
 * 一条 SSH 连接配置。
 *
 * `secret`（密码或私钥正文）和 `passphrase` 只在内存里是明文，落盘一律走 SecretStore，
 * 和面板密钥同一个规矩。
 */
data class SshProfile(
    val host: String = "",
    val port: Int = DefaultPort,
    val user: String = DefaultUser,
    val authKind: SshAuthKind = SshAuthKind.Password,
    val secret: String = "",
    val passphrase: String = "",
    val termType: String = "xterm-256color"
) {
    val display: String get() = "$user@$host:$port"

    /** 配齐了才谈得上连接：地址、用户、凭据三样缺一不可。 */
    val configured: Boolean get() = host.isNotBlank() && user.isNotBlank() && secret.isNotBlank()

    /**
     * 界面在按下连接之前先把话说清楚，别让人对着一个转圈的按钮猜。
     *
     * [reuseStoredSecret]：凭据留空表示沿用本机已存的那份，这时候不能把它当成没填。
     */
    fun issues(reuseStoredSecret: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        when {
            host.isBlank() -> out += "要填服务器地址"
            host.contains("://") || host.any { it.isWhitespace() } || host.contains('/') ->
                out += "地址只写主机名或 IP，不要带 http:// 和路径"
        }
        if (port !in 1..65535) out += "端口要在 1..65535"
        if (user.isBlank()) out += "要填登录用户名"
        if (secret.isBlank() && !reuseStoredSecret) {
            out += if (authKind == SshAuthKind.Password) "要填登录密码" else "要粘贴私钥内容"
        }
        return out
    }

    fun validated(reuseStoredSecret: Boolean = false): SshProfile {
        // 先擦掉头尾空白再验，否则从别处复制来的地址带个空格就被判成「带空格」。
        val trimmed = copy(host = host.trim(), user = user.trim())
        val problems = trimmed.issues(reuseStoredSecret)
        require(problems.isEmpty()) { problems.joinToString("；") }
        return trimmed
    }

    companion object {
        const val DefaultPort = 22

        /** 面板装在服务器上，ssh 上去几乎就是同一个账号，默认给 root 省一次输入。 */
        const val DefaultUser = "root"

        /** 面板地址长这样 `https://1.2.3.4:9964/`，ssh 要的是同一台机器，把协议和端口剥掉。 */
        fun hostFromPanel(site: String): String {
            val noScheme = site.trim().removePrefix("https://").removePrefix("http://")
            return noScheme.substringBefore(':').substringBefore('/').trim()
        }
    }
}

/**
 * 固定下来的服务器主机密钥。
 *
 * 指纹按 OpenSSH 的算法算：对线上格式的公钥 blob 取 SHA-256，再 base64 去掉填充，
 * 前缀 `SHA256:`。这样和服务器上 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 的输出
 * 可以直接对眼，用户不必信我们一面之词。
 */
data class SshHostKey(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String
) {
    /** 同一台服务器只认一条：换算法（比如从 rsa 升到 ed25519）算同一把钥匙要重新确认。 */
    val id: String get() = "$host:$port"

    fun line(): String = "$host|$port|$algorithm|$fingerprint"

    companion object {
        const val FingerprintPrefix = "SHA256:"

        /** `blob` 是 SSH 线上的公钥（4 字节长度 + 算法名 + 参数），和握手时用的一模一样。 */
        fun of(host: String, port: Int, blob: ByteArray): SshHostKey {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val encoded = Base64.getEncoder().encodeToString(digest).trimEnd('=')
            return SshHostKey(host, port, readAlgorithm(blob), "$FingerprintPrefix$encoded")
        }

        private fun readAlgorithm(blob: ByteArray): String {
            if (blob.size < 4) return ""
            val length = ((blob[0].toInt() and 0xFF) shl 24) or
                ((blob[1].toInt() and 0xFF) shl 16) or
                ((blob[2].toInt() and 0xFF) shl 8) or
                (blob[3].toInt() and 0xFF)
            if (length <= 0 || 4 + length > blob.size) return ""
            return String(blob, 4, length, Charsets.US_ASCII)
        }

        fun parse(line: String): SshHostKey? {
            val parts = line.trim().split('|')
            if (parts.size != 4) return null
            val port = parts[1].toIntOrNull() ?: return null
            if (parts[0].isBlank() || parts[3].isBlank()) return null
            return SshHostKey(parts[0], port, parts[2], parts[3])
        }

        /** 一行一条；老格式或空行直接忽略，不因为一条写坏的记录把整本丢了。 */
        fun fromText(text: String): List<SshHostKey> =
            text.lineSequence().mapNotNull { if (it.isBlank()) null else parse(it) }.toList()

        fun toText(keys: List<SshHostKey>): String = keys.joinToString("\n") { it.line() }
    }
}

/** 连不上时能讲清楚是哪一类问题，界面才好给下一步。 */
enum class SshFailureKind(val title: String) {
    /** 地址、端口、网络不通 */
    Unreachable("连不上服务器"),

    /** 主机密钥和固定的不一致：中间人有嫌疑，必须硬拦 */
    HostKeyChanged("服务器密钥变了"),

    /** 第一次见面时用户自己点了「不信」 */
    Declined("没有信任这台服务器"),

    /** 账号密码或私钥不对 */
    AuthDenied("登录被拒绝"),

    /** 私钥读不出来（格式、口令、受保护的密钥） */
    BadPrivateKey("私钥读不出来"),

    /** 算法协商不上 */
    Negotiation("SSH 算法协商失败"),

    /** 连上之后服务器把会话关了 */
    Closed("会话已断开"),

    /** 没见过的状况 */
    Unknown("SSH 连接失败")
}

/** 带分类的连接异常，`advice` 是给用户的下一句话。 */
class SshConnectionFailure(
    val kind: SshFailureKind,
    override val message: String,
    val advice: String = "",
    cause: Throwable? = null
) : Exception(message, cause)
