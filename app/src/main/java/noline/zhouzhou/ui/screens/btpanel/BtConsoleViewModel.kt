package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtCatalog
import noline.zhouzhou.data.model.BtDanger
import noline.zhouzhou.data.model.BtEndpoint
import noline.zhouzhou.data.model.BtParamSpec
import noline.zhouzhou.data.repository.BtCallResult
import noline.zhouzhou.data.repository.BtFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 文档没写、人自己补的一行参数。宝塔有不少接口靠约定传参，目录只能摆出写下来的那些。 */
data class ExtraParam(val key: String, val value: String)

data class BtConsoleUiState(
    /** 目录读没读到；没读到之前列表是空的，得说一句「随包那份目录没读出来」。 */
    val catalogReady: Boolean = false,
    val modules: List<BtCatalog.BtModule> = emptyList(),
    val endpointCount: Int = 0,
    val moduleFilter: String = "",
    val keyword: String = "",
    val shown: List<BtEndpoint> = emptyList(),
    val selected: BtEndpoint? = null,
    val values: Map<String, String> = emptyMap(),
    val extras: List<ExtraParam> = emptyList(),
    val busy: Boolean = false,
    /** 确认门开着。必填项齐了才会开——不然先问一句、人点了「执行」才打回来说缺参数，白白跳一次。 */
    val pendingConfirm: Boolean = false,
    val failure: BtFailure? = null,
    val result: BtCallResult? = null
)

/**
 * 通用调用台：整个 docs.bt.cn/api 的 329 个接口都从这一页走。
 *
 * 界面只认目录里的数据，所以面板加了新接口只要换那份 json，不用发版。
 * 危险分级和必填项检查也在这里：缺必填就不发、也不开确认门，会改/会删的才要人再过一遍。
 */
class BtConsoleViewModel(private val container: AppContainer) : ViewModel() {

    private val repository get() = container.btPanelRepository
    private val catalogStore get() = container.btCatalogStore

    private var catalog: BtCatalog = BtCatalog(emptyList())

    private val _uiState = MutableStateFlow(BtConsoleUiState())
    val uiState: StateFlow<BtConsoleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = catalogStore.load()
            catalog = loaded
            _uiState.update {
                it.copy(
                    catalogReady = loaded.endpoints.isNotEmpty(),
                    modules = loaded.modules,
                    endpointCount = loaded.endpoints.size,
                    shown = loaded.filter()
                )
            }
        }
    }

    fun setKeyword(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
        refreshList()
    }

    /** 再点一次已经选中的模块就是取消筛选。 */
    fun selectModule(title: String) {
        val next = if (_uiState.value.moduleFilter == title) "" else title
        _uiState.update { it.copy(moduleFilter = next) }
        refreshList()
    }

    fun clearKeyword() {
        _uiState.update { it.copy(keyword = "", moduleFilter = "") }
        refreshList()
    }

    private fun refreshList() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                shown = catalog.filter(state.moduleFilter, state.keyword),
                selected = null,
                values = emptyMap(),
                extras = emptyList(),
                result = null,
                pendingConfirm = false,
                failure = null
            )
        }
    }

    fun open(endpoint: BtEndpoint) {
        _uiState.update {
            it.copy(
                selected = endpoint,
                values = emptyMap(),
                extras = emptyList(),
                result = null,
                pendingConfirm = false,
                failure = null
            )
        }
    }

    fun closeEndpoint() = _uiState.update {
        it.copy(
            selected = null,
            values = emptyMap(),
            extras = emptyList(),
            result = null,
            pendingConfirm = false,
            failure = null
        )
    }

    fun setValue(name: String, value: String) = _uiState.update {
        it.copy(values = it.values + (name to value), result = null, pendingConfirm = false, failure = null)
    }

    fun addExtra() = _uiState.update { it.copy(extras = it.extras + ExtraParam("", "")) }

    fun updateExtra(index: Int, key: String, value: String) = _uiState.update { state ->
        val current = state.extras.getOrNull(index) ?: return@update state
        state.copy(extras = state.extras.toMutableList().also { it[index] = current.copy(key = key, value = value) })
    }

    fun removeExtra(index: Int) = _uiState.update { state ->
        state.copy(extras = state.extras.filterIndexed { i, _ -> i != index })
    }

    /**
     * 点「发起调用」。必填项没填齐就原地拦住——发过去也只会被面板打回来，
     * 而且面板那句「参数错误」从来不告诉你是哪个参数；更要紧的是别让人
     * 在确认弹窗上点完「执行」才发现缺东西。齐了之后只读的直发，会改/会删的开门。
     */
    fun fire() {
        val state = _uiState.value
        val endpoint = state.selected ?: return
        if (state.busy) return
        val merged = params()
        endpoint.missingParam(merged)?.let { missing ->
            _uiState.update {
                it.copy(
                    pendingConfirm = false,
                    failure = BtFailure(
                        title = "少了必填参数",
                        detail = "「${missing.n}」是面板要求必填的${typeNote(missing)}",
                        advice = missing.d.ifBlank { "填上这一项再跑；这一条在面板里是怎么来的，可以从同一模块的只读接口里查" }
                    )
                )
            }
            return
        }
        if (endpoint.danger == BtDanger.Read) launch(endpoint, merged)
        else _uiState.update { it.copy(pendingConfirm = true, failure = null) }
    }

    fun cancelConfirm() = _uiState.update { it.copy(pendingConfirm = false) }

    /** 确认门里按「执行」：参数在弹窗那会儿已经过了一遍，这里只补一次空值检查就发。 */
    fun confirmFire() {
        val endpoint = _uiState.value.selected ?: return
        _uiState.update { it.copy(pendingConfirm = false) }
        launch(endpoint, params())
    }

    private fun launch(endpoint: BtEndpoint, merged: Map<String, String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, result = null) }
            val outcome = repository.call(endpoint, merged)
            _uiState.update {
                it.copy(
                    busy = false,
                    result = outcome.getOrNull(),
                    failure = outcome.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    /** 文档参数 + 手加的行；手加的排在后面，同名以手加的为准。 */
    private fun params(): Map<String, String> {
        val state = _uiState.value
        val documented = state.selected?.params.orEmpty().map { it.n }
            .associateWith { (state.values[it] ?: "").trim() }
        val extra = state.extras
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() }
        return (documented + extra).filterValues { it.isNotBlank() }
    }

    private fun typeNote(param: BtParamSpec): String =
        if (param.t.isBlank()) "" else "（${param.t}）"

    fun dismissFailure() = _uiState.update { it.copy(failure = null) }
}
