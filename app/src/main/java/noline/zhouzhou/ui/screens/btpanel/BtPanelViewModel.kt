package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtRow
import noline.zhouzhou.data.model.BtTable
import noline.zhouzhou.data.repository.BtDashboard
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.data.remote.BtServiceOp
import noline.zhouzhou.data.repository.maskSecret
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BtPanelUiState(
    val configured: Boolean = false,
    val site: String = "",
    val keyMasked: String = "",
    val trustSelfSigned: Boolean = false,
    /** 看板和表格共用一把忙标记：面板那边一次只该有一个人在点。 */
    val busy: Boolean = false,
    val dashboard: BtDashboard? = null,
    val failure: BtFailure? = null,
    val table: BtTable = BtTable.Sites,
    val rows: List<BtRow> = emptyList(),
    val total: Int? = null,
    val keyword: String = "",
    val serviceDraft: String = "nginx",
    val note: String = "",
    val logTitle: String = "",
    val logText: String = ""
)

class BtPanelViewModel(private val container: AppContainer) : ViewModel() {

    private val repository get() = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtPanelUiState())
    val uiState: StateFlow<BtPanelUiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _uiState.update {
                    it.copy(
                        configured = config.isComplete,
                        site = config.site,
                        trustSelfSigned = config.trustSelfSigned,
                        keyMasked = if (config.key.isBlank()) "" else maskSecret(config.key)
                    )
                }
            }
        }
        // 一进页面就试一次：配好了就直接给看板，没配好就把失败摊在原地。
        refresh()
    }

    fun setServiceDraft(name: String) = _uiState.update { it.copy(serviceDraft = name) }

    fun setKeyword(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
    }

    fun selectTable(table: BtTable) {
        if (_uiState.value.table == table) return
        _uiState.update { it.copy(table = table, rows = emptyList(), total = null) }
        loadTable()
    }

    fun saveAndCheck(site: String, key: String, trustSelfSigned: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val saved = runCatching { repository.saveConfig(site, key, trustSelfSigned) }
            if (saved.isFailure) {
                _uiState.update {
                    it.copy(busy = false, failure = repository.describeFailure(saved.exceptionOrNull()!!))
                }
                return@launch
            }
            // 自检就是一次看板拉取：读到了就算通，读不到把面板原话贴出来。
            pull()
        }
    }

    fun clearConfig() {
        viewModelScope.launch {
            runCatching { repository.clearConfig() }
            _uiState.update {
                it.copy(
                    dashboard = null, rows = emptyList(), total = null,
                    failure = null, note = "已清除本机上的面板配置"
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!repository.config().isComplete) {
                _uiState.update { it.copy(busy = false, dashboard = null, failure = null) }
                return@launch
            }
            pull()
        }
    }

    /** 调用方自己判断过配置是否齐全；这里只管拉数据、把失败翻成人话。 */
    private suspend fun pull() {
        _uiState.update { it.copy(busy = true, failure = null) }
        val page = _uiState.value.table
        val dashboard = repository.dashboard()
        val table = repository.load(page, keyword = _uiState.value.keyword)
        _uiState.update {
            it.copy(
                busy = false,
                dashboard = dashboard.getOrNull() ?: it.dashboard,
                rows = table.getOrNull()?.rows ?: it.rows,
                total = table.getOrNull()?.total ?: it.total,
                failure = (dashboard.exceptionOrNull() ?: table.exceptionOrNull())
                    ?.let(repository::describeFailure)
            )
        }
    }

    fun loadTable() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val result = repository.load(_uiState.value.table, keyword = _uiState.value.keyword)
            _uiState.update {
                it.copy(
                    busy = false,
                    rows = result.getOrNull()?.rows ?: emptyList(),
                    total = result.getOrNull()?.total,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    /** 写操作串行：并发发两条 ServiceAdmin，面板那边谁先落地没人知道。 */
    private fun write(label: String, block: suspend () -> Result<String>) {
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
        }
    }

    fun runService(op: BtServiceOp) {
        val name = _uiState.value.serviceDraft.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(failure = BtFailure("没填服务名", "要先写清楚对哪个服务动手", "比如 nginx、mysql、php-fpm-82")) }
            return
        }
        write("${op.label}$name") { repository.service(name, op) }
    }

    fun reloadPanel() = write("重载面板") { repository.reloadPanel() }

    fun runTask(row: BtRow) {
        if (row.id.isBlank()) {
            _uiState.update {
                it.copy(failure = BtFailure("这一行没有编号", "面板没给 id，App 没法指定执行哪条任务", "到面板里手动执行一次"))
            }
            return
        }
        write("执行${row.title}") { repository.runTask(row.id) }
    }

    fun showLog(row: BtRow) {
        if (row.id.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val result = repository.taskLog(row.id)
            _uiState.update {
                it.copy(
                    busy = false,
                    logTitle = row.title,
                    logText = result.getOrNull().orEmpty(),
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun closeLog() = _uiState.update { it.copy(logTitle = "", logText = "") }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
