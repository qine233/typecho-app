package noline.zhouzhou.ui.screens.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BillBudget
import noline.zhouzhou.data.model.BillBudgetStatus
import noline.zhouzhou.data.model.BillMonthReport
import noline.zhouzhou.data.model.billBudgetStatus
import noline.zhouzhou.data.model.billMonthShift
import noline.zhouzhou.data.model.previousBillMonth
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.RecordPreflight
import noline.zhouzhou.data.repository.unsentAdds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BillReportUiState(
    val month: String = "",
    /** 站点那边的「今天」落在哪个月；翻月时它是不能往前越过的界线。 */
    val todayMonth: String = "",
    val report: BillMonthReport? = null,
    val loading: Boolean = false,
    /** 请求没成功、屏上这份是以前存下来的——必须说清楚，否则会把旧月当本月。 */
    val stale: Boolean = false,
    val issue: NolineIssue? = null,
    val issueHint: String = "",
    /** 上月花了多少，只用来算涨跌；没基线（更早的月，或上月一笔没记）时是 null。 */
    val prevAmount: Double? = null,
    val budgetStatus: BillBudgetStatus = BillBudgetStatus(),
    val currency: String = "CNY",
    /** 这月的数字里含了几笔还没上传的新增。 */
    val pendingCount: Int = 0
) {
    val canGoNext: Boolean get() = month.isNotBlank() && todayMonth.isNotBlank() && month < todayMonth
    val isCurrentMonth: Boolean get() = month.isNotBlank() && month == todayMonth
}

/**
 * 月报。服务端的概览只给「本月分类 + 近 7 个月的柱子」，任意一个月的明细要靠
 * `/api/bills/records?from=&to=&limit=0` 取回来自己汇总，所以进来就是一次整月请求。
 */
class BillReportViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(BillReportUiState())
    val uiState: StateFlow<BillReportUiState> = _uiState.asStateFlow()

    /** 路由带进来的月份；同一个 ViewModel 会跟着「上一月/下一月」复用。 */
    fun open(month: String) {
        val target = month.ifBlank { RecordPreflight.today().take(7) }
        if (_uiState.value.month == target && _uiState.value.report != null) return
        load(target)
    }

    fun shift(deltaMonths: Long) {
        val current = _uiState.value.month.ifBlank { RecordPreflight.today().take(7) }
        load(billMonthShift(current, deltaMonths))
    }

    fun retry() = load(_uiState.value.month)

    fun saveBudget(budget: BillBudget) {
        viewModelScope.launch {
            container.billsRepository.saveBudget(budget)
            _uiState.update { it.copy(budgetStatus = budgetStatusFor(it.report)) }
            container.refreshStatusNotice()
        }
    }

    private fun load(month: String) {
        viewModelScope.launch {
            val overview = container.billsRepository.cachedOverview()
            val serverToday = overview?.span?.today.orEmpty()
            val todayMonth = if (serverToday.length >= 7) serverToday.take(7) else RecordPreflight.today().take(7)
            val prevMonth = previousBillMonth(month)
            // 上月的数只有概览那 7 格现成有；翻到更早的月就没有基线，涨跌画「—」。
            // 上月一根开销都没有时也不算基线，否则「↑ 全额」会让人以为暴涨。
            val prevAmount = overview?.months
                ?.firstOrNull { it.month == prevMonth && it.count > 0 }?.amount
            val cached = container.billsRepository.cachedReport(month)

            _uiState.update {
                it.copy(
                    month = month,
                    todayMonth = todayMonth,
                    report = cached,
                    stale = cached != null,
                    loading = true,
                    issue = null,
                    issueHint = "",
                    currency = overview?.currency ?: "CNY",
                    prevAmount = prevAmount,
                    budgetStatus = budgetStatusFor(cached),
                    pendingCount = pendingIn(month)
                )
            }

            val result = container.billsRepository.monthReport(month)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { report ->
                        state.copy(
                            loading = false,
                            report = report,
                            stale = false,
                            issue = null,
                            issueHint = "",
                            budgetStatus = budgetStatusFor(report),
                            pendingCount = pendingIn(month)
                        )
                    },
                    onFailure = { e ->
                        state.copy(
                            loading = false,
                            stale = state.report != null,
                            issue = (e as? NolineException)?.issue ?: NolineIssue.Unknown,
                            issueHint = e.message ?: "这个月的流水没取回来"
                        )
                    }
                )
            }
        }
    }

    private suspend fun pendingIn(month: String): Int =
        container.billsRepository.pendingOps().unsentAdds().count { it.date.take(7) == month }

    /** 预算进度按这个月实际花了多少算——预算本来就是「每月同一个数」，量哪个月都成立。 */
    private suspend fun budgetStatusFor(report: BillMonthReport?): BillBudgetStatus {
        if (report == null) return BillBudgetStatus()
        return billBudgetStatus(
            budget = container.billsRepository.budget(),
            spent = report.amount,
            kindSpent = report.kinds.associate { it.kind to it.amount }
        )
    }
}
