package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtFtpUser
import noline.zhouzhou.data.model.BtNewFtpForm
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.data.repository.BtFtpLogs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PageLimit = 30

data class BtFtpUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val loadingMore: Boolean = false,
    val users: List<BtFtpUser> = emptyList(),
    val total: Int? = null,
    val keyword: String = "",
    /** 正在看日志的那个账户名；空串表示没开这一格。 */
    val logsFor: String = "",
    val logs: BtFtpLogs? = null,
    val loadingLogs: Boolean = false,
    val failure: BtFailure? = null,
    val note: String = ""
) {
    val hasMore: Boolean get() = total?.let { users.size < it } ?: (users.size >= PageLimit)
}

/**
 * FTP 管理页。
 *
 * 这一页比数据库那页多一条要写在界面上的实测结论：**备注和根目录改不了**。
 * 面板网页上点两下就能改的这两件事，接口侧六个候选名字全回「指定参数无效!」，
 * 所以行上只提供面板真收得下的手：暂停/启用、改密码、限速与容量、删除、看日志。
 */
class BtFtpViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btFtpRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtFtpUiState())
    val uiState: StateFlow<BtFtpUiState> = _uiState.asStateFlow()

    private var page = 1
    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            panel.configFlow.collect { config ->
                _uiState.update { it.copy(configured = config.isComplete) }
            }
        }
        refresh()
    }

    fun setKeyword(keyword: String) = _uiState.update { it.copy(keyword = keyword) }

    fun search() {
        page = 1
        load(keepRows = false)
    }

    fun refresh() {
        page = 1
        load(keepRows = false)
    }

    fun loadMore() {
        if (_uiState.value.loadingMore || !_uiState.value.hasMore) return
        page += 1
        load(keepRows = true, more = true)
    }

    private fun load(keepRows: Boolean, more: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(busy = !more, loadingMore = more, failure = if (more) it.failure else null)
            }
            val result = repository.users(_uiState.value.keyword.trim(), page = page, limit = PageLimit)
            _uiState.update {
                val fresh = result.getOrNull()
                val rows = fresh?.rows.orEmpty()
                it.copy(
                    busy = false,
                    loadingMore = false,
                    users = if (keepRows) it.users + rows else rows,
                    total = fresh?.total ?: if (keepRows) it.total else null,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                        ?: if (keepRows) it.failure else null
                )
            }
        }
    }

    private fun write(label: String, reloadsList: Boolean = false, block: suspend () -> Result<String>) {
        if (writeJob?.isActive == true) return
        writeJob = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, note = "") }
            val result = block()
            _uiState.update {
                it.copy(
                    busy = false,
                    note = result.getOrNull()?.let { text -> "$label：$text" }.orEmpty(),
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
            if (reloadsList && result.isSuccess) {
                page = 1
                load(keepRows = false)
            }
        }
    }

    fun addUser(name: String, password: String, path: String, remark: String) {
        val problems = listOfNotNull(
            BtNewFtpForm.nameError(name),
            BtNewFtpForm.passwordError(password),
            BtNewFtpForm.pathError(path)
        )
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(failure = BtFailure("表单还差一点", problems.joinToString("；"), "FTP 的名字和密码是面板查得最严的两格，特殊字符一律不收"))
            }
            return
        }
        write("建 ${name.trim()}", reloadsList = true) {
            repository.addUser(name.trim(), password, path.trim(), remark.trim())
        }
    }

    fun setRunning(user: BtFtpUser, running: Boolean) =
        write(if (running) "启用" else "暂停", reloadsList = true) { repository.setRunning(user, running) }

    fun setPassword(user: BtFtpUser, newPassword: String) {
        val error = BtNewFtpForm.passwordError(newPassword)
        if (error != null) {
            _uiState.update { it.copy(failure = BtFailure("新密码不合面板的规矩", error, "6–20 位，字母和数字都要有，放不了特殊字符")) }
            return
        }
        write("改 ${user.name} 的密码") { repository.setPassword(user, newPassword) }
    }

    /** 批量改密码：面板一个人报一个成没成，那句「有 N 个没改成」由仓库层拼好。 */
    fun batchSetPasswords(names: Set<String>, newPassword: String) {
        val picked = _uiState.value.users.filter { it.name in names && it.id.isNotBlank() }
        if (picked.isEmpty()) {
            _uiState.update { it.copy(failure = BtFailure("没选账户", "至少要勾一个能读出编号的账户。", "列表里没编号的行是面板少给了 id，批量这一条带不动它。")) }
            return
        }
        val error = BtNewFtpForm.passwordError(newPassword)
        if (error != null) {
            _uiState.update { it.copy(failure = BtFailure("新密码不合面板的规矩", error, "6–20 位，字母和数字都要有，放不了特殊字符")) }
            return
        }
        write("批量改密码") { repository.batchSetPasswords(picked, newPassword) }
    }

    /** 限速 + 容量。这一格写完读不回来，那句提醒在仓库层拼进了返回值。 */
    fun setAccess(user: BtFtpUser, download: String, upload: String, maxSize: String) {
        // 三格都是必填：面板这一格没有只改一项的口，留空发过去就是「不限」。
        val problems = listOfNotNull(
            BtNewFtpForm.sizeError(download, allowBlank = false),
            BtNewFtpForm.sizeError(upload, allowBlank = false),
            BtNewFtpForm.sizeError(maxSize, allowBlank = false)
        )
        if (problems.isNotEmpty()) {
            _uiState.update { it.copy(failure = BtFailure("带宽或容量这格看不懂", problems.joinToString("；"), "写 0 表示不限，或者带单位：100KB、1MB、1GB")) }
            return
        }
        write("改 ${user.name} 的限额") {
            repository.setAccess(
                user,
                BtNewFtpForm.cleanSize(download),
                BtNewFtpForm.cleanSize(upload),
                BtNewFtpForm.cleanSize(maxSize)
            )
        }
    }

    fun deleteUser(user: BtFtpUser) =
        write("删除 ${user.name}", reloadsList = true) { repository.deleteUser(user) }

    /** 打开某个账户的日志。切换账户时先把上一份清掉，别把两个账户的记录看成一份。 */
    fun openLogs(user: BtFtpUser) {
        _uiState.update { it.copy(logsFor = user.name, logs = null, loadingLogs = true) }
        viewModelScope.launch {
            val result = repository.logs(user.name)
            _uiState.update {
                it.copy(
                    loadingLogs = false,
                    logs = result.getOrNull() ?: it.logs,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun closeLogs() = _uiState.update { it.copy(logsFor = "", logs = null) }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
