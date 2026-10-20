package noline.zhouzhou.ui.screens.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.SshAuthKind
import noline.zhouzhou.data.model.SshHostKey
import noline.zhouzhou.data.model.SshProfile
import noline.zhouzhou.data.model.TerminalScreen
import noline.zhouzhou.data.model.TermWidth
import noline.zhouzhou.data.repository.SshPhase
import noline.zhouzhou.data.repository.SshSessionState
import noline.zhouzhou.data.repository.maskSecret

const val SshTextSizeMinSp = 10f
const val SshTextSizeMaxSp = 22f
const val SshTextSizeDefaultSp = 13f

/** 画布尺寸要静这么久才认作最终值，见 [SshTerminalViewModel.reportGrid]。 */
private const val ResizeSettleMillis = 220L

/** 终端页要摆的东西：存过的连接、会话走到哪一步、已钉下的主机密钥、这块地方量出几列几行。 */
data class SshTerminalUiState(
    val host: String = "",
    /** 端口输入框里的原文，填到一半也留着，别一边打字一边把框清空。 */
    val portText: String = SshProfile.DefaultPort.toString(),
    val port: Int = SshProfile.DefaultPort,
    val user: String = SshProfile.DefaultUser,
    val authKind: SshAuthKind = SshAuthKind.Password,
    /** 已存凭据的掩码；空表示本机没存过，连的时候必须现场填。 */
    val secretMasked: String = "",
    val passphraseStored: Boolean = false,
    val configured: Boolean = false,
    /** 宝塔面板地址里剥出来的主机名，地址漏填时一键回填。 */
    val panelHost: String = "",
    val pins: List<SshHostKey> = emptyList(),
    val session: SshSessionState = SshSessionState(),
    val error: String = "",
    val cols: Int = TerminalScreen.DEFAULT_COLS,
    val rows: Int = TerminalScreen.DEFAULT_ROWS,
    val textSizeSp: Float = SshTextSizeDefaultSp
) {
    val phase: SshPhase get() = session.phase
    val busy: Boolean get() = session.busy
    val connected: Boolean get() = session.phase == SshPhase.Connected
}

class SshTerminalViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sshRepository
    private val settings = container.settingsRepository

    private val _uiState = MutableStateFlow(SshTerminalUiState())
    val uiState: StateFlow<SshTerminalUiState> = _uiState.asStateFlow()

    val screen: TerminalScreen get() = repository.screen
    val revision: StateFlow<Long> get() = repository.revision

    /** 画布量出来的格子数；没连上之前先记着，连的时候一次性报给 PTY。 */
    private var gridCols = TerminalScreen.DEFAULT_COLS
    private var gridRows = TerminalScreen.DEFAULT_ROWS
    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            repository.state.collect { session ->
                _uiState.update { it.copy(session = session) }
            }
        }
        viewModelScope.launch {
            repository.config.collect { profile ->
                _uiState.update {
                    it.copy(
                        host = profile.host,
                        port = profile.port,
                        portText = profile.port.toString(),
                        user = profile.user,
                        authKind = profile.authKind,
                        secretMasked = if (profile.secret.isBlank()) "" else maskSecret(profile.secret),
                        passphraseStored = profile.passphrase.isNotBlank(),
                        configured = profile.host.isNotBlank() && profile.secret.isNotBlank()
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.pins.collect { keys -> _uiState.update { it.copy(pins = keys) } }
        }
        // 面板就装在这台服务器上，地址多半是同一个；先剥出来备着，省得人再抄一遍 IP。
        viewModelScope.launch {
            settings.btConfig.collect { config ->
                val host = SshProfile.hostFromPanel(config.site)
                if (host.isNotBlank()) _uiState.update { it.copy(panelHost = host) }
            }
        }
    }

    fun setHost(value: String) = _uiState.update { it.copy(host = value) }

    fun setPort(value: String) = _uiState.update {
        it.copy(portText = value, port = value.trim().toIntOrNull() ?: it.port)
    }

    fun setUser(value: String) = _uiState.update { it.copy(user = value) }

    fun setAuthKind(kind: SshAuthKind) = _uiState.update { it.copy(authKind = kind) }

    fun setTextSize(size: Float) = _uiState.update {
        it.copy(textSizeSp = size.coerceIn(SshTextSizeMinSp, SshTextSizeMaxSp))
    }

    private var resizeSettle: Job? = null

    /**
     * 键盘开合、展开「更多」那一层都会让画布一路换尺寸，中间态别一个不落地发给服务器——
     * 真终端会把每次 SIGWINCH 当成一次重排，vim 跟着抖。等它稳住再报一次。
     */
    fun reportGrid(cols: Int, rows: Int) {
        val c = cols.coerceIn(TerminalScreen.MIN_COLS, TerminalScreen.MAX_COLS)
        val r = rows.coerceIn(TerminalScreen.MIN_ROWS, TerminalScreen.MAX_ROWS)
        if (c == gridCols && r == gridRows) return
        gridCols = c
        gridRows = r
        _uiState.update { it.copy(cols = c, rows = r) }
        resizeSettle?.cancel()
        resizeSettle = viewModelScope.launch {
            delay(ResizeSettleMillis)
            if (repository.connected) repository.resize(c, r)
        }
    }

    fun usePanelHost() {
        val host = _uiState.value.panelHost
        if (host.isNotBlank()) _uiState.update { it.copy(host = host, error = "") }
    }

    /**
     * 表单里现场填的凭据；[secret] 留空表示沿用本机已存的那份。
     *
     * 先把配置落盘再读回来连接：沿用已存凭据时明文只在这一次读取里出现，
     * 不用把它抄进界面状态，也不用自己拼「哪份密文对应哪个字段」。
     */
    fun connect(secret: String, passphrase: String) {
        val state = _uiState.value
        val draft = SshProfile(
            host = state.host,
            port = state.port,
            user = state.user,
            authKind = state.authKind,
            secret = secret,
            passphrase = passphrase
        )
        // 凭据留空是「沿用本机已存的那份」，不能在这里就被判成没填
        val validated = runCatching { draft.validated(reuseStoredSecret = true) }
        if (validated.isFailure) {
            _uiState.update { it.copy(error = validated.exceptionOrNull()?.message.orEmpty()) }
            return
        }
        _uiState.update { it.copy(error = "") }
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val profile = runCatching {
                repository.saveConfig(validated.getOrThrow())
                settings.sshConfig.first()
            }.getOrElse { failure ->
                _uiState.update { it.copy(error = failure.message ?: "这份配置存不下来") }
                return@launch
            }
            if (profile.secret.isBlank()) {
                _uiState.update {
                    it.copy(error = if (state.authKind == SshAuthKind.PrivateKey) "要粘贴私钥内容" else "要填登录密码")
                }
                return@launch
            }
            repository.open(profile, _uiState.value.pins, gridCols, gridRows)
        }
    }

    /** 用本机存过的那份配置再连一次。 */
    fun reconnect() = connect("", "")

    fun send(bytes: ByteArray) {
        if (bytes.isNotEmpty()) repository.sendBytes(bytes)
    }

    fun arrow(direction: TermDirection) =
        repository.sendBytes(SshInput.arrow(direction, screen.applicationCursorKeys))

    fun ctrl(letter: Char) = repository.sendBytes(SshInput.ctrl(letter))

    fun key(name: String) = repository.sendBytes(SshInput.functionKey(name))

    /** 粘贴：开了 bracketed paste 的程序（vim、nano）靠这层包裹才不会把粘贴当成连敲命令。 */
    fun paste(text: String) = repository.sendBytes(SshInput.paste(text, screen.bracketedPaste))

    fun resolveHostKey(accept: Boolean) = repository.resolveHostKey(accept)

    /** 刚连上那一次才自动弹键盘；转屏重建界面时这里返回 false。 */
    fun takeKeyboardRequest(): Boolean = repository.takeKeyboardRequest()

    fun disconnect() = repository.close()

    fun clearConfig() {
        viewModelScope.launch {
            repository.clearConfig()
            _uiState.update { it.copy(secretMasked = "", configured = false, error = "") }
        }
    }

    fun unpin(id: String) {
        viewModelScope.launch { repository.unpin(id) }
    }

    fun consumeError() = _uiState.update { it.copy(error = "") }
}
