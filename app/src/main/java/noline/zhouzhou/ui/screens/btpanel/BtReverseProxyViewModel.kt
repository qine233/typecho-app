package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtProxyEngine
import noline.zhouzhou.data.model.BtReverseProxyEntry
import noline.zhouzhou.data.model.BtReverseProxyForm
import noline.zhouzhou.data.repository.BtFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PageLimit = 30

data class BtProxyUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val loadingMore: Boolean = false,
    val engine: BtProxyEngine = BtProxyEngine.Nginx,
    val entries: List<BtReverseProxyEntry> = emptyList(),
    val total: Int? = null,
    val keyword: String = "",
    val failure: BtFailure? = null,
    val note: String = ""
) {
    val hasMore: Boolean get() = total?.let { entries.size < it } ?: (entries.size >= PageLimit)
}

/**
 * 反向代理列表页。
 *
 * 这一页第一件事是把**引擎**摆出来：`/mod/proxy/com/<方法>/<stype>` 里那段 stype 是路径，
 * 说的是这台服务器装的 web 软件。面板没有接口能问「我装的是 nginx 还是 openresty」，
 * 切错了整块就是空的，所以这里让人自己选、选完记住，并在空列表时把这句话摆在界面上。
 */
class BtReverseProxyViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btProxyRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtProxyUiState())
    val uiState: StateFlow<BtProxyUiState> = _uiState.asStateFlow()

    private var page = 1
    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            panel.configFlow.collect { config ->
                _uiState.update { it.copy(configured = config.isComplete) }
            }
        }
        viewModelScope.launch { _uiState.update { it.copy(engine = repository.engine()) } }
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

    /** 换引擎：先存进设置，再按新引擎重读——这一块的每一条接口都带这一段路径。 */
    fun selectEngine(engine: BtProxyEngine) {
        if (engine == _uiState.value.engine) return
        _uiState.update { it.copy(engine = engine) }
        viewModelScope.launch {
            repository.saveEngine(engine)
            page = 1
            load(keepRows = false)
        }
    }

    private fun load(keepRows: Boolean, more: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(busy = !more, loadingMore = more, failure = if (more) it.failure else null)
            }
            val engine = repository.engine()
            val result = repository.entries(_uiState.value.keyword.trim(), page = page, limit = PageLimit)
            _uiState.update {
                val fresh = result.getOrNull()
                val rows = fresh?.rows.orEmpty()
                it.copy(
                    busy = false,
                    loadingMore = false,
                    engine = engine,
                    entries = if (keepRows) it.entries + rows else rows,
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

    /** 新建整站代理。表单先在这里过一遍 [BtReverseProxyForm.problem]，不合规一个字节都不发。 */
    fun create(form: BtReverseProxyForm) {
        form.problem()?.let {
            _uiState.update { fail ->
                fail.copy(failure = BtFailure("表单还差一点", it, "域名不能带泛解析，目标地址要写全 http:// 或 https:// 开头"))
            }
            return
        }
        write("新建「${form.domains.trim()}」", reloadsList = true) { repository.create(form) }
    }

    fun setRemark(entry: BtReverseProxyEntry, remark: String) =
        write("改「${entry.title}」的备注", reloadsList = true) { repository.setRemark(entry, remark) }

    fun setEnabled(entry: BtReverseProxyEntry, enabled: Boolean) =
        write(if (enabled) "启用" else "停用", reloadsList = true) { repository.setEnabled(entry, enabled) }

    fun delete(entry: BtReverseProxyEntry) =
        write("删除「${entry.title}」", reloadsList = true) { repository.delete(entry) }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
