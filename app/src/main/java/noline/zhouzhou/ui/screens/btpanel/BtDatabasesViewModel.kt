package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtDatabase
import noline.zhouzhou.data.model.BtNewDatabaseForm
import noline.zhouzhou.data.repository.BtDatabaseRepository
import noline.zhouzhou.data.repository.BtFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PageLimit = 30

data class BtDatabasesUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val loadingMore: Boolean = false,
    val databases: List<BtDatabase> = emptyList(),
    val total: Int? = null,
    val keyword: String = "",
    val failure: BtFailure? = null,
    val note: String = ""
) {
    val hasMore: Boolean
        get() = total?.let { databases.size < it } ?: (databases.size >= PageLimit)
}

/**
 * 数据库列表页。
 *
 * 关键词这一格和网站那页不同：面板的 `search.value` 对 `databases` 表是摆设（实测带任何词都回全表），
 * 所以过滤在仓库层本地做，界面上的「查」只是重新扫一遍再筛。
 */
class BtDatabasesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btDatabaseRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtDatabasesUiState())
    val uiState: StateFlow<BtDatabasesUiState> = _uiState.asStateFlow()

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
            val result = repository.databases(_uiState.value.keyword.trim(), page = page, limit = PageLimit)
            _uiState.update {
                val fresh = result.getOrNull()
                val rows = fresh?.rows.orEmpty()
                it.copy(
                    busy = false,
                    loadingMore = false,
                    databases = if (keepRows) it.databases + rows else rows,
                    total = fresh?.total ?: if (keepRows) it.total else null,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                        ?: if (keepRows) it.failure else null
                )
            }
        }
    }

    /** 和网站页一样：写操作一次只放一个，动完再重取列表。 */
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

    /**
     * 建库。四个格子的校验全在 [BtNewDatabaseForm]，界面提前挡一次、这里再挡一次：
     * 面板收到非法名字只会回一句「添加失败」，看不出是哪一格错了。
     */
    fun createDatabase(
        name: String,
        user: String,
        password: String,
        address: String,
        charset: String,
        remark: String
    ) {
        val problems = listOfNotNull(
            BtNewDatabaseForm.nameError(name),
            BtNewDatabaseForm.userError(user),
            BtNewDatabaseForm.passwordError(password),
            BtNewDatabaseForm.addressError(address)
        )
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(failure = BtFailure("表单还差一点", problems.joinToString("；"), "面板收的名字是 `字母数字下划线点中划线`，密码里不能有引号和反斜杠"))
            }
            return
        }
        write("建 ${name.trim()}", reloadsList = true) {
            repository.addDatabase(
                sid = BtDatabaseRepository.LocalSid,
                name = name.trim(),
                user = user.trim(),
                password = password,
                address = address.trim(),
                charset = charset,
                remark = remark.trim()
            )
        }
    }

    fun backup(db: BtDatabase) = write("备份 ${db.name}") { repository.backup(db) }

    fun deleteDatabase(db: BtDatabase) =
        write("删除 ${db.name}", reloadsList = true) { repository.deleteDatabase(db) }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
