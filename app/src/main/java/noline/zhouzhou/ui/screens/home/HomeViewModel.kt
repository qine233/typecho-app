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
    val blogName: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategory: String? = null
) {
    /** 就地筛选只按分类走；找内容这种事交给搜索页，那里能查到全站。 */
    val filteredPosts: List<Post>
        get() = posts.filter { post ->
            selectedCategory == null || post.categories.any { it.trim() == selectedCategory }
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
            // 首页不显示日记，而日记写得密、常常整页都是，所以重置时自动往后翻几页凑满首屏。
            var autoPages = 0
            while (true) {
                val current = page
                val result = container.postRepository.getRecentPosts(config, PAGE_SIZE, current)
                if (result.isFailure) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = result.exceptionOrNull()?.message
                        )
                    }
                    if (!reset && current > 1) page = current - 1
                    break
                }
                val fetched = result.getOrDefault(emptyList())

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
                val hasMore = fetched.size >= PAGE_SIZE - TailSlack && current < MaxPages
                _uiState.update {
                    it.copy(
                        posts = merged,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        canLoadMore = hasMore
                    )
                }
                container.postCacheStore.save(rawPosts)

                if (!reset || merged.size >= MinFirstScreen || !hasMore || autoPages >= MaxAutoPages) break
                autoPages += 1
                page = current + 1
                // 还没翻出可见内容就接着翻，继续显示骨架屏而不是「暂无文章」。
                _uiState.update { it.copy(isLoading = merged.isEmpty(), isLoadingMore = merged.isNotEmpty()) }
            }
        }
    }

    fun selectCategory(name: String?) = _uiState.update { it.copy(selectedCategory = name) }

    companion object {
        private const val PAGE_SIZE = 20
        private const val TailSlack = 1
        private const val MaxPages = 30

        /** 首屏至少要凑够这么多篇非日记文章才停，不然翻两页全是日记就只剩空列表。 */
        private const val MinFirstScreen = 8
        private const val MaxAutoPages = 10
    }
}
