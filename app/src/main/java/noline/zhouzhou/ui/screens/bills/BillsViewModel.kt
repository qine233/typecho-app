package noline.zhouzhou.ui.screens.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BillAggregate
import noline.zhouzhou.data.model.BillBudget
import noline.zhouzhou.data.model.BillBudgetStatus
import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.BillRecord
import noline.zhouzhou.data.model.BillsOverview
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.BillPreflight
import noline.zhouzhou.data.repository.PendingBill
import noline.zhouzhou.data.repository.PreflightError
import noline.zhouzhou.data.repository.TitleHint
import noline.zhouzhou.data.repository.WriteOutcome
import noline.zhouzhou.data.repository.billDelta
import noline.zhouzhou.data.repository.unsentAdds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一行流水，加上「这条还没到服务器」的标记。 */
data class BillRowUi(
    val record: BillRecord,
    val pending: Boolean,
    /** 待提交的新增只有本地 uid，删除时按它从队列里抹掉，不用碰服务器。 */
    val localRef: String
)

data class BillDayUi(val date: String, val amount: Double, val rows: List<BillRowUi>)

/** 展开或按分类筛选后拉回来的明细。 */
data class BillDetailUi(
    val days: List<BillDayUi> = emptyList(),
    val amount: Double = 0.0,
    val total: Int = 0,
    val caption: String = ""
)

data class BillsUiState(
    val loading: Boolean = false,
    val overview: BillsOverview? = null,
    val pending: List<PendingBill> = emptyList(),
    val configured: Boolean = false,
    val issue: NolineIssue? = null,
    val issueHint: String = "",
    val fieldErrors: List<noline.zhouzhou.data.repository.PreflightError> = emptyList(),
    /** 记账的 invalid 是**品名**，别套体重那套「日期被退回」的话。 */
    val invalidTitles: List<String> = emptyList(),
    val toast: String? = null,
    val saving: Boolean = false,
    val hints: List<TitleHint> = emptyList(),
    val filterKind: String = "",
    val expanded: Boolean = false,
    val detail: BillDetailUi? = null,
    val detailLoading: Boolean = false,
    /** 预算进度是本机的数，不进队列；没设预算时它的 active 为 false。 */
    val budgetStatus: BillBudgetStatus = BillBudgetStatus()
) {
    val pendingCount: Int get() = pending.size
    val showSetupGuide: Boolean get() = overview == null && issue != null

    /** 分类筛选和「展开全部」用的是同一份 detail，只是取法不同。 */
    val showDetail: Boolean get() = expanded || filterKind.isNotBlank()
}

class BillsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(BillsUiState())
    val uiState: StateFlow<BillsUiState> = _uiState.asStateFlow()

    init {
        load()
        watchConfig()
    }

    private fun watchConfig() {
        viewModelScope.launch {
            combine(
                container.settingsRepository.nolineApiKey,
                container.settingsRepository.nolineUrlForm,
                container.settingsRepository.siteConfig
            ) { key, form, site -> Triple(key, form, site.siteUrl) }
                .drop(1)
                .distinctUntilChanged()
                .collect { load() }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val cached = container.billsRepository.cachedOverview()
            _uiState.update {
                it.copy(
                    configured = container.billsRepository.isConfigured(),
                    overview = cached,
                    pending = container.billsRepository.pendingOps(),
                    hints = container.billsRepository.titleHints(),
                    budgetStatus = container.billsRepository.currentBudgetStatus(),
                    loading = true
                )
            }
            refresh(silent = cached != null)
        }
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.update { it.copy(loading = true) }
            val result = container.billsRepository.refresh()
            val budget = container.billsRepository.currentBudgetStatus()
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            loading = false, configured = true, overview = it,
                            issue = null, issueHint = "",
                            pending = container.billsRepository.pendingOps(),
                            budgetStatus = budget
                        )
                    },
                    onFailure = { e ->
                        state.copy(
                            loading = false,
                            issue = (e as? NolineException)?.issue ?: NolineIssue.Unknown,
                            issueHint = e.message ?: "读不到记账数据",
                            pending = container.billsRepository.pendingOps(),
                            budgetStatus = budget
                        )
                    }
                )
            }
        }
    }

    // ── 展示 ──

    /**
     * 概览里的按天分组 + 队列里还没上传的新增。
     * 待提交的修改/删除只在对应行上打个标记——它们的内容还是以服务器为准。
     */
    fun dayGroups(): List<BillDayUi> {
        val state = _uiState.value
        val overview = state.overview ?: return unsentOnly()
        val pendingEdits = state.pending.mapNotNull { it.serverId.takeIf { id -> id.isNotBlank() } }.toSet()

        val groups = overview.byDate.map { group ->
            BillDayUi(
                date = group.date,
                amount = group.amount,
                rows = group.items.map { row ->
                    BillRowUi(
                        record = row,
                        pending = row.id in pendingEdits,
                        localRef = row.id
                    )
                }
            )
        }
        return (unsentOnly() + groups).sortedByDescending { it.date }
    }

    /** 明细（展开或筛选）里也要看得见待提交的新增，否则用户以为没记上。 */
    fun displayDays(detail: BillDetailUi): List<BillDayUi> {
        val unsent = unsentOnly()
        if (unsent.isEmpty() || _uiState.value.filterKind.isNotBlank()) return detail.days
        return (unsent + detail.days).sortedByDescending { it.date }
    }

    private fun unsentOnly(): List<BillDayUi> {
        val unsent = _uiState.value.pending.unsentAdds()
        if (unsent.isEmpty()) return emptyList()
        return unsent
            .map { BillRowUi(it, pending = true, localRef = it.id) }
            .groupBy { it.record.date }
            .map { (date, rows) -> BillDayUi(date, rows.sumOf { it.record.amount }, rows) }
    }

    // ── 写入 ──

    /**
     * 批量新增。面板已经把每条都预检过了，这里再兜一次，
     * 因为 POST 永远新增：按钮防连点靠 saving，脏数据靠预检。
     */
    fun addBills(inputs: List<BillInput>): Boolean {
        if (inputs.isEmpty()) return false
        if (inputs.any { BillPreflight.validate(it.title, it.amount.toString(), it.date).any { e -> e.blocking } }) {
            return false
        }
        _uiState.update { it.copy(fieldErrors = emptyList(), saving = true) }
        viewModelScope.launch {
            apply(container.billsRepository.addBills(inputs), verb = "已记下 ${inputs.size} 笔")
            _uiState.update { it.copy(saving = false) }
        }
        return true
    }

    fun updateBill(record: BillRecord, date: String, title: String, amountText: String, kind: String, shop: String, note: String): Boolean {
        val errors = BillPreflight.validate(title, amountText, date)
        if (errors.any { it.blocking }) {
            _uiState.update { it.copy(fieldErrors = errors) }
            return false
        }
        val update = BillPreflight.toUpdate(record, date, title, amountText, kind, shop, note)
        _uiState.update { it.copy(fieldErrors = emptyList(), saving = true) }
        viewModelScope.launch {
            apply(container.billsRepository.updateBill(record.id, update), verb = "改好了")
            _uiState.update { it.copy(saving = false) }
        }
        return true
    }

    /** ref 可能是服务器 id，也可能是那条还没上传的新增的本地 uid。 */
    fun deleteBill(ref: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            apply(container.billsRepository.deleteBill(ref), verb = "已删除")
            _uiState.update { it.copy(saving = false) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            apply(container.billsRepository.flushAndSync())
            _uiState.update { it.copy(loading = false) }
        }
    }

    // ── 明细 ──

    /** 点分类 chip：空串表示取消筛选，回到概览那 12 天。 */
    fun setFilter(kind: String) {
        _uiState.update { it.copy(filterKind = kind, detail = null) }
        if (kind.isNotBlank()) loadDetail()
    }

    fun toggleExpanded() {
        val next = !_uiState.value.expanded
        _uiState.update { it.copy(expanded = next, detail = if (next) it.detail else null) }
        if (next) loadDetail()
    }

    /**
     * 拉明细。合计直接读响应里的 `amount`，所以取整段时把 limit 放到 0——
     * 默认的 50 是「区间尾部 50 条」，用它算出来的合计会少。
     */
    private fun loadDetail() {
        val state = _uiState.value
        val span = state.overview?.span
        val kind = state.filterKind
        val from = if (kind.isNotBlank()) span?.monthStart.orEmpty() else ""
        val to = if (kind.isNotBlank()) span?.monthEnd.orEmpty() else ""
        viewModelScope.launch {
            _uiState.update { it.copy(detailLoading = true) }
            val result = container.billsRepository.records(limit = 0, from = from, to = to, kind = kind)
            _uiState.update { s ->
                result.fold(
                    onSuccess = { page ->
                        // 明细顺手喂给品名候选，比概览那 120 条全。
                        val pendingEdits = s.pending
                            .mapNotNull { it.serverId.takeIf { id -> id.isNotBlank() } }.toSet()
                        val rows = page.records.asReversed().map { row ->
                            BillRowUi(row, row.id in pendingEdits, row.id)
                        }
                        val caption = if (kind.isNotBlank()) {
                            val month = span?.monthStart?.take(7).orEmpty()
                            "${month.take(4)} 年 ${month.takeLast(2).trimStart('0')} 月 · ${BillKind.label(kind)}"
                        } else "全部流水"
                        s.copy(
                            detailLoading = false,
                            detail = BillDetailUi(groupRows(rows), page.amount, page.total, caption)
                        )
                    },
                    onFailure = { e ->
                        s.copy(
                            detailLoading = false,
                            detail = null,
                            issue = (e as? NolineException)?.issue ?: NolineIssue.Unknown,
                            issueHint = e.message ?: "读不到明细"
                        )
                    }
                )
            }
        }
    }

    private fun groupRows(rows: List<BillRowUi>): List<BillDayUi> {
        val byDate = rows.groupBy { it.record.date }
        return byDate.entries.sortedByDescending { it.key }.map { (date, list) ->
            BillDayUi(date, list.sumOf { it.record.amount }, list)
        }
    }

    // ── 结果落地 ──

    private suspend fun apply(outcome: WriteOutcome, verb: String = "已记下") {
        val pending = container.billsRepository.pendingOps()
        val hints = container.billsRepository.titleHints()
        val budget = container.billsRepository.currentBudgetStatus()
        when (outcome) {
            is WriteOutcome.Saved -> _uiState.update {
                it.copy(
                    overview = container.billsRepository.cachedOverview() ?: it.overview,
                    pending = pending,
                    hints = hints,
                    budgetStatus = budget,
                    invalidTitles = outcome.invalid,
                    toast = if (outcome.invalid.isEmpty()) verb
                            else "有 ${outcome.invalid.size} 条没记上：${outcome.invalid.joinToString("、")}"
                )
            }
            is WriteOutcome.Queued -> _uiState.update {
                it.copy(
                    pending = pending,
                    hints = hints,
                    budgetStatus = budget,
                    toast = "现在没网，先存在手机里，联网后自动补交"
                )
            }
            is WriteOutcome.Rejected -> _uiState.update {
                it.copy(
                    pending = pending,
                    hints = hints,
                    budgetStatus = budget,
                    issue = outcome.issue,
                    issueHint = outcome.message,
                    invalidTitles = outcome.invalid,
                    toast = outcome.message
                )
            }
        }
        container.refreshStatusNotice()
    }

    // ── 预算 ──

    /** 预算只存本机：接口没有预算端点，它不参与同步，也就不会有「待同步的预算」。 */
    fun saveBudget(budget: BillBudget) {
        viewModelScope.launch {
            container.billsRepository.saveBudget(budget)
            _uiState.update { it.copy(budgetStatus = container.billsRepository.currentBudgetStatus()) }
            container.refreshStatusNotice()
        }
    }

    fun consumeToast() = _uiState.update { it.copy(toast = null) }
}
