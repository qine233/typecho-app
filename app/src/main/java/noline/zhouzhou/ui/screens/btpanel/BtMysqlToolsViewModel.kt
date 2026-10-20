package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtMysqlUserForm
import noline.zhouzhou.data.repository.BtDbLogs
import noline.zhouzhou.data.repository.BtDatabaseRepository
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.data.repository.BtMysqlOverview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BtMysqlToolsUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val overview: BtMysqlOverview? = null,
    val logs: BtDbLogs? = null,
    val loadingLogs: Boolean = false,
    /** 面板之外 MySQL 自己的库清单；只有点开「MySQL 原生库」那一格才发。 */
    val nativeDatabases: List<String>? = null,
    val failure: BtFailure? = null,
    val note: String = ""
)

/**
 * MySQL 这一页：运行时读数 + 六只能动的手。
 *
 * 守护进程那一对要特别说明：面板的 `set_restart_task` **不看状态参数**，发一次就建一条
 * 每 5 分钟的 `[勿删]MySQL守护进程` 计划任务；关掉它只能把那条任务删掉
 * （实测过：带 `status=0` 发过去同样是建，不是关）。
 */
class BtMysqlToolsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btDatabaseRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtMysqlToolsUiState())
    val uiState: StateFlow<BtMysqlToolsUiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            panel.configFlow.collect { config ->
                _uiState.update { it.copy(configured = config.isComplete) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val result = repository.overview(BtDatabaseRepository.LocalSid)
            _uiState.update {
                it.copy(
                    busy = false,
                    overview = result.getOrNull() ?: it.overview,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun loadLogs() {
        if (_uiState.value.loadingLogs) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingLogs = true) }
            val result = repository.logs()
            _uiState.update {
                it.copy(
                    loadingLogs = false,
                    logs = result.getOrNull() ?: it.logs,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun loadNativeDatabases() {
        if (_uiState.value.nativeDatabases != null) return
        viewModelScope.launch {
            val result = repository.nativeDatabases(BtDatabaseRepository.LocalSid)
            _uiState.update {
                it.copy(
                    nativeDatabases = result.getOrNull() ?: it.nativeDatabases,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    private fun write(label: String, reload: Boolean = false, block: suspend () -> Result<String>) {
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
            if (reload && result.isSuccess) refresh()
        }
    }

    /**
     * 改超时四格。空着的那一格不发——面板对缺参数的处理是「保持原值」，
     * 而我们无法预知它会连带改什么（实测写 `expire_logs_days=6` 会把
     * `binlog_expire_logs_seconds` 一起重算），所以界面必须把这句话原样讲给人听。
     */
    fun saveTimeout(interactive: String, wait: String, expireDays: String, passwordLifetime: String) {
        val current = _uiState.value.overview?.timeout
        val boxes = listOf("交互超时" to interactive, "非交互超时" to wait, "Binlog 天数" to expireDays, "密码有效期" to passwordLifetime)
        val bad = boxes.firstOrNull { (_, raw) ->
            val text = raw.trim()
            text.isNotBlank() && (text.toLongOrNull() == null || text.toLong() < 0)
        }
        if (bad != null) {
            _uiState.update {
                it.copy(failure = BtFailure("「${bad.first}」不是数", "这几格只能填非负整数（秒或天），空着的表示不改。", "面板收到非数字只回一句「参数错误」，说不清是哪一格"))
            }
            return
        }
        val days = expireDays.trim().toLongOrNull()
        val binlogHint = if (days != null && days > 0 && current?.binlogExpireSeconds != null) {
            "面板会按「$days 天」把 Binlog 保留秒数一起重算（现在是 ${current.binlogExpireSeconds} 秒）。"
        } else {
            ""
        }
        write(
            "改超时" + if (binlogHint.isBlank()) "" else "（$binlogHint）",
            reload = true
        ) {
            repository.setTimeout(
                interactive = interactive.trim().ifBlank { current?.interactiveSeconds?.toString().orEmpty() },
                wait = wait.trim().ifBlank { current?.waitSeconds?.toString().orEmpty() },
                expireDays = expireDays.trim().ifBlank { current?.expireLogDays?.toString().orEmpty() },
                passwordLifetime = passwordLifetime.trim().ifBlank { current?.passwordLifetimeDays?.toString().orEmpty() }
            )
        }
    }

    fun clearBinlogs(days: String) {
        val value = days.trim().toLongOrNull()
        if (value == null || value < 0) {
            _uiState.update {
                it.copy(failure = BtFailure("保留天数得是个数", "填 0 以上：面板会清掉 N 天之前的 binlog，正在写的当前文件它自己会跳过。", "填个很大的数就是「什么都不清」，实测 99999 天回「清理完成!」而一个文件没动"))
            }
            return
        }
        write("清理 Binlog", reload = true) { repository.clearBinlogs(value.toString()) }
    }

    fun setGuard(enable: Boolean) {
        if (enable) {
            write("开启守护进程", reload = true) { repository.enableGuard() }
        } else {
            // 关掉只能删任务，而任务 id 要先从面板读回来；读不到就不发，界面上说清去哪儿删。
            val id = _uiState.value.overview?.guard?.taskId.orEmpty()
            if (id.isBlank()) {
                _uiState.update {
                    it.copy(failure = BtFailure("关不掉", "面板这条回包里没给任务编号，删不了那条守护任务。", "到面板的「计划任务」里删「[勿删]MySQL守护进程」，效果一样。"))
                }
                return
            }
            write("关闭守护进程", reload = true) { repository.disableGuard() }
        }
    }

    fun addAccount(username: String, password: String, address: String) {
        val problems = listOfNotNull(
            BtMysqlUserForm.nameError(username),
            BtMysqlUserForm.passwordError(password),
            BtMysqlUserForm.addressError(address)
        )
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(failure = BtFailure("账户这格先修一下", problems.joinToString("；"), "面板这个口只管建账户，建完到「账户与权限」那一格看它实际拿到了什么。"))
            }
            return
        }
        write("建账户 ${username.trim()}", reload = true) {
            repository.addMysqlUser(BtDatabaseRepository.LocalSid, username.trim(), password, address.trim())
        }
    }

    /** 删账户：`user@host` 里那个 host 必须是原值，同一个账户在 `%` 和 `localhost` 上是两条。 */
    fun deleteAccount(entry: String) {
        val (user, host) = repository.splitHost(entry)
        if (host.isBlank()) {
            _uiState.update {
                it.copy(failure = BtFailure("拆不出主机", "这一行面板给的是「$entry」，没有 @ 后面的主机位，删不了。", "到面板上按用户删，或者先确认这一行的来源。"))
            }
            return
        }
        write("删账户 $user@$host", reload = true) {
            repository.deleteMysqlUser(BtDatabaseRepository.LocalSid, user, host)
        }
    }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
