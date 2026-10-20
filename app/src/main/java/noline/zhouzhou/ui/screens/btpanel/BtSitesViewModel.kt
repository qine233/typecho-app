package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtSite
import noline.zhouzhou.data.model.BtSiteOptions
import noline.zhouzhou.data.repository.BtFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一页 30 条和面板列表一样；面板的站点数经常上百，一次拉完既慢又没必要。 */
private const val PageLimit = 30

data class BtSitesUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val loadingMore: Boolean = false,
    val sites: List<BtSite> = emptyList(),
    val total: Int? = null,
    val keyword: String = "",
    val options: BtSiteOptions? = null,
    val failure: BtFailure? = null,
    val note: String = ""
) {
    val hasMore: Boolean
        get() = total?.let { sites.size < it } ?: (sites.size >= PageLimit)
}

/**
 * 建站表单的校验。域名这一格面板只吃「裸主机名」，
 * 人从浏览器地址栏复制过来时大概率带着 `https://` 和尾部 `/`，提前拆掉比让面板回一句强。
 */
object BtNewSiteForm {
    /** 返回 null 表示可以发；否则这条就是给界面显示的缘故。 */
    fun domainError(raw: String): String? {
        val domain = raw.trim()
        return when {
            domain.isBlank() -> "要填一个域名或 IP"
            domain.startsWith("http://") || domain.startsWith("https://") -> "只填主机名，别带 http:// 前缀"
            domain.contains("/") -> "只填主机名，别带路径"
            domain.contains(":") -> "端口在下一步单独填，这一格只要域名"
            domain.contains(" ") -> "域名里有空格"
            !domain.matches(Regex("""^[A-Za-z0-9._~-]+$""")) -> "域名里有面板不认的字符"
            else -> null
        }
    }

    fun cleanDomain(raw: String): String = raw.trim().lowercase()
}

class BtSitesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btSiteRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtSitesUiState())
    val uiState: StateFlow<BtSitesUiState> = _uiState.asStateFlow()

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
        loadOptions()
    }

    /** 下一页接在已有行后面；面板那边总数是准的，本地只做累加。 */
    fun loadMore() {
        if (_uiState.value.loadingMore || !_uiState.value.hasMore) return
        page += 1
        load(keepRows = true, more = true)
    }

    private fun load(keepRows: Boolean, more: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = !more,
                    loadingMore = more,
                    failure = if (more) it.failure else null
                )
            }
            val result = repository.sites(_uiState.value.keyword.trim(), page = page, limit = PageLimit)
            _uiState.update {
                val fresh = result.getOrNull()
                val rows = fresh?.rows.orEmpty()
                it.copy(
                    busy = false,
                    loadingMore = false,
                    sites = if (keepRows) it.sites + rows else rows,
                    total = fresh?.total ?: if (keepRows) it.total else null,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                        ?: if (keepRows) it.failure else null
                )
            }
        }
    }

    private fun loadOptions() {
        viewModelScope.launch {
            val result = repository.options()
            _uiState.update { it.copy(options = result.getOrNull() ?: it.options) }
        }
    }

    /**
     * 写操作串行，和面板那边一样：一次只该有一个人在点。
     * `reloadsList` 的动手完之后重取列表——启停、建站、删站这些不改回来人就看不见实际状态。
     */
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

    fun createSite(domain: String, phpVersion: String, remark: String, typeId: String, withDatabase: Boolean, withFtp: Boolean) {
        val error = BtNewSiteForm.domainError(domain)
        if (error != null) {
            _uiState.update { it.copy(failure = BtFailure("域名这格先修一下", error, "面板只吃裸主机名，比如 sub.example.com")) }
            return
        }
        if (phpVersion.isBlank()) {
            _uiState.update { it.copy(failure = BtFailure("没选运行环境", "要选一个 PHP 版本，或者选「纯静态」", "纯静态在列表里就是 version 00")) }
            return
        }
        write("建 ${BtNewSiteForm.cleanDomain(domain)}", reloadsList = true) {
            repository.addSite(
                domain = BtNewSiteForm.cleanDomain(domain),
                phpVersion = phpVersion,
                remark = remark.trim(),
                typeId = typeId.ifBlank { "0" },
                withDatabase = withDatabase,
                withFtp = withFtp
            )
        }
    }

    /** 停用/启用。面板那句「站点已停用/已启用」原样带回来，列表随后重取。 */
    fun setRunning(site: BtSite, running: Boolean) =
        write(if (running) "启用" else "停用", reloadsList = true) { repository.setRunning(site, running) }

    fun deleteSite(site: BtSite) =
        write("删除 ${site.name}", reloadsList = true) { repository.deleteSite(site) }

    fun backup(site: BtSite) = write("备份 ${site.name}") { repository.backup(site) }

    fun setRemark(site: BtSite, remark: String) =
        write("改备注", reloadsList = true) { repository.setRemark(site, remark) }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
