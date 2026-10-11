package com.typecho.app.ui.screens.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.typecho.app.AppContainer
import com.typecho.app.data.model.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class DiaryDay(
    val dayKey: String,
    val timestamp: Long,
    val posts: List<Post>
)

data class DiaryUiState(
    val days: List<DiaryDay> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && error == null && days.isEmpty()
}

class DiaryViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(DiaryUiState())
    val uiState: StateFlow<DiaryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() = fetch { it.copy(isLoading = true, error = null) }

    fun refresh() = fetch { it.copy(isRefreshing = true) }

    private fun fetch(progress: (DiaryUiState) -> DiaryUiState) {
        viewModelScope.launch {
            _uiState.update(progress)
            val config = container.settingsRepository.siteConfig.first()
            if (config.siteUrl.isBlank()) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = "站点未配置") }
                return@launch
            }
            // 日记分散在整站文章里，需要全量拉取后筛选，不能只取前 200 篇。
            val result = container.postRepository.getAllPosts(config)
            result.fold(
                onSuccess = { posts ->
                    container.postCache.putAll(posts)
                    val diary = posts.filter { it.isDiary }.sortedByDescending { it.dateCreated }
                    _uiState.update {
                        it.copy(
                            days = groupByDay(diary),
                            totalCount = diary.size,
                            isLoading = false,
                            isRefreshing = false,
                            error = null
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = e.message) }
                }
            )
        }
    }

    private fun groupByDay(posts: List<Post>): List<DiaryDay> {
        val groups = LinkedHashMap<String, MutableList<Post>>()
        val stamps = LinkedHashMap<String, Long>()
        for (post in posts) {
            val key = dayKey(post.dateCreated)
            groups.getOrPut(key) { mutableListOf() }.add(post)
            stamps.putIfAbsent(key, post.dateCreated)
        }
        return groups.entries.map { (key, items) ->
            DiaryDay(key, stamps[key] ?: 0L, items)
        }
    }

    private fun dayKey(timestamp: Long): String {
        if (timestamp == 0L) return "unknown"
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp * 1000 }
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
