package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.CloudreveConfig
import noline.zhouzhou.data.remote.CloudreveApi
import noline.zhouzhou.data.remote.CloudreveCapacity
import noline.zhouzhou.data.remote.CloudreveChunkReader
import noline.zhouzhou.data.remote.CloudreveException
import noline.zhouzhou.data.remote.CloudreveFileNode
import noline.zhouzhou.data.remote.CloudreveIssue
import noline.zhouzhou.data.remote.CloudreveListing
import noline.zhouzhou.data.remote.CloudreveShare
import noline.zhouzhou.data.remote.CloudreveSharePage
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
    suspend fun uploadImage(bytes: ByteArray, mimeType: String = "image/jpeg"): String {
        val config = requireConfig()
        return withToken(config) { token ->
            // 路径上的年月目录不存在时 Cloudreve 会自己建，不用先创建。
            val uri = targetUri(LocalDateTime.now())
            api.uploadFile(config.site, token, uri, bytes, mimeType)
            api.directLink(config.site, token, uri)
        }
    }

    /** 设置页「保存并自检」：强制重登一次，把站点/账号的问题当场暴露出来。 */
    suspend fun verify(): Result<Unit> = runCatching {
        val config = requireConfig()
        loginMutex.withLock { login(config) }
        Unit
    }

    // ── 网盘管理 ──
    // 这一组都是「用户点了才有反馈」的操作，统一包成 Result 交给界面层的错误条显示，
    // 免得每个调用点各自 try/catch。

    /**
     * Cloudreve 记账的容量。失败就吞掉 —— 这只是页面上的一行说明，不该拦住列目录。
     */
    suspend fun capacity(): CloudreveCapacity? = runCatching {
        val config = requireConfig()
        withToken(config) { api.capacity(config.site, it) }
    }.getOrNull()

    suspend fun list(dirUri: String, nextToken: String? = null): Result<CloudreveListing> =
        runCatching {
            val config = requireConfig()
            withToken(config) { api.list(config.site, it, dirUri, nextToken = nextToken) }
        }

    /** 在 dirUri 下只列子目录，给「移动到」挑目标用。 */
    suspend fun listFolders(dirUri: String): Result<List<CloudreveFileNode>> =
        list(dirUri).map { page -> page.files.filter { it.isFolder } }

    suspend fun createFolder(dirUri: String, name: String): Result<Unit> =
        runCatching {
            val config = requireConfig()
            withToken(config) { api.createFolder(config.site, it, childUri(dirUri, name)) }
        }

    suspend fun rename(node: CloudreveFileNode, newName: String): Result<Unit> = runCatching {
        val config = requireConfig()
        withToken(config) { api.rename(config.site, it, node.path, newName) }
    }

    /** 移动回来后原目录的游标就作废了，调用方重新拉第一页。 */
    suspend fun move(node: CloudreveFileNode, dstDirUri: String): Result<Unit> = runCatching {
        val config = requireConfig()
        withToken(config) { api.move(config.site, it, listOf(node.path), dstDirUri) }
    }

    /** 只丢进回收站；Cloudreve 默认保留 7 天，网站后台还能捞回来。 */
    suspend fun softDelete(node: CloudreveFileNode): Result<Unit> = runCatching {
        val config = requireConfig()
        withToken(config) { api.softDelete(config.site, it, listOf(node.path)) }
    }

    /** 给已有文件生成永久直链（`/f/<id>/<名称>`），游客可访问，贴进文章不会失效。 */
    suspend fun directLink(node: CloudreveFileNode): Result<String> = runCatching {
        val config = requireConfig()
        withToken(config) { api.directLink(config.site, it, node.path) }
    }

    /** 一次性的签名下载地址，默认一小时过期，只够交给下载器立刻取。 */
    suspend fun downloadUrl(node: CloudreveFileNode): Result<String> = runCatching {
        val config = requireConfig()
        withToken(config) { api.downloadUrl(config.site, it, node.path) }
    }

    // ── 分享 ──

    suspend fun shares(nextToken: String? = null): Result<CloudreveSharePage> = runCatching {
        val config = requireConfig()
        withToken(config) { api.listShares(config.site, it, nextToken) }
    }

    /** @return 完整分享链接（私密时后面已经拼好了密码段）。 */
    suspend fun createShare(
        node: CloudreveFileNode,
        password: String,
        expireSeconds: Int,
        remainDownloads: Int
    ): Result<String> = runCatching {
        val config = requireConfig()
        withToken(config) {
            api.createShare(config.site, it, node.path, password, expireSeconds, remainDownloads)
        }
    }

    suspend fun deleteShare(share: CloudreveShare): Result<Unit> = runCatching {
        val config = requireConfig()
        withToken(config) { api.deleteShare(config.site, it, share.id) }
    }

    /**
     * 把本地一个文件传到网盘。uri 由调用方拼好（含文件名），进度每送完一片回一次。
     */
    suspend fun uploadFile(
        uri: String,
        size: Long,
        mimeType: String,
        read: CloudreveChunkReader,
        onProgress: (Long) -> Unit = {}
    ): Result<Unit> = runCatching {
        val config = requireConfig()
        withToken(config) { api.uploadFile(config.site, it, uri, size, mimeType, read, onProgress) }
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

        /** 网盘里拼子路径：根目录是 cloudreve://my，网站给的子路径可能带尾斜杠，先抹掉。 */
        fun childUri(dirUri: String, name: String): String = dirUri.trimEnd('/') + "/" + name
    }
}
