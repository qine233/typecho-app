package noline.zhouzhou.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.data.model.SiteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PagesUiState(
    val pages: List<Post> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class PagesViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(PagesUiState(isLoading = true))
    val uiState: StateFlow<PagesUiState> = _uiState.asStateFlow()

    init { load() }

    /** wp.getPages 没有分页参数，一次拿全部页面。 */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val config = getSiteConfig()
            container.postRepository.getPages(config).fold(
                onSuccess = { pages ->
                    _uiState.update { it.copy(pages = pages, isLoading = false) }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    /** 删掉的那行先本地摘掉，失败再用错误条说清楚。 */
    fun deletePage(pageId: String) {
        viewModelScope.launch {
            val config = getSiteConfig()
            val result = container.postRepository.deletePage(config, pageId)
            result.fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(pages = state.pages.filterNot { it.postId == pageId })
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private suspend fun getSiteConfig(): SiteConfig = container.settingsRepository.siteConfig.first()
}
