package noline.zhouzhou.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.HealthOverview
import noline.zhouzhou.data.model.HealthRecord
import noline.zhouzhou.data.model.RecordInput
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.PreflightError
import noline.zhouzhou.data.repository.RecordPreflight
import noline.zhouzhou.data.repository.WriteOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthUiState(
    val loading: Boolean = false,
    val overview: HealthOverview? = null,
    val pendingCount: Int = 0,
    val configured: Boolean = false,
    /** 阻断性故障（未配密钥 / 接口关了 / 只读 / 路径不对）。 */
    val issue: NolineIssue? = null,
    val issueHint: String = "",
    val fieldErrors: List<PreflightError> = emptyList(),
    val invalidDates: List<String> = emptyList(),
    val toast: String? = null,
    val saving: Boolean = false
) {
    /** 列表按日期倒序看最新的；接口给的是升序。 */
    val records: List<HealthRecord> get() = overview?.series?.reversed() ?: emptyList()

    /** 有缓存就先画数据，故障条只在上面提示，不要把整页变成错误页。 */
    val showSetupGuide: Boolean get() = overview == null && issue != null
}

class HealthViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val cached = container.healthRepository.cachedOverview()
            _uiState.update {
                it.copy(
                    configured = container.healthRepository.isConfigured(),
                    overview = cached,
                    pendingCount = container.healthRepository.pendingOps().size,
                    loading = true
                )
            }
            refresh(silent = cached != null)
        }
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.update { it.copy(loading = true) }
            val result = container.healthRepository.refresh()
            val pending = container.healthRepository.pendingOps().size
            _uiState.update { state ->
                result.fold(
                    onSuccess = { overview ->
                        state.copy(
                            loading = false,
                            configured = true,
                            overview = overview,
                            issue = null,
                            issueHint = "",
                            pendingCount = pending
                        )
                    },
                    onFailure = { e ->
                        state.copy(
                            loading = false,
                            issue = (e as? NolineException)?.issue ?: NolineIssue.Unknown,
                            issueHint = e.message ?: "读不到健康数据",
                            pendingCount = pending
                        )
                    }
                )
            }
        }
    }

    /** @return false 表示预检没过，录入面板保持打开。 */
    fun save(date: String, weightText: String, fatText: String, note: String): Boolean {
        val errors = RecordPreflight.validate(date, weightText, fatText, note)
        if (errors.any { it.blocking }) {
            _uiState.update { it.copy(fieldErrors = errors) }
            return false
        }
        // 越界的体脂服务器会静默丢弃，已经提示过了，这里干脆不发送该字段。
        val fat = fatText.trim().toDoubleOrNull()?.takeIf { it in 1.0..70.0 }
        val input = RecordInput(
            date = date,
            weight = weightText.trim().toDouble(),
            fat = fat,
            note = note.trim().take(200).ifBlank { null }
        )
        _uiState.update { it.copy(fieldErrors = emptyList(), saving = true) }
        viewModelScope.launch {
            apply(container.healthRepository.saveRecord(input))
            _uiState.update { it.copy(saving = false) }
        }
        return true
    }

    fun delete(date: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            apply(container.healthRepository.deleteRecord(date), verb = "已删除")
            _uiState.update { it.copy(saving = false) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            apply(container.healthRepository.flushAndSync())
            _uiState.update { it.copy(loading = false) }
        }
    }

    private suspend fun apply(outcome: WriteOutcome, verb: String = "已记录") {
        val pending = container.healthRepository.pendingOps().size
        when (outcome) {
            is WriteOutcome.Saved -> _uiState.update {
                it.copy(
                    overview = container.healthRepository.cachedOverview() ?: it.overview,
                    pendingCount = pending,
                    invalidDates = outcome.invalid,
                    toast = if (outcome.invalid.isEmpty()) verb
                            else "有 ${outcome.invalid.size} 条日期被服务器退回"
                )
            }
            is WriteOutcome.Queued -> _uiState.update {
                it.copy(
                    pendingCount = pending,
                    toast = "现在没网，已经存在手机里，联网后自动补交"
                )
            }
            is WriteOutcome.Rejected -> _uiState.update {
                it.copy(
                    pendingCount = pending,
                    issue = outcome.issue,
                    issueHint = outcome.message,
                    invalidDates = outcome.invalid,
                    toast = outcome.message
                )
            }
        }
    }

    fun consumeToast() = _uiState.update { it.copy(toast = null) }
}
