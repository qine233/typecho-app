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
import noline.zhouzhou.data.model.BtField
import noline.zhouzhou.data.model.BtMysqlUserForm
import noline.zhouzhou.data.model.BtNewDatabaseForm
import noline.zhouzhou.data.model.btByteSize
import noline.zhouzhou.data.repository.BtMysqlOverview
import noline.zhouzhou.data.repository.BtSourceFailure
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * MySQL 这一页：面板「数据库 → MySQL 管理」那一整排的手机版。
 *
 * 只读的四块（运行时计数、内存参数、服务器信息、安装状态）进来一次全取；
 * 六只能动的手都在这一页：改超时、清 Binlog、开关守护进程、加账户、删账户、读日志。
 * 日志正文能到几百 KB，所以它只在点「读日志」之后才发请求，并且收在弹层里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtMysqlToolsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtMysqlToolsViewModel>(appContainer) { BtMysqlToolsViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var ask by remember { mutableStateOf<MysqlPending.Confirm?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    ask?.let { pending ->
        ConfirmAction(
            title = pending.label,
            body = pending.body,
            keyword = pending.keyword,
            mustType = pending.mustType,
            onConfirm = { pending.run(); ask = null },
            onDismiss = { ask = null }
        )
    }

    if (addingAccount) {
        AddAccountSheet(
            busy = state.busy,
            onDismiss = { addingAccount = false },
            onSubmit = { name, password, address ->
                addingAccount = false
                viewModel.addAccount(name, password, address)
            }
        )
    }

    if (showLogs) {
        MysqlLogsSheet(
            error = state.logs?.error.orEmpty(),
            slow = state.logs?.slow.orEmpty(),
            errorPath = state.logs?.errorPath.orEmpty(),
            loading = state.loadingLogs,
            onLoad = viewModel::loadLogs,
            onDismiss = { showLogs = false }
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MySQL 管理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                        Text(
                            state.overview?.server?.port?.takeIf { it.isNotBlank() }?.let { "监听端口 $it" }
                                ?: "这台服务器上的 MySQL",
                            fontSize = 11.sp,
                            color = t.textLight,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { addingAccount = true }) {
                        Icon(Icons.Default.Add, contentDescription = "加账户", modifier = Modifier.size(21.dp), tint = t.accent)
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

            val overview = state.overview
            if (overview == null && !state.busy) {
                Text(
                    if (!state.configured) "还没配好面板地址和密钥，回到宝塔那一页先保存自检。"
                    else "这一页并发问面板十一张口，一张都没回来。看看是不是那 20 次签名失败的锁还没过。",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
                )
                Spacer(Modifier.height(24.dp))
            }

            overview?.let { data ->
                ServerCard(data)
                Spacer(Modifier.height(12.dp))
                FieldsCard("运行计数", data.runtime, "这一格是 MySQL 自己的状态计数器，只读。面板给多少摊多少。")
                Spacer(Modifier.height(12.dp))
                FieldsCard("内存参数", data.memory, "改这些要重启 MySQL，面板走的是另一个口，这一页只做只读展示。")
                Spacer(Modifier.height(12.dp))
                TimeoutCard(data) { interactive, wait, days, lifetime ->
                    ask = MysqlPending.Confirm(
                        label = "改超时设置？",
                        body = "空着的那一格面板按「保持原值」处理。写「Binlog 天数」这一格会让面板连带把 " +
                            "binlog_expire_logs_seconds 按天数重算（实测过），所以这一发会动两个参数。",
                        keyword = "超时",
                        mustType = false,
                        run = { viewModel.saveTimeout(interactive, wait, days, lifetime) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                BinlogCard(data) { days ->
                    ask = MysqlPending.Confirm(
                        label = "清理 N 天之前的 Binlog？",
                        body = "面板会把 $days 天之前的 binlog 文件清掉，正在写的当前文件它自己会跳过。" +
                            "如果这台上挂了主从复制或者用 binlog 做恢复，清早了会断链——这一步不可逆。",
                        keyword = "清理",
                        mustType = true,
                        run = { viewModel.clearBinlogs(days) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                GuardCard(data) { enable ->
                    ask = MysqlPending.Confirm(
                        label = if (enable) "开启 MySQL 守护进程？" else "关闭 MySQL 守护进程？",
                        body = if (enable) {
                            "面板会建一条每 ${data.guard.everyMinutes.ifBlank { "5" }} 分钟的计划任务「[勿删]MySQL守护进程」。" +
                                "实测这个口不看状态参数：带 status=0 发过去同样是建一条，不是关。"
                        } else {
                            "关闭只能把那条计划任务删掉（走的是 crontab 模块的 DelCrontab）。" +
                                "任务编号是从面板读回来的，读不到这里就不发。"
                        },
                        keyword = if (enable) "开启" else "关闭",
                        mustType = !enable,
                        run = { viewModel.setGuard(enable) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                AccountCard(data) { entry ->
                    ask = MysqlPending.Confirm(
                        label = "删除账户 $entry？",
                        body = "只删这一个来源主机上的授权行。同一个账户在 % 和 localhost 上是两条记录，" +
                            "别的来源不受影响；它已经拿到的库权限会一起回收。",
                        keyword = "删除",
                        mustType = true,
                        run = { viewModel.deleteAccount(entry) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                NativeDatabaseCard(state.nativeDatabases, state.busy, viewModel::loadNativeDatabases)
                Spacer(Modifier.height(12.dp))
                LogLinkCard(state.logs != null, state.loadingLogs) {
                    if (state.logs == null) viewModel.loadLogs()
                    showLogs = true
                }
                Spacer(Modifier.height(12.dp))
                ServerListCard(data)
                data.failures.forEach { SourceFailureLine(it) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 这一页上会动的手都要先问一句，删账户和清 binlog 还要亲手打关键词。 */
private sealed interface MysqlPending {
    data class Confirm(
        val label: String,
        val body: String,
        val keyword: String,
        val mustType: Boolean,
        val run: () -> Unit
    ) : MysqlPending
}

@Composable
private fun ServerCard(data: BtMysqlOverview) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("MySQL 服务", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        DetailLine("数据目录", data.server.dataDir.ifBlank { "面板没给" })
        DetailLine("监听端口", data.server.port.ifBlank { "面板没给" })
        DetailLine("安装状态", data.installStatus.ifBlank { "面板没给" })
        if (data.loginGuard.enabled) {
            Spacer(Modifier.height(6.dp))
            StatPill(
                "登录失败限流已开：错 ${data.loginGuard.threshold ?: "?"} 次起，延迟 ${data.loginGuard.minDelayMs ?: "?"}–${data.loginGuard.maxDelayMs ?: "?"} 毫秒",
                tint = t.warnText
            )
        }
    }
}

@Composable
private fun FieldsCard(title: String, fields: List<BtField>, footnote: String) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        if (fields.isEmpty()) {
            Text("面板这一格回的是空的。", fontSize = 12.sp, color = t.textLight, lineHeight = 18.sp)
        } else {
            fields.forEach { field ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(field.label, fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.weight(1f))
                    Text(
                        field.value.ifBlank { "—" },
                        fontSize = 12.sp,
                        color = t.textMain,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(footnote, fontSize = 11.sp, color = t.textLight, lineHeight = 16.sp)
    }
}

/**
 * 超时四格。空着表示不改——仓库层会把当前值原样填回去发，
 * 因为面板对缺参的处理是「保持原值」，而它连带改了什么我们预知不了。
 */
@Composable
private fun TimeoutCard(data: BtMysqlOverview, onSubmit: (String, String, String, String) -> Unit) {
    val t = LocalNoLine.current
    val timeout = data.timeout
    var interactive by remember(timeout.interactiveSeconds) { mutableStateOf("") }
    var wait by remember(timeout.waitSeconds) { mutableStateOf("") }
    var days by remember(timeout.expireLogDays) { mutableStateOf("") }
    var lifetime by remember(timeout.passwordLifetimeDays) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("超时设置", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        timeout.rows.forEach { field -> DetailLine(field.label, field.value) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = interactive,
                onValueChange = { interactive = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("交互超时（秒）") },
                placeholder = timeout.interactiveSeconds?.let { { Text("不改，现在是 $it") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            OutlinedTextField(
                value = wait,
                onValueChange = { wait = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("非交互超时（秒）") },
                placeholder = timeout.waitSeconds?.let { { Text("不改，现在是 $it") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = days,
                onValueChange = { days = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Binlog 保留（天）") },
                placeholder = timeout.expireLogDays?.let { { Text("不改，现在是 $it") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            OutlinedTextField(
                value = lifetime,
                onValueChange = { lifetime = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("密码有效期（天）") },
                placeholder = timeout.passwordLifetimeDays?.let { { Text("不改，现在是 $it") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "0 在这几格里各有自己的意思：Binlog 天数 0 是「不按天保留」，密码有效期 0 是「永不过期」。",
            fontSize = 11.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSubmit(interactive, wait, days, lifetime) },
            enabled = interactive.isNotBlank() || wait.isNotBlank() || days.isNotBlank() || lifetime.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("发给面板", fontSize = 13.sp) }
    }
}

@Composable
private fun BinlogCard(data: BtMysqlOverview, onClear: (String) -> Unit) {
    val t = LocalNoLine.current
    var days by remember { mutableStateOf("7") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Binlog 文件", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
            StatPill("${data.binlogs.size} 个", tint = t.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        if (data.binlogs.isEmpty()) {
            Text("面板没列出任何 binlog 文件：要么没开，要么已经被清了。", fontSize = 12.sp, color = t.textLight, lineHeight = 18.sp)
        } else {
            data.binlogs.take(20).forEach { file ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        fontSize = 12.sp,
                        color = t.textMain,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    if (file.current) StatPill("正在写", tint = t.accent)
                    Text(
                        btByteSize(file.sizeBytes).ifBlank { "—" },
                        fontSize = 11.5.sp,
                        color = t.textSecondary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (data.binlogs.size > 20) {
                Text("另外 ${data.binlogs.size - 20} 个没摊开。", fontSize = 11.sp, color = t.textLight, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = days,
                onValueChange = { days = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("清理几天之前的") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Button(onClick = { onClear(days) }, enabled = days.isNotBlank()) {
                Text("清理", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GuardCard(data: BtMysqlOverview, onToggle: (Boolean) -> Unit) {
    val t = LocalNoLine.current
    val guard = data.guard
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("守护进程", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (guard.enabled) {
                        "已开：计划任务「${guard.name.ifBlank { "[勿删]MySQL守护进程" }}」每 ${guard.everyMinutes.ifBlank { "5" }} 分钟查一次"
                    } else "没配：MySQL 掉了面板不会自己拉起来。",
                    fontSize = 11.5.sp,
                    color = t.textSecondary,
                    lineHeight = 17.sp
                )
            }
            Switch(checked = guard.enabled, onCheckedChange = { onToggle(it) })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "这一格其实是计划任务：开 = 建一条 crontab，关 = 删那条 crontab。面板的开关参数是摆设（实测）。",
            fontSize = 11.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
    }
}

/** 账户与主机：一个用户在几个来源上就是几条，删的时候按 `user@host` 整条来。 */
@Composable
private fun AccountCard(data: BtMysqlOverview, onDelete: (String) -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("账户与权限", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        if (data.accounts.isEmpty()) {
            Text("面板这一格回的是空的。", fontSize = 12.sp, color = t.textLight, lineHeight = 18.sp)
        } else {
            data.accounts.forEach { account ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        account.user,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.textMain,
                        fontFamily = FontFamily.Monospace
                    )
                    account.hosts.forEach { host ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    listOfNotNull(
                                        host.host,
                                        when (host.hasPassword) {
                                            true -> "有密码"
                                            false -> "无密码"
                                            null -> null
                                        },
                                        host.passwordChanged.takeIf { it.isNotBlank() }?.let { "改于 $it" }
                                    ).joinToString(" · "),
                                    fontSize = 11.5.sp,
                                    color = t.textSecondary
                                )
                                if (host.grants.isNotEmpty()) {
                                    Text(
                                        host.grants.joinToString("、"),
                                        fontSize = 11.sp,
                                        color = t.textLight,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                            TextButton(
                                onClick = { onDelete("${account.user}@${host.host}") },
                                enabled = host.host.isNotBlank()
                            ) { Text("删", fontSize = 12.5.sp, color = t.healthOver) }
                        }
                    }
                }
                HorizontalDivider(color = t.hairline)
            }
        }
        if (data.userHosts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "面板的账户清单：" + data.userHosts.joinToString("、"),
                fontSize = 11.sp,
                color = t.textLight,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "「无密码」的那几条是面板里那些库自带的账号行——密码面板回的是明文，这个 App 一个字段都不接，所以这里只说有没有。",
            fontSize = 11.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun NativeDatabaseCard(names: List<String>?, busy: Boolean, onLoad: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MySQL 原生库", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
            names?.let { StatPill("${it.size} 个", tint = t.textSecondary) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "这一份是 MySQL 自己数的库，包含面板记录之外的东西（information_schema、mysql、performance_schema、sys，" +
                "以及有人在命令行里手建的库）。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )
        names?.let { list ->
            Spacer(Modifier.height(8.dp))
            Text(
                list.joinToString("、"),
                fontSize = 12.sp,
                color = t.textSecondary,
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLoad, enabled = !busy || names == null) {
            Text(if (names == null) "读一次原生库清单" else "再读一次", fontSize = 13.sp)
        }
    }
}

@Composable
private fun LogLinkCard(loaded: Boolean, loading: Boolean, onOpen: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("错误日志 · 慢查询日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Text(
                if (loading) "正在从面板拉正文…" else if (loaded) "已经读过一次，点开看正文。" else "正文能到几百 KB，所以只在点开之后才发请求。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
        }
        Text("打开", fontSize = 12.5.sp, color = t.accent)
    }
}

/** 面板上「服务器」那一格：多服务器管理列表。这一页的所有读都固定走本机（sid=0）。 */
@Composable
private fun ServerListCard(data: BtMysqlOverview) {
    val t = LocalNoLine.current
    if (data.servers.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("面板登记的数据库服务器", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        data.servers.forEach { server ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.address.ifBlank { server.host },
                        fontSize = 12.sp,
                        color = t.textMain,
                        fontFamily = FontFamily.Monospace
                    )
                    if (server.remark.isNotBlank()) {
                        Text(server.remark, fontSize = 11.sp, color = t.textLight)
                    }
                }
                StatPill(if (server.isLocal) "本机" else "远程", tint = if (server.isLocal) t.accent else t.textSecondary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "这一页读和写都只打到本机那一台（sid=0）。远程服务器要在面板网页上先加进来，这里的接口带不上它的凭据。",
            fontSize = 11.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun SourceFailureLine(source: BtSourceFailure) {
    val t = LocalNoLine.current
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

@Composable
private fun DetailLine(label: String, value: String) {
    val t = LocalNoLine.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.5.sp, color = t.textLight, modifier = Modifier.width(86.dp))
        Text(value, fontSize = 11.5.sp, color = t.textSecondary, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

/** 加账户：名字和密码的规则跟建库那套一样，面板查得最严的就是这两格。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountSheet(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String, String) -> Unit) {
    val t = LocalNoLine.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(BtNewDatabaseForm.randomPassword()) }
    var address by remember { mutableStateOf("127.0.0.1") }
    var revealed by remember { mutableStateOf(false) }
    val nameError = BtMysqlUserForm.nameError(name).takeIf { name.isNotBlank() }
    val passwordError = BtMysqlUserForm.passwordError(password)
    val addressError = BtMysqlUserForm.addressError(address)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("加一个 MySQL 账户", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "面板这个口只建账户、不授权：建完到下面「账户与权限」那一格看它实际拿到了什么。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("账户名") },
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it, color = t.warnText) } },
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
                        passwordError ?: "这一格建完就看不回来了，先自己存一份。",
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
            TextButton(onClick = { password = BtNewDatabaseForm.randomPassword() }) {
                Text("换一串随机密码", fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("允许从哪些地址登录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("127.0.0.1" to "只有本机", "%" to "任意地址").forEach { (value, label) ->
                    NoLineChip(label = label, selected = address == value, onClick = { address = value })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("或者自己写（逗号分隔）") },
                isError = addressError != null,
                supportingText = {
                    val wide = BtNewDatabaseForm.isWideOpen(address)
                    Text(
                        when {
                            addressError != null -> addressError
                            wide -> "选了「任意地址」，这个账户就能从整个互联网尝试登录，只靠上面那串密码挡着。"
                            else -> "只有列在这里的地址能用这个账户登录。"
                        },
                        color = if (addressError != null || wide) t.warnText else t.textLight
                    )
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = { onSubmit(name, password, address) },
                enabled = !busy && nameError == null && passwordError == null && addressError == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "正在发给面板…" else "建这个账户", fontSize = 14.sp) }
        }
    }
}

/** 日志弹层：两份正文收在一个底部抽屉里，各自能滚。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MysqlLogsSheet(
    error: String,
    slow: String,
    errorPath: String,
    loading: Boolean,
    onLoad: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalNoLine.current
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MySQL 日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
                TextButton(onClick = onLoad, enabled = !loading) { Text(if (loading) "读取中…" else "重新读", fontSize = 12.5.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoLineChip(label = "错误日志", selected = tab == 0, onClick = { tab = 0 })
                NoLineChip(label = "慢查询日志", selected = tab == 1, onClick = { tab = 1 })
            }
            Spacer(Modifier.height(10.dp))
            if (loading) {
                LinearProgressIndicator(color = t.accent, trackColor = t.hairline)
                Spacer(Modifier.height(10.dp))
            }
            if (tab == 0 && errorPath.isNotBlank()) {
                Text(
                    "面板这次只给了路径没给正文：$errorPath。这一格只能到服务器上打开看。",
                    fontSize = 12.sp,
                    color = t.warnText,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
            }
            val body = if (tab == 0) error else slow
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .background(t.surfaceSoft, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    body.ifBlank {
                        when {
                            loading -> "正在拉正文…"
                            tab == 0 -> if (errorPath.isNotBlank()) "面板只回了路径。" else "还没读到错误日志。"
                            else -> "还没读到慢查询日志：面板没开 slow_query_log 时这一格就是空的。"
                        }
                    },
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = t.textMain,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
