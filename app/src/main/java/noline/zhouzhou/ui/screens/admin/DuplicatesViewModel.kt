package noline.zhouzhou.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.data.repository.DuplicateScan
import noline.zhouzhou.data.repository.scanDuplicatePosts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicatesUiState(
    val scanning: Boolean = false,
    /** 翻到第几篇了——整站扫描一页一页来，不报进度看起来像卡死。 */
    val loaded: Int = 0,
    val scan: DuplicateScan? = null,
    val error: String? = null,
    /** 勾上的就是要点删除的那几篇（每组里最早的那篇永远不在其中）。 */
    val selected: Set<String> = emptySet(),
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val deletedCount: Int = 0
) {
    val deletableIds: List<String> get() = scan?.deletable?.map { it.postId }.orEmpty()
    val hasResults: Boolean get() = scan != null
}

/**
 * 重复文章清理。当年 newPost 的 cid 回包被 PHP 警告吞掉过，点了两次发布就多出一篇，
 * 而且两边内容一模一样——这一页负责把它们翻出来并一次删干净。
 */
class DuplicatesViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState(scanning = true))
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    /** 扫描用的那份快照，删完就地重算，不用再把整站翻一遍。 */
    private var snapshot: List<Post> = emptyList()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true, loaded = 0, error = null) }
            val config = getSiteConfig()
            val acc = LinkedHashMap<String, Post>()
            var page = 1
            var failure: String? = null
            var reachedEnd = false

            while (true) {
                val result = container.postRepository.getRecentPosts(config, PAGE_SIZE, page)
                val batch = result.getOrElse { e ->
                    failure = e.message ?: "没取到文章列表"
                    emptyList()
                }
                batch.forEach { if (it.postId.isNotBlank()) acc[it.postId] = it }
                _uiState.update { it.copy(loaded = acc.size) }

                if (batch.size < PAGE_SIZE || acc.size >= MaxPosts) {
                    reachedEnd = batch.size < PAGE_SIZE
                    break
                }
                page++
            }

            snapshot = acc.values.toList()
            val scanned = scanDuplicatePosts(snapshot)
            _uiState.update {
                val fresh = scanned.copy(truncated = failure != null || !reachedEnd)
                it.copy(
                    scanning = false,
                    scan = fresh,
                    // 正文完全相同的那几篇默认全选：留最早的那篇，删的都是重复副本。
                    selected = fresh.deletable.map { p -> p.postId }.toSet(),
                    error = failure
                )
            }
        }
    }

    fun toggleSelected(postId: String) {
        _uiState.update {
            it.copy(selected = if (postId in it.selected) it.selected - postId
            else it.selected + postId)
        }
    }

    fun selectAllDeletable() = _uiState.update {
        it.copy(selected = it.deletableIds.toSet())
    }

    fun clearSelection() = _uiState.update { it.copy(selected = emptySet()) }

    fun deleteSelected() {
        val state = _uiState.value
        val ids = state.selected.toList()
        if (ids.isEmpty() || state.deleting) return

        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true, deleteError = null) }
            val config = getSiteConfig()
            val deleted = mutableSetOf<String>()
            var failure: String? = null
            ids.forEach { id ->
                val result = container.postRepository.deletePost(config, id)
                if (result.isSuccess) {
                    deleted += id
                    // 站点上没了，两份本地副本也必须跟着没，否则详情页还能点开一篇死文章。
                    container.postCache.remove(id)
                    container.postCacheStore.remove(id)
                } else {
                    failure = failure ?: (result.exceptionOrNull()?.message ?: "删除失败")
                }
            }
            if (deleted.isNotEmpty()) container.notifyContentChanged()

            snapshot = snapshot.filterNot { it.postId in deleted }
            val fresh = scanDuplicatePosts(snapshot)
            _uiState.update {
                it.copy(
                    deleting = false,
                    deletedCount = it.deletedCount + deleted.size,
                    deleteError = failure,
                    scan = fresh.copy(truncated = it.scan?.truncated == true),
                    selected = (fresh.deletable.map { p -> p.postId }.toSet())
                )
            }
        }
    }

    private suspend fun getSiteConfig(): noline.zhouzhou.data.model.SiteConfig {
        return container.settingsRepository.siteConfig.first()
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MaxPosts = 600
    }
}
