package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.CloudreveConfig
import noline.zhouzhou.data.remote.CloudreveApi
import noline.zhouzhou.data.remote.CloudreveException
import noline.zhouzhou.data.remote.CloudreveIssue
import noline.zhouzhou.data.remote.isAuthFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Cloudreve 的 access_token 默认只有一个小时，v4 又没有「长期 API 密钥」这种东西，
 * 所以这里缓存令牌、快到期或收到鉴权类错误时拿账号密码重登一次。
 */
class CloudreveRepository(
    private val api: CloudreveApi,
    private val configOf: suspend () -> CloudreveConfig
) {

    private val loginMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    /** @return 贴进正文的永久直链。 */
    suspend fun uploadImage(bytes: ByteArray): String {
        val config = requireConfig()
        return withToken(config) { token ->
            // 路径上的年月目录不存在时 Cloudreve 会自己建，不用先创建。
            val uri = targetUri(LocalDateTime.now())
            api.upload(config.site, token, uri, bytes)
            api.directLink(config.site, token, uri)
        }
    }

    /** 设置页「保存并自检」：强制重登一次，把站点/账号的问题当场暴露出来。 */
    suspend fun verify(): Result<Unit> = runCatching {
        val config = requireConfig()
        loginMutex.withLock { login(config) }
        Unit
    }

    suspend fun isConfigured(): Boolean = configOf().isComplete

    private suspend fun requireConfig(): CloudreveConfig =
        configOf().also {
            if (!it.isComplete) throw CloudreveException(-1, CloudreveIssue.BadInput, "还没有配置 Cloudreve 图床")
        }

    private suspend fun <T> withToken(config: CloudreveConfig, block: suspend (String) -> T): T {
        val token = currentToken(config)
        return try {
            block(token)
        } catch (e: CloudreveException) {
            if (!e.issue.isAuthFailure) throw e
            block(loginMutex.withLock { login(config) })
        }
    }

    private suspend fun currentToken(config: CloudreveConfig): String = loginMutex.withLock {
        val cached = cachedToken
        if (cached != null && System.currentTimeMillis() < tokenExpiresAt - SafetyMarginMs) cached
        else login(config)
    }

    /** 调用方必须持有 loginMutex，避免并发上传各登一次互相顶掉。 */
    private suspend fun login(config: CloudreveConfig): String {
        val token = api.login(config.site, config.email, config.password)
        cachedToken = token.accessToken
        tokenExpiresAt = parseExpiry(token.accessExpires)
        return token.accessToken
    }

    private fun parseExpiry(raw: String): Long =
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis() + FallbackTtlMs)

    companion object {
        // 令牌卡在过期边缘上传会半途失败，提前一分钟就重登。
        private const val SafetyMarginMs = 60_000L
        private const val FallbackTtlMs = 30 * 60_000L
        const val ImageDir = "blog"

        /** 按年月分文件夹，文件名用毫秒时间戳 —— 同一秒连传两张会互相盖掉。压缩器固定出 JPEG。 */
        fun targetUri(now: LocalDateTime): String {
            val month = now.monthValue.toString().padStart(2, '0')
            val stamp = now.toInstant(ZoneOffset.UTC).toEpochMilli()
            return "cloudreve://my/$ImageDir/${now.year}/$month/$stamp.jpg"
        }
    }
}
