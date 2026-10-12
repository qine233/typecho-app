package noline.zhouzhou.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Category
import noline.zhouzhou.data.model.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val posts: List<Post> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val blogName: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategory: String? = null
) {
    val filteredPosts: List<Post>
        get() {
            val query = searchQuery.trim()
            return posts.filter { post ->
                val byCategory = selectedCategory == null ||
                    post.categories.any { it.trim() == selectedCategory }
                val byQuery = query.isBlank() ||
                    post.displayTitle.contains(query, ignoreCase = true) ||
                    post.plainExcerpt.contains(query, ignoreCase = true)
                byCategory && byQuery
            }
        }
}

class HomeViewModel(
    private val container: AppContainer,
    initialCategory: String = ""
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            isLoading = true,
            selectedCategory = initialCategory.takeIf { it.isNotBlank() }
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Typecho 的管理列表按 URL 上的 page 参数翻页，所以「下一页」只要换页码，
    // 不需要像过去那样用更大的 count 把前面的文章重取一遍。
    private var page = 1
    private var rawPosts: List<Post> = emptyList()

    init {
        loadBlogName()
        loadCategories()
        restoreCache()
        loadPosts()
        watchContentChanges()
    }

    /** 存稿/发布/删文章之后重取第一页，别让新文章等用户退出重进。 */
    private fun watchContentChanges() {
        viewModelScope.launch {
            container.contentChanged.collect {
                loadCategories()
                fetch(reset = true, refreshing = false)
            }
        }
    }

    /** 冷启动先用上次落盘的文章渲染，首屏不等网络。 */
    private fun restoreCache() {
        viewModelScope.launch {
            val cached = container.postCacheStore.load()
            if (cached.isEmpty()) return@launch
            container.postCache.putAll(cached)
            rawPosts = cached
            _uiState.update { it.copy(posts = cached.filterNot { p -> p.isDiary }) }
        }
    }

    private fun loadBlogName() {
        viewModelScope.launch {
            container.settingsRepository.blogName.collect { name ->
                _uiState.update { it.copy(blogName = name) }
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            val config = container.settingsRepository.siteConfig.first()
            if (config.siteUrl.isBlank()) return@launch
            container.postRepository.getCategories(config).onSuccess { cats ->
                _uiState.update { it.copy(categories = cats.filter { c -> c.name.isNotBlank() }) }
            }
        }
    }

    fun loadPosts() = fetch(reset = true, refreshing = false)

    fun refresh() = fetch(reset = true, refreshing = true)

    fun loadMore() {
        val s = _uiState.value
        if (s.isLoading || s.isRefreshing || s.isLoadingMore || !s.canLoadMore) return
        page += 1
        fetch(reset = false, refreshing = false)
    }

    private fun fetch(reset: Boolean, refreshing: Boolean) {
        viewModelScope.launch {
            if (reset) page = 1
            _uiState.update {
                it.copy(
                    isLoading = if (reset && it.posts.isEmpty()) true else it.isLoading,
                    isRefreshing = refreshing,
                    isLoadingMore = !reset && !refreshing,
                    error = null
                )
            }
            val config = container.settingsRepository.siteConfig.first()
            if (config.siteUrl.isBlank()) {
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = "站点未配置")
                }
                return@launch
            }
            // 重取时先留着旧列表渲染，新数据到了再换，避免整页闪一下骨架屏。
            val result = container.postRepository.getRecentPosts(config, PAGE_SIZE, page)
            result.fold(
                onSuccess = { fetched ->
                    container.postCache.putAll(fetched)
                    rawPosts = if (reset) {
                        fetched
                    } else {
                        val seen = rawPosts.map { it.postId }.toSet()
                        rawPosts + fetched.filter { it.postId !in seen }
                    }
                    val merged = rawPosts
                        .filterNot { it.isDiary }
                        .distinctBy { p -> p.postId }
                    // 每页尾部那一篇由 Repository 单独补取，正常就是整页；
                    // 补取偶尔失败时留 1 篇余量，避免提前判定「到底了」。
                    _uiState.update {
                        it.copy(
                            posts = merged,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            canLoadMore = fetched.size >= PAGE_SIZE - TailSlack && page < MaxPages
                        )
                    }
                    container.postCacheStore.save(rawPosts)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = e.message)
                    }
                    if (!reset && page > 1) page -= 1
                }
            )
        }
    }

    fun onSearchQueryChange(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun selectCategory(name: String?) = _uiState.update { it.copy(selectedCategory = name) }

    companion object {
        private const val PAGE_SIZE = 20
        private const val TailSlack = 1
        private const val MaxPages = 30
    }
}
