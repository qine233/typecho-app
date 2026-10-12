package noline.zhouzhou.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Comment
import noline.zhouzhou.data.model.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminUiState(
    val posts: List<Post> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val error: String? = null,
    /** 翻页失败时不动整页列表，只在页脚给一句可重试的提示。 */
    val moreError: String? = null
)

class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState(isLoading = true))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init { loadPosts() }

    fun loadPosts() = fetch(reset = true)

    fun loadMore() {
        val s = _uiState.value
        if (s.isLoading || s.isLoadingMore || !s.canLoadMore || s.moreError != null) return
        fetch(reset = false)
    }

    /**
     * 一页一页取：Typecho 的管理列表按 URL 上的 page 参数翻页，
     * 一次拉全量反而会被 PHP 告警截断，看起来就只剩「第一批」。
     */
    private fun fetch(reset: Boolean) {
        viewModelScope.launch {
            val loaded = if (reset) 0 else _uiState.value.posts.size
            // 已加载条数换算页码：删过文章导致列表整体前移时，这一页会重复带回旧文章，
            // 去重后只会少补不多取，不会跳过任何一篇。
            val page = loaded / PAGE_SIZE + 1
            _uiState.update {
                it.copy(
                    isLoading = if (reset) true else false,
                    isLoadingMore = !reset,
                    error = if (reset) null else it.error,
                    moreError = null
                )
            }
            val config = getSiteConfig()
            val result = container.postRepository.getRecentPosts(config, PAGE_SIZE, page)
            result.fold(
                onSuccess = { fetched ->
                    container.postCache.putAll(fetched)
                    val merged = if (reset) {
                        fetched.distinctBy { it.postId }
                    } else {
                        val seen = _uiState.value.posts.map { it.postId }.toSet()
                        _uiState.value.posts + fetched.filter { it.postId !in seen }
                    }
                    _uiState.update {
                        it.copy(
                            posts = merged,
                            isLoading = false,
                            isLoadingMore = false,
                            // 尾部那一两篇可能补取失败，留 1 篇余量再判「到底」。
                            canLoadMore = fetched.size >= PAGE_SIZE - TailSlack && merged.size < MaxPosts
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = if (reset) e.message else it.error,
                            moreError = if (reset) null else (e.message ?: "没取到更多文章")
                        )
                    }
                }
            )
        }
    }

    /** 删除后本地摘掉这一篇即可，整表重拉会把用户滚到的位置弄丢。 */
    fun deletePost(postId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val config = getSiteConfig()
            val result = container.postRepository.deletePost(config, postId)
            result.onSuccess {
                _uiState.update { state ->
                    state.copy(posts = state.posts.filterNot { it.postId == postId })
                }
                onDone()
            }
        }
    }

    private suspend fun getSiteConfig(): noline.zhouzhou.data.model.SiteConfig {
        return container.settingsRepository.siteConfig.first()
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val TailSlack = 1
        private const val MaxPosts = 1000
    }
}

data class CommentsUiState(
    val comments: List<Comment> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val filterStatus: String? = null
)

class CommentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentsUiState())
    val uiState: StateFlow<CommentsUiState> = _uiState.asStateFlow()

    init { loadComments() }

    fun loadComments(status: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, filterStatus = status) }
            val config = getSiteConfig()
            val result = container.commentRepository.getComments(config, status = status, number = 100)
            result.fold(
                onSuccess = { comments -> _uiState.update { it.copy(comments = comments, isLoading = false) } },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            val config = getSiteConfig()
            container.commentRepository.deleteComment(config, commentId)
            loadComments(_uiState.value.filterStatus)
        }
    }

    fun approveComment(commentId: String) {
        viewModelScope.launch {
            val config = getSiteConfig()
            container.commentRepository.editCommentStatus(config, commentId, "approve")
            loadComments(_uiState.value.filterStatus)
        }
    }

    fun spamComment(commentId: String) {
        viewModelScope.launch {
            val config = getSiteConfig()
            container.commentRepository.editCommentStatus(config, commentId, "spam")
            loadComments(_uiState.value.filterStatus)
        }
    }

    private suspend fun getSiteConfig(): noline.zhouzhou.data.model.SiteConfig {
        return container.settingsRepository.siteConfig.first()
    }
}
