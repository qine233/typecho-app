package noline.zhouzhou.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.remote.GithubMirror
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.DownloadProgress
import noline.zhouzhou.data.repository.NolineReport
import noline.zhouzhou.data.repository.UpdateInfo
import noline.zhouzhou.data.repository.UpdateOutcome
import noline.zhouzhou.data.repository.maskSecret
import noline.zhouzhou.data.sync.UpdateDownloadService
import noline.zhouzhou.data.sync.UpdatePhase
import noline.zhouzhou.util.canInstallFromUnknownSources
import noline.zhouzhou.util.installApk
import noline.zhouzhou.util.openInstallPermissionSettings
import kotlinx.coroutines.CancellationException
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
    val cloudreveConfigured: Boolean = false,
    val cloudreveSite: String = "",
    val cloudreveEmail: String = "",
    val cloudrevePasswordMasked: String = "",
    val cloudreveChecking: Boolean = false,
    val cloudreveCheck: CloudreveCheck? = null,
    val cloudreveToast: String = "",
    val updateMirrorId: String = GithubMirror.GhProxyCom.id,
    val update: UpdateState? = null
)

/** 设置页「图床」那张卡：试登录的两种结果。 */
sealed interface CloudreveCheck {
    data object Passed : CloudreveCheck
    data class Failed(val message: String) : CloudreveCheck
}

/** 设置页「检测更新」那张卡的全部形态。 */
sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object NoMatchingBuild : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState

    /** 下载在前台服务里跑，切后台也不断；这里只是照着通知同步一份。 */
    data class Downloading(val info: UpdateInfo, val progress: DownloadProgress) : UpdateState

    /** 下完了、包在缓存里躺着，但人当时不在页面上，所以没自动拉起安装器。 */
    data class ReadyToInstall(val info: UpdateInfo) : UpdateState
    data class HandedToInstaller(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val app get() = container.application

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 这一页亲手按下的下载：下完时如果人还在页面上，就把安装器直接拉起来。 */
    private var armedAutoInstall = false
    private var screenVisible = true

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
        viewModelScope.launch {
            container.settingsRepository.cloudreveConfig.collect { config ->
                _uiState.update {
                    it.copy(
                        cloudreveConfigured = config.isComplete,
                        cloudreveSite = config.site,
                        cloudreveEmail = config.email,
                        cloudrevePasswordMasked =
                            if (config.password.isBlank()) "" else maskSecret(config.password)
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.updateMirror.collect { mirror ->
                _uiState.update { it.copy(updateMirrorId = mirror.id) }
            }
        }
        // 下载归前台服务，这里只跟着它的状态改口。用 AppContainer 里那份共享状态而不是
        // 自己起协程，所以人切去后台再回来、甚至页面重建，进度都接得上。
        viewModelScope.launch {
            container.updateDownload.phase.collect { phase -> applyPhase(phase) }
        }
    }

    private fun applyPhase(phase: UpdatePhase?) {
        val next = when (phase) {
            null -> when (_uiState.value.update) {
                is UpdateState.Downloading, is UpdateState.ReadyToInstall -> null
                else -> return
            }
            is UpdatePhase.Downloading -> UpdateState.Downloading(phase.info, phase.progress)
            is UpdatePhase.Ready -> {
                // 包在缓存里，按版本找回它；被系统清掉就退回「可以重新下」。
                val apk = container.updateRepository.cachedApk(phase.info.version)
                val mine = armedAutoInstall
                armedAutoInstall = false
                when {
                    apk == null -> UpdateState.Available(phase.info)
                    // 后台起 Activity 会被系统拦，所以下完时人不在页面上就只留通知和按钮。
                    mine && screenVisible -> {
                        handedToInstaller(phase.info, apk)
                        return
                    }
                    else -> UpdateState.ReadyToInstall(phase.info)
                }
            }
            is UpdatePhase.Failed -> {
                armedAutoInstall = false
                UpdateState.Failed(phase.message)
            }
        }
        _uiState.update { it.copy(update = next) }
    }

    /** 页面可见性由设置页的生命周期回调喂进来。 */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
    }

    /** 当前选中的更新来源；界面只存 id，动手时换成枚举。 */
    private fun preferredMirror(): GithubMirror = GithubMirror.fromId(_uiState.value.updateMirrorId)

    fun setUpdateMirror(mirror: GithubMirror) {
        viewModelScope.launch { container.settingsRepository.setUpdateMirror(mirror) }
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

    // ── Cloudreve 图床 ──

    /** 密码留空 = 沿用已存的；自检只试登录，不动云端任何文件。 */
    fun saveAndCheckCloudreve(site: String, email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(cloudreveChecking = true, cloudreveCheck = null) }
            val check = try {
                container.settingsRepository.saveCloudreveConfig(site, email, password.trim())
                // verify() 把失败包在 Result 里，不 getOrThrow 的话连不上也会显示「已连上」。
                container.cloudreveRepository.verify().getOrThrow()
                CloudreveCheck.Passed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CloudreveCheck.Failed(e.message ?: "Cloudreve 没有响应")
            }
            _uiState.update { it.copy(cloudreveChecking = false, cloudreveCheck = check) }
        }
    }

    fun clearCloudreve() {
        viewModelScope.launch {
            val outcome = runCatching { container.settingsRepository.clearCloudreveConfig() }
            _uiState.update {
                it.copy(
                    cloudreveCheck = null,
                    cloudreveToast = if (outcome.isSuccess) "已清除图床配置" else "系统密钥库没能写入，重试一次"
                )
            }
        }
    }

    fun consumeCloudreveToast() = _uiState.update { it.copy(cloudreveToast = "") }

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
        // 先看是不是已经在下：下了一半的那个文件也在缓存里，长度不为 0，不能当成下好的包。
        if (_uiState.value.update is UpdateState.Downloading) return
        val already = container.updateRepository.cachedApk(info.version)
        if (already != null) {
            handedToInstaller(info, already)
            return
        }
        armedAutoInstall = true
        _uiState.update {
            it.copy(update = UpdateState.Downloading(info, DownloadProgress(0L, info.sizeBytes)))
        }
        // 协程交给前台服务，这个 ViewModel 一销毁就把下载带走的话，切个后台就前功尽弃。
        UpdateDownloadService.start(app, info, preferredMirror())
    }

    /** 安装器被用户划走、或者下完时人不在页面上，都靠这个按钮再来一次。 */
    fun installDownloaded() {
        val info = when (val state = _uiState.value.update) {
            is UpdateState.ReadyToInstall -> state.info
            is UpdateState.HandedToInstaller -> state.info
            else -> return
        }
        val apk = container.updateRepository.cachedApk(info.version)
        if (apk == null) downloadUpdate(info) else handedToInstaller(info, apk)
    }

    fun openInstallPermission() = app.openInstallPermissionSettings()

    fun cancelUpdate() {
        armedAutoInstall = false
        UpdateDownloadService.cancel(app)
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
}
