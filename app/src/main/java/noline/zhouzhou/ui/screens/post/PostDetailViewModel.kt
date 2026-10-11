package noline.zhouzhou.ui.screens.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Comment
import noline.zhouzhou.data.model.CommentThread
import noline.zhouzhou.data.model.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条顶层评论及其下的回复。 */
data class CommentThread(
    val root: Comment,
    val replies: List<Comment> = emptyList()
)

data class PostDetailUiState(
    val post: Post? = null,
    val comments: List<Comment> = emptyList(),
    val threads: List<CommentThread> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingComments: Boolean = false,
    val error: String? = null,
    val commentText: String = "",
    val isSubmittingComment: Boolean = false,
    val commentError: String? = null,
    val commentSuccess: Boolean = false,
    val replyTo: Comment? = null,
    val showCommentInput: Boolean = false,
    val isLoggedIn: Boolean = false,
    val liked: Boolean = false,
    val collected: Boolean = false
) {
    /** 只统计已通过审核的评论，待审核的另算。 */
    val approvedCommentCount: Int
        get() = comments.count { it.isApproved }
}

class PostDetailViewModel(
    private val container: AppContainer,
    private val postId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    init {
        loadPost()
        loadComments()
        checkLogin()
        checkInteractions()
    }

    private fun checkLogin() {
        viewModelScope.launch {
            container.settingsRepository.isLoggedIn.collect { loggedIn ->
                _uiState.update { it.copy(isLoggedIn = loggedIn) }
            }
        }
    }

    private fun checkInteractions() {
        viewModelScope.launch {
            container.interactionRepository.likedPosts.collect { liked ->
                _uiState.update { it.copy(liked = postId in liked) }
            }
        }
        viewModelScope.launch {
            container.interactionRepository.collectedPosts.collect { saved ->
                _uiState.update { it.copy(collected = postId in saved) }
            }
        }
    }

    private fun loadPost() {
        viewModelScope.launch {
            // 列表接口已带完整正文，优先用缓存，绕开站点 getPost 的 500。
            container.postCache.get(postId)?.let { cached ->
                _uiState.update { it.copy(post = cached, isLoading = false, error = null) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, error = null) }
            val config = getSiteConfig()
            val result = container.postRepository.getPost(config, postId)
            result.fold(
                onSuccess = { post ->
                    container.postCache.put(post)
                    _uiState.update { it.copy(post = post, isLoading = false) }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    private fun loadComments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingComments = true) }
            val config = getSiteConfig()
            val result = container.commentRepository.getComments(config, postId = postId)
            result.fold(
                onSuccess = { comments ->
                    _uiState.update {
                        it.copy(
                            comments = comments,
                            threads = buildThreads(comments),
                            isLoadingComments = false
                        )
                    }
                },
                onFailure = { _uiState.update { it.copy(isLoadingComments = false) } }
            )
        }
    }

    /** 按 parentId 组装两级楼中楼；父评论不在本次结果里的回复单独成楼，避免被吞掉。 */
    private fun buildThreads(comments: List<Comment>): List<CommentThread> {
        val visible = comments.filter { it.isApproved }
        val byId = visible.associateBy { it.commentId }
        val repliesByParent = mutableMapOf<String, MutableList<Comment>>()
        val roots = mutableListOf<Comment>()

        visible.forEach { comment ->
            val parent = comment.parentId
            if (parent.isNotBlank() && parent != "0" && byId.containsKey(parent)) {
                repliesByParent.getOrPut(parent) { mutableListOf() }.add(comment)
            } else {
                roots.add(comment)
            }
        }
        return roots
            .sortedByDescending { it.dateCreated }
            .map { root ->
                CommentThread(
                    root = root,
                    replies = repliesByParent[root.commentId].orEmpty().sortedBy { it.dateCreated }
                )
            }
    }

    fun onCommentTextChange(text: String) = _uiState.update { it.copy(commentText = text) }

    fun openCommentInput(replyTo: Comment? = null) = _uiState.update {
        it.copy(showCommentInput = true, replyTo = replyTo, commentError = null)
    }

    fun closeCommentInput() = _uiState.update {
        it.copy(showCommentInput = false, replyTo = null)
    }

    fun toggleLike() {
        viewModelScope.launch { container.interactionRepository.toggleLike(postId) }
    }

    fun toggleCollect() {
        viewModelScope.launch { container.interactionRepository.toggleCollect(postId) }
    }

    fun submitComment() {
        val text = _uiState.value.commentText.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingComment = true, commentError = null) }
            val config = getSiteConfig()
            val result = container.commentRepository.newComment(
                config, postId, text, _uiState.value.replyTo?.commentId
            )
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            commentText = "",
                            commentSuccess = true,
                            showCommentInput = false,
                            replyTo = null
                        )
                    }
                    loadComments()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isSubmittingComment = false, commentError = e.message ?: "评论失败")
                    }
                }
            )
        }
    }

    fun clearCommentSuccess() = _uiState.update { it.copy(commentSuccess = false) }

    private suspend fun getSiteConfig(): noline.zhouzhou.data.model.SiteConfig {
        return container.settingsRepository.siteConfig.first()
    }
}
