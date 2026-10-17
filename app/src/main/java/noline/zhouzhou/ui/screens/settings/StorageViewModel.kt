package noline.zhouzhou.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.repository.BillQueue
import noline.zhouzhou.data.repository.DraftEntry
import noline.zhouzhou.data.repository.HealthQueue
import noline.zhouzhou.data.repository.PendingBill
import noline.zhouzhou.data.repository.PendingOp
import noline.zhouzhou.data.repository.StorageRepository
import noline.zhouzhou.data.repository.StorageUsage
import noline.zhouzhou.data.repository.WriteOutcome
import java.util.Locale

/**
 * 字节数自己格式化而不是丢给 Formatter：这里的结果要进断言和单测，
 * Formatter 的输出跟着系统 locale 变，同样 1024 字节能给出三种写法。
 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

data class StorageUiState(
    val usage: StorageUsage? = null,
    val drafts: List<DraftEntry> = emptyList(),
    val healthPending: List<PendingOp> = emptyList(),
    val billsPending: List<PendingBill> = emptyList(),
    val isBusy: Boolean = false,
    val isSyncing: Boolean = false,
    val message: String? = null
) {
    val pendingCount: Int get() = healthPending.size + billsPending.size
}

class StorageViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val usage = container.storageRepository.measure()
            val drafts = container.draftStore.listAll()
            val health = container.healthLocalStore.loadQueue().ops
            val bills = container.billsLocalStore.loadQueue().ops
            _uiState.update {
                it.copy(usage = usage, drafts = drafts, healthPending = health, billsPending = bills, isBusy = false)
            }
            // 这个页面是队列的唯一出口，重算一次就把通知一起对平
            container.statusRepository.publish()
        }
    }

    fun clear(keys: Set<String>) {
        viewModelScope.launch {
            val freed = container.storageRepository.clearCaches(keys)
            // 磁盘上的文章缓存删了，内存里那份也得跟着走，否则详情页还能读到
            if (StorageRepository.KEY_POSTS in keys) container.postCache.clear()
            _uiState.update {
                it.copy(message = if (freed > 0L) "已释放 ${formatBytes(freed)}" else "这些缓存本来就是空的")
            }
            refresh()
        }
    }

    fun discardDraft(key: String) {
        viewModelScope.launch {
            container.draftStore.discardKey(key)
            _uiState.update { it.copy(message = "已删除本地草稿") }
            refresh()
        }
    }

    /** 两条队列分别补交，各自成文，避免一个失败把另一个的结果也带没了。 */
    fun syncAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val lines = mutableListOf<String>()
            if (_uiState.value.healthPending.isNotEmpty()) {
                lines += "健康：${describe(container.healthRepository.flushAndSync())}"
            }
            if (_uiState.value.billsPending.isNotEmpty()) {
                lines += "记账：${describe(container.billsRepository.flushAndSync())}"
            }
            _uiState.update {
                it.copy(isSyncing = false, message = lines.joinToString("\n").ifBlank { "没有待同步的记录" })
            }
            refresh()
        }
    }

    /** 丢弃全部未提交操作。这些是还没交上去的数据，所以界面上必须二次确认。 */
    fun dropAllPending() {
        viewModelScope.launch {
            container.healthLocalStore.saveQueue(HealthQueue())
            container.billsLocalStore.saveQueue(BillQueue())
            _uiState.update { it.copy(message = "已丢弃全部待同步记录") }
            refresh()
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun describe(outcome: WriteOutcome): String = when (outcome) {
        is WriteOutcome.Saved -> "已同步"
        is WriteOutcome.Queued -> "仍有 ${outcome.pending} 条待同步"
        else -> "提交被拒绝，请去对应页面查看"
    }
}
