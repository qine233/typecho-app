package noline.zhouzhou.data.repository

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import noline.zhouzhou.data.model.BtFtpLogLine
import noline.zhouzhou.data.model.BtFtpUser
import noline.zhouzhou.data.model.BtSiteType
import noline.zhouzhou.data.model.btFtpLogLines
import noline.zhouzhou.data.model.btFtpUsers
import noline.zhouzhou.data.model.btItems
import noline.zhouzhou.data.model.btNamedOptions
import noline.zhouzhou.data.model.btText
import noline.zhouzhou.data.model.btTotal
import noline.zhouzhou.data.remote.BtFtpApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** FTP 账户列表一页。 */
data class BtFtpPage(val rows: List<BtFtpUser>, val total: Int?)

/**
 * 一个账户的日志。`recording` 是面板那边「记录 FTP 日志」这个开关的当前状态原话
 * （实测这台是 `stop`），界面按它翻译成人话而不是自己猜。
 */
data class BtFtpLogs(
    val login: List<BtFtpLogLine>,
    val action: List<BtFtpLogLine>,
    val loginTotal: Int?,
    val actionTotal: Int?,
    val recording: String,
    val failures: List<BtSourceFailure>
) {
    val isEmpty: Boolean get() = login.isEmpty() && action.isEmpty()
}

/**
 * FTP 模块的仓库层。
 *
 * 和数据库那层同两条规矩：关键词只能本地过滤（`getData` 的 `search.value` 对 `ftps` 同样是摆设），
 * 以及**密码一个字段都不接**——`getData table=ftps` 每行都带明文 `password`，
 * 翻成模型时丢掉，回显里也就没有它。
 *
 * 第三条是这一层特有的：限速和容量写完**读不回来**（`ModifyFtpUserAccess` 回「修改成功」，
 * 但 `ftps` 行里的 `quota` 不跟着变，也没有查询口），所以 [setAccess] 的返回值里
 * 明确带着这句提醒，界面上照抄，不报「现在是 1MB」。
 */
class BtFtpRepository(private val panel: BtPanelRepository) {

    private suspend fun client(): BtFtpApi = panel.ftpClient()

    fun describeFailure(error: Throwable): BtFailure = panel.describeFailure(error)

    suspend fun users(keyword: String, page: Int = 1, limit: Int = PageLimit): Result<BtFtpPage> = runCatching {
        val api = client()
        val word = keyword.trim()
        if (word.isBlank()) {
            val reply = api.users(page, limit)
            BtFtpPage(btFtpUsers(reply.root), btTotal(reply.root))
        } else {
            val reply = api.users(1, ScanLimit)
            val all = btFtpUsers(reply.root).filter { it.matches(word) }
            val from = (page - 1) * limit
            BtFtpPage(all.drop(from).take(limit), all.size)
        }
    }

    suspend fun types(): Result<List<BtSiteType>> = runCatching { btNamedOptions(client().types()) }

    /**
     * 登录日志 + 操作日志 + 记录开关，三路并发。
     *
     * 这台正式环境的 FTP 日志是关着的，所以两条日志口实测都回空数组——
     * 空是**数据**不是失败，界面要写「没有记录」而不是「读不到」。
     */
    suspend fun logs(username: String, page: Int = 1, limit: Int = PageLimit): Result<BtFtpLogs> = runCatching {
        val api = client()
        coroutineScope {
            val login = async { runCatching { api.loginLogs(username, page, limit) } }
            val action = async { runCatching { api.actionLogs(username, page, limit) } }
            val state = async { runCatching { api.logsState() } }
            val failed = mutableListOf<BtSourceFailure>()
            val loginReply = login.await().getOrNull().also {
                if (it == null) failed += BtSourceFailure("登录日志", describeFailure(login.await().error()))
            }
            val actionReply = action.await().getOrNull().also {
                if (it == null) failed += BtSourceFailure("操作日志", describeFailure(action.await().error()))
            }
            BtFtpLogs(
                login = loginReply?.root?.let(::btFtpLogLines) ?: emptyList(),
                action = actionReply?.root?.let(::btFtpLogLines) ?: emptyList(),
                loginTotal = loginReply?.root?.let(::btTotal),
                actionTotal = actionReply?.root?.let(::btTotal),
                recording = state.await().getOrNull().orEmpty(),
                failures = failed
            )
        }
    }

    /** 只在已经确认失败的 Result 上调：异常给不出就用一句人话顶上，别让 [describeFailure] 收到 null。 */
    private fun Result<*>.error(): Throwable = exceptionOrNull() ?: IllegalStateException("面板这一格没给数据")

    // ── 写 ──

    suspend fun addUser(
        username: String,
        password: String,
        path: String,
        remark: String
    ): Result<String> = runCatching { client().addUser(username, password, path, remark) }

    suspend fun setRunning(user: BtFtpUser, running: Boolean): Result<String> = runCatching {
        client().setStatus(user.id, user.name, running)
    }

    suspend fun setPassword(user: BtFtpUser, newPassword: String): Result<String> = runCatching {
        client().setPassword(user.id, user.name, newPassword)
    }

    /**
     * 批量改密码。面板回的是裸数组、一个人报一个成没成，所以这里逐个数 `status:true`，
     * 有一句没成就不说「都改好了」。
     */
    suspend fun batchSetPasswords(users: List<BtFtpUser>, newPassword: String): Result<String> = runCatching {
        require(users.isNotEmpty()) { "没选账户" }
        val root = client().batchSetPasswords(
            users.map { Triple(it.id, it.name, newPassword) }
        )
        val items = btItems(root)
        val failedNames = items.filter {
            (it["status"] as? JsonPrimitive)?.booleanOrNull != true
        }.map { btText(it["ftp_username"]).ifBlank { "未知账户" } }
        when {
            failedNames.isEmpty() -> "${items.size.ifZero(users.size)} 个账户的密码已改"
            else -> "有 ${failedNames.size} 个没改成：${failedNames.joinToString("、")}"
        }
    }

    /** 见类注释第三条：这一格写完读不回来，所以那句提醒是返回值的一部分。 */
    suspend fun setAccess(
        user: BtFtpUser,
        downloadBandwidth: String,
        uploadBandwidth: String,
        maxSize: String
    ): Result<String> = runCatching {
        val said = client().setAccess(user.name, downloadBandwidth, uploadBandwidth, maxSize)
        "$said（这一格面板不给读回的口，改完的实际值只能到面板网页上看）"
    }

    suspend fun deleteUser(user: BtFtpUser): Result<String> = runCatching {
        client().deleteUser(user.id, user.name)
    }

    private fun BtFtpUser.matches(keyword: String): Boolean =
        listOf(name, path, remark).any { it.contains(keyword, ignoreCase = true) }

    private fun Int.ifZero(fallback: Int): Int = if (this > 0) this else fallback

    companion object {
        private const val PageLimit = 30
        private const val ScanLimit = 500
    }
}
