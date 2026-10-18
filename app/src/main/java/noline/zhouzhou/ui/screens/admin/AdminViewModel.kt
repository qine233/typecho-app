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
    val moreError: String? = null,
    /** 没配 Cloudreve 就不摆一个点进去只会报错的入口。 */
    val driveAvailable: Boolean = false
)

class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState(isLoading = true))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        loadPosts()
        watchContentChanges()
        viewModelScope.launch {
            container.settingsRepository.cloudreveConfigured.collect { configured ->
                _uiState.update { it.copy(driveAvailable = configured) }
            }
        }
    }

    /** 编辑器存完稿返回时，这一页得自己去重取。 */
    private fun watchContentChanges() {
        viewModelScope.launch {
            // 删除已经在就地摘掉了，那一次回声跳过：整表重拉会把滚动位置甩回顶部。
            container.contentChanged.collect {
                if (skipNextChange) skipNextChange = false else fetch(reset = true, silent = true)
            }
        }
    }

    private var skipNextChange = false

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
    private fun fetch(reset: Boolean, silent: Boolean = false) {
        viewModelScope.launch {
            val loaded = if (reset) 0 else _uiState.value.posts.size
            // 已加载条数换算页码：删过文章导致列表整体前移时，这一页会重复带回旧文章，
            // 去重后只会少补不多取，不会跳过任何一篇。
            val page = loaded / PAGE_SIZE + 1
            _uiState.update {
                it.copy(
                    isLoading = if (reset) !silent else false,
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
                skipNextChange = true
                invalidatePostCache(postId)
                // 首页/日记那边还留着这一篇，靠这条事件让它们自己去重取。
                container.notifyContentChanged()
                _uiState.update { state ->
                    state.copy(posts = state.posts.filterNot { it.postId == postId })
                }
                onDone()
            }
        }
    }

    /**
     * 站点上没了，本地两份副本（内存 + 落盘）也必须跟着没：
     * 本站 getPost 会 500，详情页命中缓存就再也不回源，留着就是一篇点得开的死文章。
     */
    private suspend fun invalidatePostCache(postId: String) {
        container.postCache.remove(postId)
        container.postCacheStore.remove(postId)
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
