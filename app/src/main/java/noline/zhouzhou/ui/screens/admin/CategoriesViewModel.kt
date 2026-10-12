package noline.zhouzhou.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Category
import noline.zhouzhou.data.model.SiteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoriesUiState(
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
) {
    /** 分类名 → 分类，用于把 parentId 显示成人能看懂的上级分类名。 */
    private val byId: Map<String, Category>
        get() = categories.associateBy { it.categoryId }

    fun parentName(category: Category): String? =
        byId[category.parentId]?.takeIf { it.categoryId != category.categoryId }?.name
}

class CategoriesViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoriesUiState(isLoading = true))
    val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val config = getSiteConfig()
            container.postRepository.getCategories(config).fold(
                onSuccess = { cats ->
                    _uiState.update {
                        it.copy(categories = cats.filter { c -> c.name.isNotBlank() }, isLoading = false)
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    fun create(
        name: String, slug: String, parentId: String, description: String,
        onCreated: () -> Unit
    ) {
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "分类名不能为空") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            val config = getSiteConfig()
            container.postRepository.createCategory(config, name.trim(), slug.trim(), parentId, description.trim())
                .fold(
                    onSuccess = {
                        _uiState.update { it.copy(isSaving = false) }
                        onCreated()
                        load()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isSaving = false, error = e.message) }
                    }
                )
        }
    }

    fun delete(categoryId: String) {
        viewModelScope.launch {
            val config = getSiteConfig()
            container.postRepository.deleteCategory(config, categoryId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(categories = state.categories.filterNot { it.categoryId == categoryId })
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private suspend fun getSiteConfig(): SiteConfig = container.settingsRepository.siteConfig.first()
}
