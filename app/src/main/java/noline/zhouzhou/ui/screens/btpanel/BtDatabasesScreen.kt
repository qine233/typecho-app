package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
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
import noline.zhouzhou.data.model.BtDatabase
import noline.zhouzhou.data.model.BtDbCharsets
import noline.zhouzhou.data.model.BtNewDatabaseForm
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/** 列表页上会动的两只手，删库要亲手打「删除」。 */
private sealed interface DbPending {
    data class Delete(val db: BtDatabase) : DbPending
    data class Backup(val db: BtDatabase) : DbPending
}

/**
 * 数据库管理列表：面板「数据库」那一页的手机版。
 *
 * 一行一个库：库名、`账号@访问来源`、占用、备份份数都在行上；点进去是这一库的三个页签。
 * 建库、备份、删库三只手在列表上就能做完。行上**永远不出现密码**——
 * 面板这张表每行都带明文 `password`，翻模型的时候已经整个丢掉了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtDatabasesScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onOpenDb: (BtDatabase) -> Unit,
    onOpenTools: () -> Unit
) {
    val viewModel = typechoViewModel<BtDatabasesViewModel>(appContainer) { BtDatabasesViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pending by remember { mutableStateOf<DbPending?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    when (val action = pending) {
        is DbPending.Delete -> ConfirmAction(
            title = "删除「${action.db.name}」？",
            body = "面板会把库、账号和它的权限一起删掉，里面所有数据当场没了。" +
                "唯一不会被带走的是备份：/www/backup/database/mysql/${action.db.name}/ 那个目录会留在服务器上。",
            keyword = "删除",
            mustType = true,
            onConfirm = {
                viewModel.deleteDatabase(action.db)
                pending = null
            },
            onDismiss = { pending = null }
        )
        is DbPending.Backup -> ConfirmAction(
            title = "立刻备份「${action.db.name}」？",
            body = "面板当场打一发 .sql.gz，落在 /www/backup/database/mysql/${action.db.name}/ 下面。" +
                "库大的时候这一步要占几秒到几十秒，期间这一页都是转的。",
            keyword = "备份",
            mustType = false,
            onConfirm = {
                viewModel.backup(action.db)
                pending = null
            },
            onDismiss = { pending = null }
        )
        null -> Unit
    }

    if (adding) {
        AddDatabaseSheet(
            busy = state.busy,
            onDismiss = { adding = false },
            onSubmit = { name, user, password, address, charset, remark ->
                adding = false
                viewModel.createDatabase(name, user, password, address, charset, remark)
            }
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
                    Text("数据库管理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenTools) {
                        Icon(Icons.Default.Build, contentDescription = "MySQL 工具", modifier = Modifier.size(21.dp), tint = t.accent)
                    }
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "建库", modifier = Modifier.size(21.dp), tint = t.accent)
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
                    label = { Text("按库名或备注找") },
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
                    "这一张表的关键词是本机筛的：面板那个搜索参数对数据库表是个摆设，带什么词都回全表。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            val heading = state.total?.let { "共 $it 个数据库，已取 ${state.databases.size} 个" }
                ?: "已取 ${state.databases.size} 个数据库"
            Text(heading, fontSize = 12.sp, color = t.textLight)
            Spacer(Modifier.height(6.dp))

            if (state.databases.isEmpty() && !state.busy) {
                Text(
                    if (state.keyword.isNotBlank()) "这个关键词没匹配到库，面板那张表里也没有别的行。"
                    else if (!state.configured) "还没配好面板地址和密钥，回到宝塔那一页先保存自检。"
                    else "面板回了一个空列表：这台服务器上一个用面板建过的库都没有。",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
                )
            }

            state.databases.forEachIndexed { index, db ->
                if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 6.dp))
                DatabaseRow(
                    db = db,
                    busy = state.busy,
                    onOpen = { onOpenDb(db) },
                    onBackup = { pending = DbPending.Backup(db) },
                    onDelete = { pending = DbPending.Delete(db) }
                )
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

            Spacer(Modifier.height(14.dp))
            ToolsLink(onOpen = onOpenTools)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DatabaseRow(
    db: BtDatabase,
    busy: Boolean,
    onOpen: () -> Unit,
    onBackup: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                db.name.ifBlank { "（面板没给库名）" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            if (db.engine.isNotBlank()) StatPill(db.engine, tint = t.textSecondary)
        }
        if (db.remark.isNotBlank() && db.remark != db.name) {
            Text(db.remark, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (db.subtitle.isNotBlank()) {
            Text(
                db.subtitle,
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        // 访问来源是整个互联网开放的话，这一行必须染成警告色：这一格只靠密码挡着。
        if (BtNewDatabaseForm.isWideOpen(db.accept)) {
            Spacer(Modifier.height(4.dp))
            StatPill("允许任意地址连接", tint = t.warnText)
        }
        if (db.addTime.isNotBlank()) {
            Text("建于 ${db.addTime}", fontSize = 11.sp, color = t.textLight, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onBackup,
                enabled = !busy && db.id.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("备份", fontSize = 12.5.sp, color = t.textSecondary) }
            TextButton(
                onClick = onDelete,
                enabled = !busy && db.id.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("删库", fontSize = 12.5.sp, color = t.healthOver) }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("管理", fontSize = 12.5.sp, color = t.textSecondary) }
        }
    }
}

/** MySQL 工具那一页的文字入口：它不是一个库，是整台服务器。 */
@Composable
private fun ToolsLink(onOpen: () -> Unit) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Build, contentDescription = null,
            modifier = Modifier.size(15.dp), tint = t.textSecondary
        )
        Spacer(Modifier.width(8.dp))
        Text("MySQL 运行状况与工具", fontSize = 13.sp, color = t.textSecondary, modifier = Modifier.weight(1f))
        Text("超时 · Binlog · 守护进程 · 账户", fontSize = 11.sp, color = t.textLight)
    }
}

/**
 * 建库面板。按面板建库那表单的顺序摆：名 → 账号 → 密码 → 访问来源 → 字符集 → 备注。
 *
 * 「关联网站」那一格没做：面板要的是站点 id，而这一页拿不到站点列表是常事，
 * 填错会把库绑到一个不相干的站上，宁可让人去面板上勾。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDatabaseSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, user: String, password: String, address: String, charset: String, remark: String) -> Unit
) {
    val t = LocalNoLine.current
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    // 账号名跟着库名走，但一旦手动改过就不再跟（模拟器实测：靠比对旧值只会同步进第一个字）。
    var userTyped by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(BtNewDatabaseForm.randomPassword()) }
    var address by remember { mutableStateOf("127.0.0.1") }
    var charset by remember { mutableStateOf("utf8mb4") }
    var remark by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val nameError = BtNewDatabaseForm.nameError(name).takeIf { name.isNotBlank() }
    val userError = BtNewDatabaseForm.userError(user).takeIf { user.isNotBlank() }
    val passwordError = BtNewDatabaseForm.passwordError(password)
    val addressError = BtNewDatabaseForm.addressError(address)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("建一个数据库", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "面板这一步会同时建库、建账号、把整库权限授给它，任何一半失败这库就不成立。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; if (!userTyped) user = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("数据库名") },
                placeholder = { Text("my_blog") },
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it, color = t.warnText) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it; userTyped = true },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("数据库账号") },
                isError = userError != null,
                supportingText = { userError?.let { Text(it, color = t.warnText) } },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("账号密码") },
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
            TextButton(onClick = { password = BtNewDatabaseForm.randomPassword() }) {
                Text("换一串随机密码", fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("访问来源", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "127.0.0.1" to "只有本机",
                    "%" to "任意地址",
                    "localhost" to "本机别名"
                ).forEach { (value, label) ->
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
                            wide -> "选了「任意地址」，这个库就对整个互联网开放，只靠上面那串密码挡着。"
                            else -> "只有列在这里的地址能连这个库。"
                        },
                        color = if (addressError != null || wide) t.warnText else t.textLight
                    )
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(14.dp))
            Text("字符集", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BtDbCharsets.forEach { value ->
                    NoLineChip(label = value, selected = charset == value, onClick = { charset = value })
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("备注（可留空）") },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = { onSubmit(name, user, password, address, charset, remark) },
                enabled = !busy && nameError == null && userError == null &&
                    passwordError == null && addressError == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "正在发给面板…" else "建这个库", fontSize = 14.sp) }
        }
    }
}
