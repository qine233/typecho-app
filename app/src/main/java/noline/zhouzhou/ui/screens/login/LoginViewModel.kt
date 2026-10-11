package noline.zhouzhou.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.SiteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val siteUrl: String = "",
    val xmlrpcPath: String = "/action/xmlrpc",
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionOk: Boolean? = null
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.siteConfig.collect { config ->
                _uiState.update {
                    it.copy(
                        siteUrl = config.siteUrl,
                        xmlrpcPath = config.xmlrpcPath,
                        username = config.username
                    )
                }
            }
        }
    }

    fun onSiteUrlChange(url: String) = _uiState.update { it.copy(siteUrl = url, error = null, connectionOk = null) }
    fun onXmlrpcPathChange(path: String) = _uiState.update { it.copy(xmlrpcPath = path, error = null, connectionOk = null) }
    fun onUsernameChange(name: String) = _uiState.update { it.copy(username = name, error = null) }
    fun onPasswordChange(pass: String) = _uiState.update { it.copy(password = pass, error = null) }
    fun onRememberMeChange(remember: Boolean) = _uiState.update { it.copy(rememberMe = remember) }

    fun testConnection() {
        val state = _uiState.value
        if (state.siteUrl.isBlank()) {
            _uiState.update { it.copy(error = "请输入站点地址") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(testingConnection = true, connectionOk = null) }
            val config = state.toSiteConfig()
            val result = container.userRepository.testConnection(config.fullXmlrpcUrl)
            _uiState.update {
                it.copy(testingConnection = false, connectionOk = result.isSuccess)
            }
        }
    }

    fun login() {
        val state = _uiState.value
        if (state.siteUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "请填写所有必填项") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val config = state.toSiteConfig()
            val result = container.userRepository.login(config.fullXmlrpcUrl, config.username, config.password)
            result.fold(
                onSuccess = { blogInfo ->
                    container.settingsRepository.saveSiteConfig(
                        config.copy(blogId = blogInfo.blogId),
                        blogName = blogInfo.blogName,
                        isAdmin = blogInfo.isAdmin,
                        rememberMe = state.rememberMe
                    )
                    _uiState.update { it.copy(isLoading = false, success = true) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "登录失败")
                    }
                }
            )
        }
    }

    private fun LoginUiState.toSiteConfig() = SiteConfig(
        siteUrl = siteUrl.trim(),
        xmlrpcPath = xmlrpcPath.trim(),
        username = username.trim(),
        password = password,
        blogId = "1"
    )
}
