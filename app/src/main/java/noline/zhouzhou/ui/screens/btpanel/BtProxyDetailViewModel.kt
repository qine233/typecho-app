package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtProxyEngine
import noline.zhouzhou.data.model.BtReverseProxyEntry
import noline.zhouzhou.data.model.BtReverseProxyForm
import noline.zhouzhou.data.model.BtReverseProxyLog
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.data.repository.BtProxyDetail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BtProxyDetailUiState(
    val engine: BtProxyEngine = BtProxyEngine.Nginx,
    val busy: Boolean = false,
    val detail: BtProxyDetail? = null,
    val log: BtReverseProxyLog? = null,
    val loadingLog: Boolean = false,
    val failure: BtFailure? = null,
    val note: String = ""
)

/**
 * 单个反向代理站点的详情页：规则、全局设置、域名三块一起读，谁读不到谁留一句。
 *
 * 这一页上的开关全是「一站一份」：gzip、websocket、缓存、日志去向都作用在整个站点，
 * 不是某条规则。面板网页上的标签页也是这么分的，所以这里跟着分。
 */
class BtProxyDetailViewModel(
    container: AppContainer,
    private val entry: BtReverseProxyEntry
) : ViewModel() {

    private val repository = container.btProxyRepository

    private val _uiState = MutableStateFlow(BtProxyDetailUiState())
    val uiState: StateFlow<BtProxyDetailUiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val engine = repository.engine()
            val result = repository.detail(entry)
            _uiState.update {
                it.copy(
                    busy = false,
                    engine = engine,
                    detail = result.getOrNull() ?: it.detail,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

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
            if (result.isSuccess) refresh()
        }
    }

    fun addRule(form: BtReverseProxyForm) {
        val problem = form.problem()
        if (problem != null) {
            _uiState.update {
                it.copy(failure = BtFailure("表单还差一点", problem, "同一个站点下 `proxy_path` 不能重复，路径要以 / 开头"))
            }
            return
        }
        write("加一条规则") { repository.addRule(entry.name, form.copy(domains = entry.name)) }
    }

    fun setRemark(remark: String) = write("改备注") { repository.setRemark(entry, remark) }

    fun setCache(enabled: Boolean, expires: String) =
        write(if (enabled) "开缓存" else "关缓存") { repository.setCache(entry, enabled, expires) }

    fun setGzip(enabled: Boolean, minLength: String, compLevel: String, types: String) =
        write(if (enabled) "开 gzip" else "关 gzip") {
            repository.setGzip(entry, enabled, minLength, compLevel, types)
        }

    fun setWebsocket(enabled: Boolean) =
        write(if (enabled) "开 websocket" else "关 websocket") { repository.setWebsocket(entry, enabled) }

    fun setLog(logType: String, logPath: String) = write("改日志去向") {
        repository.setLog(entry, logType, logPath)
    }

    fun clearCache() = write("清缓存") { repository.clearCache(entry) }

    /** 访问日志单独一口：正文在 `data.msg`，大小在 `data.size`，不跟着详情一起读。 */
    fun loadLog() {
        _uiState.update { it.copy(loadingLog = true) }
        viewModelScope.launch {
            val result = repository.logs(entry)
            _uiState.update {
                it.copy(
                    loadingLog = false,
                    log = result.getOrNull() ?: it.log,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
