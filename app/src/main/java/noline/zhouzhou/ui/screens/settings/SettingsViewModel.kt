package noline.zhouzhou.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.NolineReport
import noline.zhouzhou.data.repository.UpdateInfo
import noline.zhouzhou.data.repository.UpdateOutcome
import noline.zhouzhou.data.repository.maskSecret
import noline.zhouzhou.util.canInstallFromUnknownSources
import noline.zhouzhou.util.installApk
import noline.zhouzhou.util.openInstallPermissionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SettingsUiState(
    val blogName: String = "",
    val siteUrl: String = "",
    val username: String = "",
    val isAdmin: Boolean = false,
    val darkMode: String = "system",
    val fontScale: Float = 1.0f,
    val showLogoutDialog: Boolean = false,
    val testConnectionResult: Boolean? = null,
    val isTestingConnection: Boolean = false,
    val nolineKeyConfigured: Boolean = false,
    val nolineKeyMasked: String = "",
    val nolineFormLabel: String = "",
    val nolineChecking: Boolean = false,
    val nolineReport: NolineReport? = null,
    val nolineToast: String = "",
    val update: UpdateState? = null
)

/** 设置页「检测更新」那张卡的全部形态。 */
sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object NoMatchingBuild : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class HandedToInstaller(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val app get() = container.application

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var downloadedApk: File? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.blogName.collect { name ->
                _uiState.update { it.copy(blogName = name) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.siteConfig.collect { config ->
                _uiState.update { it.copy(siteUrl = config.siteUrl, username = config.username) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.isAdmin.collect { admin ->
                _uiState.update { it.copy(isAdmin = admin) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.darkMode.collect { mode ->
                _uiState.update { it.copy(darkMode = mode) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.fontScale.collect { scale ->
                _uiState.update { it.copy(fontScale = scale) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.nolineApiKey.collect { key ->
                _uiState.update {
                    it.copy(
                        nolineKeyConfigured = key.isNotBlank(),
                        nolineKeyMasked = if (key.isBlank()) "" else maskSecret(key)
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.nolineUrlForm.collect { form ->
                _uiState.update { it.copy(nolineFormLabel = form.label) }
            }
        }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch { container.settingsRepository.setDarkMode(mode) }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { container.settingsRepository.setFontScale(scale) }
    }

    fun showLogoutDialog(show: Boolean) = _uiState.update { it.copy(showLogoutDialog = show) }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            container.settingsRepository.logout()
            onDone()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, testConnectionResult = null) }
            val cfg = container.settingsRepository.siteConfig.first()
            if (cfg.siteUrl.isBlank()) {
                _uiState.update { it.copy(isTestingConnection = false, testConnectionResult = false) }
                return@launch
            }
            val result = container.userRepository.testConnection(cfg.fullXmlrpcUrl)
            _uiState.update {
                it.copy(isTestingConnection = false, testConnectionResult = result.isSuccess)
            }
        }
    }

    fun clearNolineKey() {
        viewModelScope.launch {
            val outcome = runCatching { container.settingsRepository.setNolineApiKey("") }
            _uiState.update {
                it.copy(
                    nolineReport = null,
                    nolineToast = if (outcome.isSuccess) "已清除本机密钥" else "系统密钥库没能写入，重试一次"
                )
            }
        }
    }

    /**
     * 「保存并自检」：输入框有字就先落盘（空着则表示沿用已存的那把），
     * 然后探 URL 形态 → 验密钥 → 试写入。
     */
    fun saveAndCheck(inputKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(nolineChecking = true, nolineReport = null) }
            val typed = inputKey.trim()
            val stored = container.settingsRepository.nolineApiKey.first()
            val key = if (typed.isEmpty()) stored else typed
            if (typed.isNotEmpty() && typed != stored) {
                runCatching { container.settingsRepository.setNolineApiKey(typed) }
            }
            val report = try {
                container.healthRepository.selfCheck(key)
            } catch (e: NolineException) {
                NolineReport(false, e.issue, e.message, null, false, "", "")
            } catch (e: Exception) {
                NolineReport(false, NolineIssue.Unknown, e.message ?: "响应解析失败", null, false, "", "")
            }
            _uiState.update { it.copy(nolineChecking = false, nolineReport = report) }
        }
    }

    fun consumeNolineToast() = _uiState.update { it.copy(nolineToast = "") }

    // ── 检测更新 ──

    fun checkUpdate() {
        if (_uiState.value.update is UpdateState.Checking ||
            _uiState.value.update is UpdateState.Downloading
        ) return
        viewModelScope.launch {
            _uiState.update { it.copy(update = UpdateState.Checking) }
            val next = try {
                when (val outcome = container.updateRepository.check()) {
                    UpdateOutcome.UpToDate -> UpdateState.UpToDate
                    UpdateOutcome.NoMatchingBuild -> UpdateState.NoMatchingBuild
                    is UpdateOutcome.Available -> UpdateState.Available(outcome.update)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "连不上 GitHub")
            }
            _uiState.update { it.copy(update = next) }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        if (!app.canInstallFromUnknownSources()) {
            _uiState.update { it.copy(update = UpdateState.NeedsPermission(info)) }
            return
        }
        downloadedApk?.takeIf { it.length() > 0 }?.let { alreadyThere ->
            handedToInstaller(info, alreadyThere)
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(update = UpdateState.Downloading(info, 0f)) }
            var lastPercent = -1
            try {
                val file = container.updateRepository.download(info) { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _uiState.update {
                            val current = it.update as? UpdateState.Downloading ?: return@update it
                            it.copy(update = current.copy(progress = percent / 100f))
                        }
                    }
                }
                downloadedApk = file
                handedToInstaller(info, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(update = UpdateState.Failed(e.message ?: "下载中断")) }
            }
        }
    }

    /** 安装器被用户划走之后还能再拉一次，不必重新下载。 */
    fun reinstall() {
        val info = (_uiState.value.update as? UpdateState.HandedToInstaller)?.info ?: return
        downloadedApk?.takeIf { it.length() > 0 }?.let { handedToInstaller(info, it) }
            ?: downloadUpdate(info)
    }

    fun openInstallPermission() = app.openInstallPermissionSettings()

    fun cancelUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(update = null) }
    }

    /** 从系统授权页回来接着上次的下载，不用用户再点一遍。 */
    fun refreshPermission() {
        val pending = _uiState.value.update as? UpdateState.NeedsPermission ?: return
        if (app.canInstallFromUnknownSources()) downloadUpdate(pending.info)
    }

    private fun handedToInstaller(info: UpdateInfo, file: File) {
        runCatching { app.installApk(file) }
            .onFailure {
                _uiState.update {
                    it.copy(update = UpdateState.Failed("系统里没有能装 apk 的应用"))
                }
            }
            .onSuccess { _uiState.update { it.copy(update = UpdateState.HandedToInstaller(info)) } }
    }

    override fun onCleared() {
        downloadJob?.cancel()
    }
}
