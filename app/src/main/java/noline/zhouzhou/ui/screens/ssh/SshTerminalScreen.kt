package noline.zhouzhou.ui.screens.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.SshAuthKind
import noline.zhouzhou.data.model.SshHostKey
import noline.zhouzhou.data.repository.SshPhase
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * 终端的深底：不管 App 是浅色还是深色主题，终端都保持深底，
 * 命令行的观感才稳定，`vim`、`htop` 那套配色也才读得出来。
 */
private val TermBgLight = Color(0xFF262A33)
private val TermBgDark = Color(0xFF15171C)
private val TermFg = Color(0xFFDCE1EC)
private val TermKeyBg = Color(0x14FFFFFF)
private val TermKeyBorder = Color(0x26FFFFFF)
private val TermKeyLabel = Color(0xFFC4CBDA)
private val TermMuted = Color(0xFF8C93A3)

@Composable
fun SshTerminalScreen(appContainer: AppContainer, onBack: () -> Unit) {
    val viewModel = typechoViewModel<SshTerminalViewModel>(appContainer) { SshTerminalViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val revision by viewModel.revision.collectAsState()
    val t = LocalNoLine.current
    val density = LocalDensity.current
    val textSize = state.textSizeSp.sp
    val metrics = rememberTerminalMetrics(textSize, density)
    val termBg = if (t.pageBg.luminance() > 0.4f) TermBgLight else TermBgDark
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var editing by remember { mutableStateOf(false) }
    val phase = state.session.phase
    // 还没连过就摆表单；连上之后表单退到顶栏那颗齿轮后面，整块屏幕留给终端。
    val showForm = editing || phase == SshPhase.Idle

    val showKeyboard: () -> Unit = {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = if (showForm) t.pageBg else termBg,
        topBar = {
            TerminalTopBar(
                state = state,
                showForm = showForm,
                termBg = termBg,
                onBack = onBack,
                onToggleForm = { editing = !editing },
                onDisconnect = viewModel::disconnect
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 全屏 edge-to-edge，窗口不会因为软键盘缩；不让出键盘高度，
                // 按键条和终端最后几行会被输入法盖住。
                .imePadding()
                .background(if (showForm) t.pageBg else termBg)
        ) {
            // 量的是画布本身：按键条排在下面另占一行，不然报给 PTY 的行数会比看得见的多几行。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { size ->
                        viewModel.reportGrid(
                            metrics.columnsFor(size.width.toFloat()),
                            metrics.rowsFor(size.height.toFloat())
                        )
                    }
            ) {
                if (showForm) {
                    ConnectForm(
                        state = state,
                        viewModel = viewModel,
                        onConnect = { secret, passphrase ->
                            editing = false
                            viewModel.connect(secret, passphrase)
                        }
                    )
                } else {
                    TerminalPane(
                        viewModel = viewModel,
                        state = state,
                        revision = revision,
                        textSizePx = with(density) { textSize.toPx() },
                        background = termBg,
                        accent = t.accent.toArgb(),
                        focusRequester = focusRequester
                    )
                }

                // 断开和失败都摊在终端上：最后一屏输出还得看得见，人才知道刚才发生了什么。
                if (!showForm && (phase == SshPhase.Failed || phase == SshPhase.Closed)) {
                    SessionResultCard(
                        state = state,
                        onRetry = viewModel::reconnect,
                        onEdit = { editing = true },
                        onDismiss = onBack
                    )
                }
                if (!showForm && phase == SshPhase.Connecting) {
                    ConnectingOverlay(state)
                }
            }

            if (!showForm) {
                KeyStrip(
                    viewModel = viewModel,
                    state = state,
                    onShowKeyboard = showKeyboard
                )
            }

            KeyboardInput(
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester)
                    // 实体键盘的方向键、退格这些不产生文字，走这里；焦点在这个 1dp 的框上，
                    // 画布那边 isFocusable = false，收不到按键。
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> viewModel.arrow(TermDirection.Up)
                            Key.DirectionDown -> viewModel.arrow(TermDirection.Down)
                            Key.DirectionLeft -> viewModel.arrow(TermDirection.Left)
                            Key.DirectionRight -> viewModel.arrow(TermDirection.Right)
                            Key.Backspace -> viewModel.send(ByteArray(1) { SshInput.Backspace.toByte() })
                            Key.Delete -> viewModel.key("Delete")
                            Key.Enter, Key.NumPadEnter -> viewModel.key("Enter")
                            Key.Tab -> viewModel.key("Tab")
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                onBytes = viewModel::send
            )
        }
    }

    if (phase == SshPhase.WaitingKey) {
        state.session.candidate?.let { candidate ->
            HostKeyDialog(
                candidate = candidate,
                onAccept = { viewModel.resolveHostKey(true) },
                onDecline = { viewModel.resolveHostKey(false) }
            )
        }
    }
}

/** 顶栏：表单模式走浅色那套，终端模式压成深色，跟画布连成一片。 */
@Composable
private fun TerminalTopBar(
    state: SshTerminalUiState,
    showForm: Boolean,
    termBg: Color,
    onBack: () -> Unit,
    onToggleForm: () -> Unit,
    onDisconnect: () -> Unit
) {
    val t = LocalNoLine.current
    val fg = if (showForm) t.textStrong else TermFg
    val secondary = if (showForm) t.textSecondary else TermKeyLabel
    Column(modifier = Modifier.background(if (showForm) t.pageBg else termBg)) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    modifier = Modifier.size(20.dp), tint = fg
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (showForm) "SSH 终端" else state.session.title.ifBlank { terminalTitle(state) },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    maxLines = 1
                )
                Text(
                    text = statusLine(state),
                    fontSize = 11.5.sp,
                    color = if (state.connected) t.accent else secondary,
                    maxLines = 1
                )
            }
            if (showForm) {
                if (state.connected) TextButton(onClick = onToggleForm) { Text("回到终端", color = t.accent) }
            } else {
                IconButton(onClick = onToggleForm) {
                    Icon(
                        Icons.Default.Tune, contentDescription = "改配置",
                        modifier = Modifier.size(19.dp), tint = secondary
                    )
                }
                if (state.connected) {
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Default.LinkOff, contentDescription = "断开",
                            modifier = Modifier.size(19.dp), tint = secondary
                        )
                    }
                }
            }
        }
    }
}

private fun terminalTitle(state: SshTerminalUiState): String {
    val host = state.host.trim()
    return if (host.isBlank()) "SSH 终端" else "${state.user.trim()}@$host"
}

private fun statusLine(state: SshTerminalUiState): String = when (state.phase) {
    SshPhase.Idle -> if (state.configured) "配置已存，点「保存并连接」就开一条会话" else "还没存过连接"
    SshPhase.Connecting -> "正在握手…"
    SshPhase.WaitingKey -> "等你核对主机密钥"
    SshPhase.Connected -> "已连接 · ${state.cols}×${state.rows} 格"
    SshPhase.Closed -> "会话已结束"
    SshPhase.Failed -> state.session.title.ifBlank { "没连上" }
}

/**
 * 终端画布 + 往上翻的提示。
 *
 * 画布只管画格子，行列数由外层量好后报给 ViewModel；键盘输入走 [KeyboardInput] 那个 1dp 的框。
 */
@Composable
private fun TerminalPane(
    viewModel: SshTerminalViewModel,
    state: SshTerminalUiState,
    revision: Long,
    textSizePx: Float,
    background: Color,
    accent: Int,
    focusRequester: FocusRequester
) {
    val controller = LocalSoftwareKeyboardController.current
    var surface by remember { mutableStateOf<TerminalSurface?>(null) }
    var scrolledUp by remember { mutableStateOf(false) }

    LaunchedEffect(state.connected) {
        if (!state.connected) {
            controller?.hide()
            return@LaunchedEffect
        }
        // 只有「刚连上」那一次自动弹键盘；转屏重建界面时不再弹，
        // 横屏里键盘占掉大半高度，终端会被挤成三四行。
        if (viewModel.takeKeyboardRequest()) {
            runCatching { focusRequester.requestFocus() }
            controller?.show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                TerminalSurface(context).apply {
                    onTap = {
                        runCatching { focusRequester.requestFocus() }
                        controller?.show()
                    }
                    onScrolled = { offset -> scrolledUp = offset > 0 }
                }
            },
            modifier = Modifier
                .fillMaxSize(),
            update = { view ->
                surface = view
                view.model = viewModel.screen
                view.textSizePx = textSizePx
                view.backgroundArgb = background.toArgb()
                view.foregroundArgb = TermFg.toArgb()
                view.cursorArgb = accent
                view.revision = revision
            }
        )

        if (scrolledUp) {
            KeyChip(
                label = "↑ 回到最新",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                onClick = {
                    surface?.scrollToBottom()
                    scrolledUp = false
                }
            )
        }

        if (!state.connected && state.phase != SshPhase.Connecting) {
            // 没连上时画布是空的，这里补一句人话，别让人对着黑屏猜
            Text(
                text = if (state.session.detail.isBlank()) "还没连上" else state.session.detail,
                fontSize = 12.5.sp,
                color = TermMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
            )
        }
    }
}

private val KeyStripHeight = 46.dp

/** 键盘输入：软键盘敲的字翻成按键串发出去，回车之后清空重新攒下一行。 */
@Composable
private fun KeyboardInput(
    modifier: Modifier,
    onBytes: (ByteArray) -> Unit
) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            // 拼音这类候选还没上屏时先不发，否则半截字母会直接进服务器
            val composing = next.composition
            if (composing != null && composing.length > 0) {
                value = next
                return@BasicTextField
            }
            val bytes = SshInput.deltaBytes(value.text, next.text)
            if (bytes.isNotEmpty()) onBytes(bytes)
            value = if (next.text.contains('\n')) TextFieldValue("") else next
        },
        modifier = modifier,
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default
        )
    )
}

/**
 * 按键条：手机键盘上没有 Esc、Tab、Ctrl、方向键这些终端离不开的键，
 * 全摆在这里。「更多」那一层放符号、Ctrl 组合、字号和粘贴。
 */
@Composable
private fun KeyStrip(
    viewModel: SshTerminalViewModel,
    state: SshTerminalUiState,
    onShowKeyboard: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val controller = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.22f))
            .navigationBarsPadding()
    ) {
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                StripLabel("符号")
                FlowKeys(
                    labels = listOf(
                        "|", "~", "-", "/", "\\", "\"", "'", "`", "$", "*", "<", ">",
                        "(", ")", "#", "%", ";", ":", "=", "+", "[", "]", "{", "}",
                        "^", "&", "!", "?", ".", ",", "_", "@"
                    )
                ) { viewModel.send(SshInput.normalize(it)) }
                StripLabel("Ctrl 组合")
                FlowKeys(listOf("A", "C", "D", "E", "K", "L", "R", "U", "W", "Z")) {
                    viewModel.ctrl(it.first())
                }
                StripLabel("字号 ${state.textSizeSp.toInt()}sp")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyChip("A-", onClick = { viewModel.setTextSize(state.textSizeSp - 1f) })
                    Spacer(Modifier.width(8.dp))
                    KeyChip("A+", onClick = { viewModel.setTextSize(state.textSizeSp + 1f) })
                    Spacer(Modifier.width(12.dp))
                    KeyChip("粘贴", icon = Icons.Default.ContentPaste) {
                        clipboard.getText()?.text?.let { viewModel.paste(it) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KeyStripHeight)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            KeyChip("Esc") { viewModel.key("Esc") }
            KeyChip("^C") { viewModel.ctrl('c') }
            KeyChip("^Z") { viewModel.ctrl('z') }
            KeyChip("^D") { viewModel.ctrl('d') }
            KeyChip("Tab") { viewModel.key("Tab") }
            KeyChip("⌫") { viewModel.send(ByteArray(1) { SshInput.Backspace.toByte() }) }
            KeyChip("↵") { viewModel.key("Enter") }
            KeyChip("↑") { viewModel.arrow(TermDirection.Up) }
            KeyChip("↓") { viewModel.arrow(TermDirection.Down) }
            KeyChip("←") { viewModel.arrow(TermDirection.Left) }
            KeyChip("→") { viewModel.arrow(TermDirection.Right) }
            KeyChip("更多", icon = Icons.Default.MoreHoriz, active = expanded) { expanded = !expanded }
            KeyChip(icon = if (expanded) Icons.Default.KeyboardHide else Icons.Default.Keyboard) {
                onShowKeyboard()
                controller?.show()
            }
        }
    }
}

@Composable
private fun StripLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = TermMuted,
        modifier = Modifier.padding(bottom = 5.dp, top = 3.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowKeys(labels: List<String>, onClick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEach { label ->
            KeyChip(label, height = 34.dp) { onClick(label) }
        }
    }
}

@Composable
private fun KeyChip(
    label: String = "",
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    active: Boolean = false,
    height: Dp = 34.dp,
    onClick: () -> Unit
) {
    val t = LocalNoLine.current
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) t.accent.copy(alpha = 0.28f) else TermKeyBg)
            .border(1.dp, if (active) t.accent else TermKeyBorder, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (label.length > 2) 12.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = label.ifBlank { null },
                modifier = Modifier.size(16.dp),
                tint = if (active) t.accent else TermKeyLabel
            )
        }
        if (label.isNotBlank()) {
            if (icon != null) Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = if (active) t.accent else TermKeyLabel
            )
        }
    }
}

/** 连接表单：地址、端口、账号、密码或私钥，底下摊已固定的主机密钥。 */
@Composable
private fun ConnectForm(
    state: SshTerminalUiState,
    viewModel: SshTerminalViewModel,
    onConnect: (secret: String, passphrase: String) -> Unit
) {
    val t = LocalNoLine.current
    val clipboard = LocalClipboardManager.current
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val private = state.authKind == SshAuthKind.PrivateKey

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .noLineCard(RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text("直连这台服务器", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(5.dp))
            Text(
                "走 SSH（默认 22 端口）登进宝塔那台服务器，跟面板的网页接口是两条路：" +
                    "面板打不开、被改坏、nginx 挂了，这里照样能连上去修。" +
                    "登录密码和私钥只用系统密钥库存密文，不落明文，也不上传到任何地方。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .noLineCard(RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text("连接设置", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::setHost,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("服务器地址") },
                placeholder = { Text("1.2.3.4 或 server.example.com") },
                supportingText = if (state.panelHost.isNotBlank() && state.panelHost != state.host.trim()) {
                    {
                        TextButton(onClick = viewModel::usePanelHost, contentPadding = PaddingValues(0.dp)) {
                            Text("用面板那台：${state.panelHost}", fontSize = 12.sp)
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.portText,
                    onValueChange = viewModel::setPort,
                    modifier = Modifier.width(104.dp),
                    singleLine = true,
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                OutlinedTextField(
                    value = state.user,
                    onValueChange = viewModel::setUser,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("用户名") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("登录方式", fontSize = 12.5.sp, color = t.textSecondary)
                NoLineChip(
                    label = "密码",
                    selected = !private,
                    onClick = { viewModel.setAuthKind(SshAuthKind.Password) },
                    icon = Icons.Default.Lock
                )
                NoLineChip(
                    label = "私钥",
                    selected = private,
                    onClick = { viewModel.setAuthKind(SshAuthKind.PrivateKey) },
                    icon = Icons.Default.VpnKey
                )
            }
            Spacer(Modifier.height(12.dp))

            if (private) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 128.dp),
                    label = {
                        Text(
                            if (state.secretMasked.isBlank()) "私钥内容"
                            else "留空表示沿用已存的：${state.secretMasked}"
                        )
                    },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp),
                    maxLines = 14,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (reveal) "藏起来" else "显示出来",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                TextButton(
                    onClick = { clipboard.getText()?.text?.let { secret = it.trim() } },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("从剪贴板粘贴", fontSize = 12.5.sp)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            if (state.passphraseStored) "私钥口令（留空沿用已存的）" else "私钥口令（没设就留空）"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                Text(
                    "私钥在连接时写进 App 自己的缓存目录，权限只有本人可读，连完立刻删掉；" +
                        "留在手机上的只有系统密钥库加密后的那份。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            if (state.secretMasked.isBlank()) "登录密码"
                            else "留空表示沿用已存的：${state.secretMasked}"
                        )
                    },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (reveal) "藏起来" else "显示出来",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            }

            if (state.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning, null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(state.error, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onConnect(secret, passphrase) },
                    enabled = !state.busy
                ) { Text(if (state.busy) "正在连接…" else "保存并连接", fontSize = 13.5.sp) }
                if (state.configured) {
                    TextButton(onClick = viewModel::clearConfig) {
                        Text("清除配置", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        PinnedKeysCard(pins = state.pins, onUnpin = viewModel::unpin)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .noLineCard(RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text("手机上的终端怎么用", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Text(
                "屏幕底下那条按键摆着 Esc、Ctrl 组合、Tab、方向键这些手机键盘上没有的键，" +
                    "点「更多」还有符号、字号和剪贴板粘贴；`vim`、`top`、`htop` 都能照常跑，" +
                    "屏幕会按量出来的格子数告诉服务器该折多少行。\n" +
                    "往上拖能翻历史输出，翻动时不会被新输出拽回底部，点「回到最新」接着看。\n" +
                    "会话挂在 App 上，切去别的页面、退到后台都不会断，只有点「断开」或者服务器收掉 shell 才结束。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 已经信任过的主机密钥：一行一条，指纹可以自己跟服务器上 `ssh-keygen -lf` 对眼。 */
@Composable
private fun PinnedKeysCard(pins: List<SshHostKey>, onUnpin: (String) -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Fingerprint, null, modifier = Modifier.size(17.dp), tint = t.accent)
            Spacer(Modifier.width(8.dp))
            Text("已信任的服务器密钥", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "第一次连上一台服务器，App 会把它的公钥指纹记下来，往后每次都自动核对；" +
                "对不上就硬拦，不会再问一遍「要不要信」——那正是中间人最想看到的一句话。" +
                "点右边的垃圾桶就忘掉这台，下次连接会重新问你。",
            fontSize = 12.sp,
            color = t.textSecondary,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        if (pins.isEmpty()) {
            Text("还没记过任何服务器。", fontSize = 12.5.sp, color = t.textLight)
        } else {
            pins.forEachIndexed { index, key ->
                if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${key.host}:${key.port}",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = t.textStrong
                        )
                        Text(key.algorithm.ifBlank { "未知算法" }, fontSize = 11.5.sp, color = t.textLight)
                        Spacer(Modifier.height(3.dp))
                        SelectionContainer {
                            Text(
                                key.fingerprint,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = t.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    IconButton(onClick = { onUnpin(key.id) }) {
                        Icon(
                            Icons.Default.Delete, contentDescription = "忘掉这台服务器",
                            modifier = Modifier.size(18.dp), tint = t.textLight
                        )
                    }
                }
            }
        }
    }
}

/** 第一次见面（或服务器换过密钥）时的确认门：不给「一直信任」这种默认值，必须亲手点。 */
@Composable
private fun HostKeyDialog(
    candidate: SshHostKey,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val t = LocalNoLine.current
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("核对服务器密钥") },
        text = {
            Column {
                Text(
                    "第一次连 ${candidate.host}:${candidate.port}，App 没法自己判断对面是不是你那台服务器。" +
                        "对一下这串指纹：在服务器上跑 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`，" +
                        "两边一模一样才点信任。",
                    fontSize = 13.sp,
                    color = t.textMain,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(t.surfaceSoft, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        candidate.algorithm.ifBlank { "未知算法" },
                        fontSize = 11.5.sp,
                        color = t.textLight
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            candidate.fingerprint,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = t.textStrong,
                            lineHeight = 19.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "点了信任，这把密钥就记在本机上，往后自动核对；换了服务器或者重装了系统，" +
                        "指纹会变，那时候会硬拦下来而不是偷偷放行。",
                    fontSize = 11.5.sp,
                    color = t.textSecondary,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("核对无误，信任并连接", color = t.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("不信，断开", color = MaterialTheme.colorScheme.error) }
        },
        containerColor = t.cardBg
    )
}

/** 断开和失败都摊在终端上，最后一屏输出还看得见。 */
@Composable
private fun SessionResultCard(
    state: SshTerminalUiState,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalNoLine.current
    val session = state.session
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(t.cardBg)
                .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
                .clickable { }
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning, null,
                    modifier = Modifier.size(18.dp), tint = t.warnText
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    session.title.ifBlank { "会话已结束" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = t.textStrong
                )
            }
            if (session.detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        session.detail,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = t.textMain,
                        lineHeight = 18.sp
                    )
                }
            }
            if (session.advice.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(session.advice, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重连", fontSize = 13.5.sp)
                }
                FilledTonalButton(onClick = onEdit) { Text("改配置", fontSize = 13.5.sp) }
            }
        }
    }
}

@Composable
private fun ConnectingOverlay(state: SshTerminalUiState) {
    val t = LocalNoLine.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = t.accent
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "正在连接 ${terminalTitle(state)}…",
                fontSize = 12.5.sp,
                color = Color.White
            )
        }
    }
}

