package noline.zhouzhou.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.repository.SearchResults

data class SearchUiState(
    val query: String = "",
    val results: SearchResults = SearchResults(),
    val isSearching: Boolean = false,
    /** 真的跑过一次搜索。用它区分「还没输入」和「搜了没中」。 */
    val searched: Boolean = false,
    val history: List<String> = emptyList()
) {
    val hasResults: Boolean get() = results.all.isNotEmpty()
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val container: AppContainer,
    initialQuery: String = ""
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(query = initialQuery))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** 输入先攒在这儿，停顿这么久才真的发请求；站点那边一次搜索是一趟 HTTP。 */
    private val pending = MutableStateFlow(initialQuery)

    /** 只有最后一次发出的请求能改状态，否则慢的那趟会把新结果盖掉。 */
    private var generation = 0

    init {
        viewModelScope.launch {
            container.settingsRepository.searchHistory.collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            pending.debounce(DebounceMs).collect { query ->
                if (query.trim().isBlank()) {
                    generation++
                    _uiState.update {
                        it.copy(results = SearchResults(), isSearching = false, searched = false)
                    }
                } else if (query.trim() != _uiState.value.results.query) {
                    // 按下搜索已经当场查过一轮，攒出来的这一轮就别再发一趟 HTTP。
                    run(query)
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        pending.value = query
    }

    fun clearQuery() = onQueryChange("")

    /** 键盘上那下「搜索」不等人攒词，当场发出去，并把这个词记进历史。 */
    fun submit() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return
        record(query)
        run(query)
    }

    fun retry() = run(_uiState.value.query)

    fun pickHistory(term: String) {
        onQueryChange(term)
        submit()
    }

    /** 点开一条结果就说明这个词是搜对了的，比按下搜索更能代表用户想找什么。 */
    fun onResultOpened() = record(_uiState.value.query)

    fun forget(term: String) {
        viewModelScope.launch { container.settingsRepository.removeSearchTerm(term) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.settingsRepository.clearSearchHistory() }
    }

    private fun record(term: String) {
        val trimmed = term.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { container.settingsRepository.recordSearchTerm(trimmed) }
    }

    private fun run(query: String) {
        val token = ++generation
        _uiState.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val results = container.searchRepository.search(query)
            if (token != generation) return@launch
            _uiState.update { it.copy(results = results, isSearching = false, searched = true) }
        }
    }

    private companion object {
        const val DebounceMs = 350L
    }
}
