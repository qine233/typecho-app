package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtFtpUser
import noline.zhouzhou.data.model.BtNewFtpForm
import noline.zhouzhou.data.repository.BtFtpLogs
import noline.zhouzhou.data.repository.BtSourceFailure
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * FTP 管理：面板「FTP 管理」那一页的手机版。
 *
 * 一行一个账户：名字、根目录、已用/上限、到期时间、状态都在行上。手有五只——
 * 建号、暂停/启用、改密码（含批量）、限速与容量、删除，外加看登录和操作日志。
 *
 * 面板上点两下就能改的**备注和根目录，接口做不到**：六个候选 action 名实测全回
 * 「指定参数无效!」，所以这两格在这里只能读，行上也不给编辑入口。
 * 密码同理：列表每行都带明文 `password`，模型一个字段都不接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtFtpScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtFtpViewModel>(appContainer) { BtFtpViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pending by remember { mutableStateOf<FtpPending.Confirm?>(null) }
    var adding by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var passwordFor by remember { mutableStateOf<FtpPasswordTarget?>(null) }
    var accessFor by remember { mutableStateOf<BtFtpUser?>(null) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    pending?.let { ask ->
        ConfirmAction(
            title = ask.label,
            body = ask.body,
            keyword = ask.keyword,
            mustType = ask.mustType,
            onConfirm = { ask.run(); pending = null },
            onDismiss = { pending = null }
        )
    }
    if (adding) {
        AddFtpSheet(
            busy = state.busy,
            onDismiss = { adding = false },
            onSubmit = { name, password, path, remark ->
                adding = false
                viewModel.addUser(name, password, path, remark)
            }
        )
    }

    passwordFor?.let { target ->
        FtpPasswordDialog(
            title = if (target.names.size == 1) "改「${target.names.first()}」的密码" else "批量改 ${target.names.size} 个账户的密码",
            busy = state.busy,
            onDismiss = { passwordFor = null },
            onSubmit = { password ->
                val names = target.names
                passwordFor = null
                if (names.size == 1) {
                    state.users.firstOrNull { it.name == names.first() }?.let { viewModel.setPassword(it, password) }
                } else {
                    viewModel.batchSetPasswords(names, password)
                    picked = emptySet()
                    batch = false
                }
            }
        )
    }

    accessFor?.let { user ->
        AccessSheet(
            user = user,
            busy = state.busy,
            onDismiss = { accessFor = null },
            onSubmit = { download, upload, maxSize ->
                accessFor = null
                viewModel.setAccess(user, download, upload, maxSize)
            }
        )
    }

    if (state.logsFor.isNotBlank()) {
        FtpLogsSheet(
            username = state.logsFor,
            logs = state.logs,
            loading = state.loadingLogs,
            onDismiss = viewModel::closeLogs
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = t.pageBg,
        topBar = {
            Column(modifier = Modifier.background(t.pageBg)) {
                Spacer(Modifier.statusBarsPadding())
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong
                        )
                    }
                    Text("FTP 管理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { batch = !batch; picked = emptySet() },
                        enabled = !state.busy
                    ) {
                        Text(if (batch) "退出批量" else "批量改密码", fontSize = 12.5.sp, color = t.accent)
                    }
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "建账户", modifier = Modifier.size(21.dp), tint = t.accent)
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新读取", modifier = Modifier.size(20.dp), tint = t.textSecondary)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = t.accent, trackColor = t.hairline)
                Spacer(Modifier.height(10.dp))
            }
            state.failure?.let { FailureCard(it) }
            if (state.note.isNotBlank()) {
                Text(
                    state.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textMain,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(12.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = viewModel::setKeyword,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("按用户名或目录找") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                IconButton(onClick = viewModel::search, enabled = !state.busy) {
                    Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
                }
            }
            if (state.keyword.isNotBlank()) {
                Text(
                    "这一张表的关键词是本机筛的：面板那个搜索参数对 FTP 表是摆设，带什么词都回全表。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            Text(
                state.total?.let { "共 $it 个账户，已取 ${state.users.size} 个" }
                    ?: "已取 ${state.users.size} 个账户",
                fontSize = 12.sp,
                color = t.textLight
            )
            Spacer(Modifier.height(6.dp))

            if (state.users.isEmpty() && !state.busy) {
                Text(
                    if (state.keyword.isNotBlank()) "这个关键词没匹配到账户，面板那张表里也没有别的行。"
                    else if (!state.configured) "还没配好面板地址和密钥，回到宝塔那一页先保存自检。"
                    else "面板回了一个空列表：这台服务器上一个面板建的 FTP 账户都没有。",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
                )
            }

            state.users.forEachIndexed { index, user ->
                if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 6.dp))
                if (batch) {
                    FtpBatchRow(
                        user = user,
                        checked = user.name in picked,
                        onToggle = {
                            picked = if (user.name in picked) picked - user.name else picked + user.name
                        }
                    )
                } else {
                    FtpUserRow(
                        user = user,
                        busy = state.busy,
                        onToggleRunning = {
                            pending = FtpPending.Confirm(
                                label = if (user.enabled) "暂停「${user.name}」？" else "启用「${user.name}」？",
                                body = if (user.enabled) {
                                    "面板把这一行的状态翻成停用：目录、密码、限额都不动，随时能再启用。"
                                } else {
                                    "面板把这一行翻回运行中，用原来的密码和目录就能登。"
                                },
                                keyword = "",
                                mustType = false,
                                run = { viewModel.setRunning(user, !user.enabled) }
                            )
                        },
                        onPassword = { passwordFor = FtpPasswordTarget(setOf(user.name)) },
                        onAccess = { accessFor = user },
                        onLogs = { viewModel.openLogs(user) },
                        onDelete = {
                            pending = FtpPending.Confirm(
                                label = "删除「${user.name}」？",
                                body = "面板删的是账户本身。根目录 ${user.path} 里的文件归网站那边管，" +
                                    "这一发不会带走它们，但账户一没，正在用它的程序当场连不上。",
                                keyword = "删除",
                                mustType = true,
                                run = { viewModel.deleteUser(user) }
                            )
                        }
                    )
                }
            }

            if (state.hasMore) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = viewModel::loadMore,
                    enabled = !state.busy && !state.loadingMore,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) { Text(if (state.loadingMore) "正在取下一页…" else "再多取 30 条", fontSize = 13.sp) }
            }

            if (batch) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { passwordFor = FtpPasswordTarget(picked) },
                    enabled = picked.isNotEmpty() && !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("改这 ${picked.size} 个的密码", fontSize = 13.5.sp) }
                Text(
                    "面板的批量口一个账户报一个成没成，改完这一发会告诉你有几个没改成。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            FtpLimitsCard()
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 批量改密码的目标：一个名字就是单改，多个就是批量。 */
private data class FtpPasswordTarget(val names: Set<String>)

private sealed interface FtpPending {
    data class Confirm(
        val label: String,
        val body: String,
        val keyword: String,
        val mustType: Boolean,
        val run: () -> Unit
    ) : FtpPending
}

@Composable
private fun FtpUserRow(
    user: BtFtpUser,
    busy: Boolean,
    onToggleRunning: () -> Unit,
    onPassword: () -> Unit,
    onAccess: () -> Unit,
    onLogs: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                user.name.ifBlank { "（面板没给用户名）" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            StatPill(if (user.enabled) "运行中" else "已暂停", tint = if (user.enabled) t.accent else t.warnText)
        }
        if (user.subtitle.isNotBlank()) {
            Text(
                user.subtitle,
                fontSize = 11.5.sp,
                color = t.textLight,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (user.remark.isNotBlank() && user.remark != user.name) {
            Text(user.remark, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Row(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                listOfNotNull(
                    user.addTime.takeIf { it.isNotBlank() }?.let { "建于 $it" },
                    user.endAt.takeIf { it.isNotBlank() && it != "无" }?.let { "到期 $it" }
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = t.textLight
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            RowAction("暂停", enabled = !busy && user.enabled, onClick = onToggleRunning)
            RowAction("启用", enabled = !busy && !user.enabled, onClick = onToggleRunning)
            RowAction("改密码", enabled = !busy && user.id.isNotBlank(), onClick = onPassword)
            RowAction("限额", enabled = !busy && user.id.isNotBlank(), onClick = onAccess)
            RowAction("日志", enabled = !busy, onClick = onLogs)
            RowAction("删除", enabled = !busy && user.id.isNotBlank(), color = t.healthOver, onClick = onDelete)
        }
    }
}

@Composable
private fun RowAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = LocalNoLine.current.textSecondary
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, fontSize = 12.5.sp, color = if (enabled) color else LocalNoLine.current.textLight) }
}

@Composable
private fun FtpBatchRow(user: BtFtpUser, checked: Boolean, onToggle: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                user.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong
            )
            if (user.subtitle.isNotBlank()) {
                Text(user.subtitle, fontSize = 11.sp, color = t.textLight, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
        if (!user.enabled) StatPill("已暂停", tint = t.warnText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFtpSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit
) {
    val t = LocalNoLine.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(BtNewFtpForm.randomPassword()) }
    var path by remember { mutableStateOf("/www/wwwroot/") }
    // 根目录跟着账户名走（面板网页上就是这么预填的）：默认停在 /www/wwwroot/ 这一层，
    // 手一抖不改就会把整站根目录交给这个账户。改过之后就不再覆盖。
    var pathTyped by remember { mutableStateOf(false) }
    var remark by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val nameError = BtNewFtpForm.nameError(name).takeIf { name.isNotBlank() }
    val passwordError = BtNewFtpForm.passwordError(password)
    val pathError = BtNewFtpForm.pathError(path)

    fun syncPath(nameValue: String) {
        if (!pathTyped) path = "/www/wwwroot/" + nameValue.trim()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("建一个 FTP 账户", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "面板会按根目录建一个上传点，账户只能待在这个目录底下。目录不存在的时候面板自己会创建。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; syncPath(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("FTP 用户名") },
                placeholder = { Text("只能字母开头，字母数字下划线") },
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it, color = t.warnText) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("密码") },
                isError = passwordError != null,
                supportingText = {
                    Text(
                        passwordError ?: "已经随机生成了一串。这一格建完就看不回来了，先自己存一份。",
                        color = if (passwordError != null) t.warnText else t.textLight
                    )
                },
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealed) "隐藏密码" else "显示密码",
                            tint = t.textSecondary
                        )
                    }
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { password = BtNewFtpForm.randomPassword() }) {
                Text("换一串随机密码", fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = path,
                onValueChange = { path = it; pathTyped = true },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("根目录") },
                isError = pathError != null,
                supportingText = {
                    Text(
                        pathError ?: "账户只能进出这个目录底下。这一格建完改不了——面板接口没有对应的口。",
                        color = if (pathError != null) t.warnText else t.textLight
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("备注（可留空）") },
                supportingText = { Text("备注建完之后也改不了，同样只有面板网页上做得到。", color = t.textLight) },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = { onSubmit(name, password, path, remark) },
                enabled = !busy && nameError == null && passwordError == null && pathError == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "正在发给面板…" else "建这个账户", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun FtpPasswordDialog(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val t = LocalNoLine.current
    var password by remember { mutableStateOf(BtNewFtpForm.randomPassword()) }
    var revealed by remember { mutableStateOf(false) }
    val error = BtNewFtpForm.passwordError(password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "面板的密码规则是死的：6–20 位，只能字母和数字，而且两类都要有。",
                    fontSize = 12.sp,
                    color = t.textSecondary,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("新密码") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it, color = t.warnText) } },
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealed) "隐藏密码" else "显示密码",
                                tint = t.textSecondary
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { password = BtNewFtpForm.randomPassword() }) {
                    Text("换一串随机密码", fontSize = 12.5.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = error == null && !busy) { Text("改", color = t.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 限速与容量：写完读不回来，这一格的面板真相只能到面板网页上看。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessSheet(
    user: BtFtpUser,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    val t = LocalNoLine.current
    var download by remember { mutableStateOf("") }
    var upload by remember { mutableStateOf("") }
    var maxSize by remember { mutableStateOf("") }
    // 三格都得填：面板这一格没有「只改其中一项」的口，留空发过去就是 0，也就是不限，
    // 那等于把人家原有的限额悄悄抹掉。
    val problems = listOf(
        BtNewFtpForm.sizeError(download, allowBlank = false),
        BtNewFtpForm.sizeError(upload, allowBlank = false),
        BtNewFtpForm.sizeError(maxSize, allowBlank = false)
    )
    val error = problems.firstOrNull { it != null }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("「${user.name}」的限速与容量", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                user.quotaText.ifBlank { "面板现在没给这行的容量信息。" },
                fontSize = 12.sp,
                color = t.textSecondary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            SizeField("下载带宽", download, { download = it }, "写 0 表示不限，比如 1MB")
            Spacer(Modifier.height(10.dp))
            SizeField("上传带宽", upload, { upload = it }, "写 0 表示不限，比如 512KB")
            Spacer(Modifier.height(10.dp))
            SizeField("空间上限", maxSize, { maxSize = it }, "比如 100MB、1GB；写 0 表示不限量")
            Spacer(Modifier.height(10.dp))
            Text(
                error ?: "这三格是整体写进去的：面板没有「只改其中一格」的口，也没有读回的口。" +
                    "发出去只回一句「修改成功」，实际生效值这里显示不了，要确认只能去面板网页那一行看。",
                fontSize = 11.5.sp,
                color = if (error != null) t.warnText else t.textLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = { onSubmit(download, upload, maxSize) },
                enabled = !busy && error == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "正在发给面板…" else "发给面板", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun SizeField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    val t = LocalNoLine.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        supportingText = { Text(hint, color = t.textLight) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        shape = MaterialTheme.shapes.medium,
        colors = noLineFieldColors()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FtpLogsSheet(username: String, logs: BtFtpLogs?, loading: Boolean, onDismiss: () -> Unit) {
    val t = LocalNoLine.current
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("「$username」的日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoLineChip(label = "登录日志", selected = tab == 0, onClick = { tab = 0 })
                NoLineChip(label = "操作日志", selected = tab == 1, onClick = { tab = 1 })
            }
            Spacer(Modifier.height(8.dp))
            logs?.let {
                Text(
                    buildString {
                        append(
                            if (it.recording.isBlank()) "" else "面板记录开关回的是「${it.recording}」。"
                        )
                        append("登录 ${it.loginTotal ?: it.login.size} 条 · 操作 ${it.actionTotal ?: it.action.size} 条")
                    },
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            if (loading) {
                LinearProgressIndicator(color = t.accent, trackColor = t.hairline)
                Spacer(Modifier.height(10.dp))
            }
            logs?.failures?.forEach { source ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(t.warnBg, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text("「${source.label}」这一格没读到", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = t.warnText)
                    Spacer(Modifier.height(3.dp))
                    Text(source.failure.title, fontSize = 11.5.sp, color = t.textMain, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .background(t.surfaceSoft, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                val lines = when (tab) {
                    0 -> logs?.login.orEmpty()
                    else -> logs?.action.orEmpty()
                }
                if (lines.isEmpty()) {
                    Text(
                        when {
                            loading -> "正在从面板拉日志…"
                            logs?.isEmpty == true -> "这一格没有记录。面板的 FTP 日志开关是只读的，这里不能替你打开。"
                            else -> "还没读到日志。"
                        },
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = t.textSecondary,
                        lineHeight = 17.sp
                    )
                } else {
                    lines.forEach { line ->
                        Text(
                            line.text,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = t.textMain,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

/** 接口做不到的两件事，写在列表底下，免得人找到第三层还找不到按钮。 */
@Composable
private fun FtpLimitsCard() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("这一格面板接口做不到", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        listOf(
            "改备注、改根目录：面板网页点两下就行，接口侧六个候选 action 名实测全回「指定参数无效!」。",
            "改 FTP 端口：接口有 setPort，但它改的是整台的服务端口，一动手所有账户都要重连，这里没有摆出来。",
            "打开 / 关闭日志记录：面板那个开关实测按不动，所以日志只做只读。"
        ).forEach { line ->
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text("· ", fontSize = 12.sp, color = t.textSecondary)
                Text(line, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
