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
    val error: String? = null,
    val selectedTab: Int = 0
)

class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init { loadPosts() }

    fun loadPosts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val config = getSiteConfig()
            val result = container.postRepository.getAllPosts(config)
            result.fold(
                onSuccess = { posts ->
                    container.postCache.putAll(posts)
                    _uiState.update { it.copy(posts = posts, isLoading = false) }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    fun deletePost(postId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val config = getSiteConfig()
            val result = container.postRepository.deletePost(config, postId)
            result.onSuccess {
                loadPosts()
                onDone()
            }
        }
    }

    fun onTabSelected(index: Int) = _uiState.update { it.copy(selectedTab = index) }

    private suspend fun getSiteConfig(): noline.zhouzhou.data.model.SiteConfig {
        return container.settingsRepository.siteConfig.first()
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
