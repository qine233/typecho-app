package noline.zhouzhou.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import noline.zhouzhou.data.model.SshConnectionFailure
import noline.zhouzhou.data.model.SshFailureKind
import noline.zhouzhou.data.model.SshHostKey
import noline.zhouzhou.data.model.SshProfile
import noline.zhouzhou.data.model.TerminalScreen
import noline.zhouzhou.data.remote.SshLink
import noline.zhouzhou.data.sync.SshSessionService

/** 会话走到哪一步了，界面按这个换标题、换按钮。 */
enum class SshPhase { Idle, Connecting, WaitingKey, Connected, Closed, Failed }

data class SshSessionState(
    val phase: SshPhase = SshPhase.Idle,
    val title: String = "",
    val detail: String = "",
    val advice: String = "",
    /** 第一次见面（或换过密钥）时等待用户确认的那把。 */
    val candidate: SshHostKey? = null
) {
    val busy: Boolean get() = phase == SshPhase.Connecting || phase == SshPhase.WaitingKey
    val usable: Boolean get() = phase == SshPhase.Connected
}

/**
 * 宝塔那台服务器的 SSH 会话。
 *
 * 会话挂在 App 级单例上而不是 ViewModel 上：切去别的页面，终端不该跟着断，
 * 只有用户按「断开」或者服务器挂掉才收场。光这样还不够——实测退到后台约二十秒进程被冻结、
 * socket 被拆，所以连着的时候另挂一条 [SshSessionService] 的前台通知把进程撑在前台优先级。
 * 读循环独占一条线程，字节直接喂进
 * [TerminalScreen]，屏幕的每一次改动都跟着 [revision] 发布出去，UI 只看这个数决定重画。
 */
class SshRepository(
    context: Context,
    private val settings: SettingsRepository
) {
    private val appContext = context.applicationContext
    private val cacheDir = appContext.cacheDir

    val screen = TerminalScreen()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var link: SshLink? = null
    private var reader: Job? = null

    /**
     * 会话是第几轮。散场那段代码跑在 IO 线程上，可能晚于下一轮 [open] 或 [close] 好几拍，
     * 认了这个号才动手，否则老会话的收尾会把新会话的连接、通知和界面卡片一起拆掉。
     */
    @Volatile
    private var sessionToken = 0

    /** 握手线程停在「等用户点头」上，UI 点完就把这个 future 填掉。 */
    @Volatile
    private var approval: CompletableDeferred<Boolean>? = null

    /**
     * 刚连上时界面要自动弹一次软键盘；转屏重建 Activity 不该再弹——
     * 横屏里键盘占掉大半高度，终端会被挤成三四行。
     */
    @Volatile
    private var keyboardWanted = false

    fun takeKeyboardRequest(): Boolean {
        val wanted = keyboardWanted
        keyboardWanted = false
        return wanted
    }

    private val _state = MutableStateFlow(SshSessionState())
    val state: StateFlow<SshSessionState> = _state.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /** 屏幕内容变了 N 次，界面拿这个数决定要不要重画。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val config = settings.sshConfig
    val pins = settings.sshPins

    val connected: Boolean get() = link?.connected == true

    /** 开一条新会话；`cols`/`rows` 由界面按实际可画的格子数算好。 */
    fun open(profile: SshProfile, pinned: List<SshHostKey>, cols: Int, rows: Int) {
        close()
        val token = ++sessionToken
        screen.reset()
        screen.resize(cols, rows)
        _revision.value += 1
        _state.value = SshSessionState(SshPhase.Connecting)
        reader = scope.launch {
            val ssh = SshLink(profile, pinned, ::awaitApproval, cacheDir)
            try {
                ssh.connect(cols, rows)
                link = ssh
                ssh.acceptedKey?.let { accepted -> settings.pinSshHostKey(accepted) }
                keyboardWanted = true
                publish(token) { SshSessionState(SshPhase.Connected) }
                // 会话挂在前台服务上才真能扛住后台：光靠 App 级单例，进程一被冻结 socket 就拆。
                SshSessionService.start(appContext, profile.host, profile.port, profile.user)
                pump(ssh, token)
                publish(token) {
                    SshSessionState(
                        SshPhase.Closed,
                        title = "服务器把会话关了",
                        advice = "可能是 exit 退出、超时，或者对端重启。"
                    )
                }
            } catch (failure: SshConnectionFailure) {
                publish(token) { failed(failure.kind, failure.message, failure.advice) }
            } catch (failure: Exception) {
                publish(token) {
                    failed(
                        SshFailureKind.Unknown,
                        failure.message ?: failure.javaClass.simpleName,
                        "再试一次，还是不行就看具体报错。"
                    )
                }
            } finally {
                runCatching { ssh.close() }
                if (token == sessionToken) {
                    link = null
                    SshSessionService.stop(appContext)
                }
            }
        }
    }

    /** 只有还活着的那一轮才有资格改界面：散场路上的旧会话别把新会话的卡片盖掉。 */
    private inline fun publish(token: Int, state: () -> SshSessionState) {
        if (token == sessionToken) _state.value = state()
    }

    private fun failed(kind: SshFailureKind, message: String, advice: String) =
        SshSessionState(SshPhase.Failed, title = kind.title, detail = message, advice = advice)

    /** 阻塞读，谁先返回谁说话；对端关闭时 read 返回 -1。 */
    private suspend fun pump(ssh: SshLink, token: Int) {
        val buffer = ByteArray(ReadChunkSize)
        while (true) {
            val read = ssh.read(buffer)
            if (read < 0) return
            currentCoroutineContext().ensureActive()
            // 旧会话的读循环醒来时可能已经换了一轮，别再往新屏幕吐字节。
            if (token != sessionToken) return
            if (read > 0) {
                screen.feed(buffer.copyOfRange(0, read))
                _revision.value += 1
            }
        }
    }

    /**
     * 握手线程在 SSHJ 里回调进来，只能阻塞等；UI 那边 [resolveHostKey] 把结果填上。
     * 一直没人点就按「不信」处理，绝不能默认放行。
     */
    private fun awaitApproval(candidate: SshHostKey): Boolean {
        val gate = CompletableDeferred<Boolean>()
        approval = gate
        _state.value = SshSessionState(SshPhase.WaitingKey, candidate = candidate)
        val granted = runCatching {
            runBlocking { withTimeout(ApprovalTimeoutMs) { gate.await() } }
        }.getOrDefault(false)
        approval = null
        return granted
    }

    /** 用户在指纹弹窗上点的结果。点了「信」当场钉下来，下次就不用再问。 */
    fun resolveHostKey(accept: Boolean) {
        val candidate = _state.value.candidate
        if (accept && candidate != null) {
            scope.launch { runCatching { settings.pinSshHostKey(candidate) } }
        }
        if (accept) _state.value = SshSessionState(SshPhase.Connecting)
        approval?.complete(accept)
    }

    fun send(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    fun sendBytes(bytes: ByteArray) {
        val ssh = link ?: return
        writer.launch { runCatching { ssh.write(bytes) } }
    }

    /** 界面尺寸变了（转屏、键盘收起、字号调整）就把新行列报给服务器。 */
    fun resize(cols: Int, rows: Int) {
        screen.resize(cols, rows)
        _revision.value += 1
        val ssh = link ?: return
        writer.launch { runCatching { ssh.resize(cols, rows) } }
    }

    fun close() {
        // 亲手断开、或开新会话前清场：先把号作废，散场路上那个读循环就再也别碰界面和服务。
        sessionToken++
        reader?.cancel()
        reader = null
        approval?.complete(false)
        val ssh = link
        link = null
        if (ssh != null) writer.launch { runCatching { ssh.close() } }
        SshSessionService.stop(appContext)
        _state.value = SshSessionState(SshPhase.Idle)
    }

    /** 会话还活着就别把用户填了一半的东西清了。 */
    suspend fun saveConfig(profile: SshProfile) = settings.saveSshConfig(profile)

    suspend fun clearConfig() = settings.clearSshConfig()

    suspend fun unpin(id: String) = settings.unpinSshHostKey(id)

    private companion object {
        const val ReadChunkSize = 8 * 1024

        /** 指纹弹窗最多等人两分钟，超时按不信处理。 */
        const val ApprovalTimeoutMs = 120_000L
    }
}
