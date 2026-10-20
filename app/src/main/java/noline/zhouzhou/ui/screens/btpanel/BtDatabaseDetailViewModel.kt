package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtDatabase
import noline.zhouzhou.data.repository.BtDatabaseDetail
import noline.zhouzhou.data.repository.BtFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一个库的三个页签。面板上有六个，接口只给得出这三块数据。 */
enum class BtDbTab(val label: String) {
    Tables("大小与表"),
    Grants("授权"),
    Backups("备份")
}

data class BtDatabaseDetailUiState(
    val db: BtDatabase = BtDatabase("", "", "", "", "", "", "0", "MySQL", null, null),
    val tab: BtDbTab = BtDbTab.Tables,
    val busy: Boolean = false,
    val detail: BtDatabaseDetail? = null,
    val failure: BtFailure? = null,
    val note: String = ""
)

/**
 * 单个库的详情页。
 *
 * 进来先按列表带过来的三个字段重建这一行（`id/name/sid`），再拉详情；
 * 面板没有「按 id 查单行」的口（`find_databases_by_name_and_type` 实测对存在的库回空数组），
 * 所以详情各页签全按库名去问，重名这种面板自己不管的事这里也只能照它的口径来。
 */
class BtDatabaseDetailViewModel(
    container: AppContainer,
    private val database: BtDatabase
) : ViewModel() {

    private val repository = container.btDatabaseRepository

    private val _uiState = MutableStateFlow(BtDatabaseDetailUiState(db = database))
    val uiState: StateFlow<BtDatabaseDetailUiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null

    init {
        refresh()
    }

    fun selectTab(tab: BtDbTab) = _uiState.update { it.copy(tab = tab) }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val result = repository.detail(database)
            _uiState.update {
                it.copy(
                    busy = false,
                    detail = result.getOrNull() ?: it.detail,
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

    fun backup() = write("备份 ${database.name}", reload = true) { repository.backup(database) }

    /**
     * 删库。删成之后要退出这一页——这一行在面板上已经不成立了，
     * 留在原地只会一直显示旧数据，所以这里给调用方留一个回调位。
     */
    fun deleteDatabase(onDone: () -> Unit) {
        if (writeJob?.isActive == true) return
        writeJob = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, note = "") }
            val result = repository.deleteDatabase(database)
            _uiState.update {
                it.copy(
                    busy = false,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
            if (result.isSuccess) onDone()
        }
    }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
