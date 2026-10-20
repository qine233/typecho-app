package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtProxyEngine
import noline.zhouzhou.data.model.BtReverseProxyEntry
import noline.zhouzhou.data.model.BtReverseProxyForm
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * 反向代理模块的列表页：面板「反向代理」那一格的手机版。
 *
 * 一行一个代理站点，手有四只：新建、停用/启用、改备注、删除，点行进详情页管规则、缓存、
 * gzip、websocket、日志去向、域名探活和访问日志。
 *
 * 有两件事这一页做不到，界面上直说：
 * **改转发目标没有单独接口**（面板自己也是「加一条新的、删掉旧的」拼的），
 * 以及**引擎（nginx / Apache / OpenResty）问不出来**——它是 URL 里的一段，不是参数，
 * 所以由人自己选，选错了那一块就是空的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtReverseProxyScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onOpenProxy: (BtReverseProxyEntry) -> Unit
) {
    val viewModel = typechoViewModel<BtReverseProxyViewModel>(appContainer) { BtReverseProxyViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pending by remember { mutableStateOf<ProxyPending?>(null) }
    var creating by remember { mutableStateOf(false) }
    var remarkFor by remember { mutableStateOf<BtReverseProxyEntry?>(null) }

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
    if (creating) {
        CreateProxySheet(
            busy = state.busy,
            onDismiss = { creating = false },
            onSubmit = { form ->
                creating = false
                viewModel.create(form)
            }
        )
    }
    remarkFor?.let { entry ->
        RemarkDialog(
            entry = entry,
            busy = state.busy,
            onDismiss = { remarkFor = null },
            onSubmit = { text ->
                remarkFor = null
                viewModel.setRemark(entry, text)
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
                    Text("反向代理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建代理", modifier = Modifier.size(21.dp), tint = t.accent)
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

            Text("这批代理挂在", fontSize = 12.sp, color = t.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtProxyEngine.entries.forEach { engine ->
                    NoLineChip(
                        label = engine.label,
                        selected = state.engine == engine,
                        onClick = { viewModel.selectEngine(engine) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "面板的接口把 web 软件名写在 URL 里（`/mod/proxy/com/…/${state.engine.wire}`），" +
                    "也没有接口能问这台装的是哪一个。装的是 Apache 却选了 Nginx，" +
                    "读回来就是一张空表，不是这台真的一条代理都没有。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = viewModel::setKeyword,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("按域名或站点名找") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                IconButton(onClick = viewModel::search, enabled = !state.busy) {
                    Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
                }
            }
            Spacer(Modifier.height(8.dp))

            Text(
                state.total?.let { "共 $it 条代理，已取 ${state.entries.size} 条" }
                    ?: "已取 ${state.entries.size} 条代理",
                fontSize = 12.sp,
                color = t.textLight
            )
            Spacer(Modifier.height(6.dp))

            if (state.entries.isEmpty() && !state.busy) {
                Text(
                    if (state.keyword.isNotBlank()) "面板按这个词回的是空表。把搜索词清空再读一次，" +
                        "能读出行来说明这个词面板不认（它那一格按什么字段筛，文档没说死）。"
                    else if (!state.configured) "还没配好面板地址和密钥，回到宝塔那一页先保存自检。"
                    else "上面那个引擎换成别的再读一次试试：这一块的表是按 web 软件分开的，" +
                        "Nginx 底下没有不等于 Apache 底下也没有。",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
                )
            }

            state.entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 6.dp))
                ProxyEntryRow(
                    entry = entry,
                    busy = state.busy,
                    onOpen = { onOpenProxy(entry) },
                    onToggle = {
                        val turningOn = entry.enabled != true
                        pending = ProxyPending(
                            label = if (turningOn) "启用「${entry.title}」？" else "停用「${entry.title}」？",
                            body = if (turningOn) {
                                "面板把整个站点翻回运行中，原来的规则和目录都不动。"
                            } else {
                                "面板把整个站点停用：这一底下的代理规则全部不再转发，" +
                                    "配置和日志都留着，随时能启用回来。"
                            },
                            keyword = "",
                            mustType = false,
                            run = { viewModel.setEnabled(entry, turningOn) }
                        )
                    },
                    onRemark = { remarkFor = entry },
                    onDelete = {
                        pending = ProxyPending(
                            label = "删除「${entry.title}」？",
                            body = "这一发带走的是面板数据库里的那条记录、它写进 " +
                                "${state.engine.label} 的那段配置，还有日志目录。" +
                                "目标服务本身还在，但域名从此不再转发过来，配在它上面的证书也不会跟着走。",
                            keyword = "删除",
                            mustType = true,
                            run = { viewModel.delete(entry) }
                        )
                    }
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
            ProxyLimitsCard()
            Spacer(Modifier.height(28.dp))
        }
    }
}

private data class ProxyPending(
    val label: String,
    val body: String,
    val keyword: String,
    val mustType: Boolean,
    val run: () -> Unit
)

@Composable
private fun ProxyEntryRow(
    entry: BtReverseProxyEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRemark: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.title.ifBlank { "（面板没给站点名）" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            when (entry.enabled) {
                true -> StatPill("运行中", tint = t.accent)
                false -> StatPill("已停用", tint = t.warnText)
                // 面板这一格给过 "1"/"0"，也给过别的写法；认不出来就摊原文，不猜。
                null -> if (entry.statusText.isNotBlank()) StatPill(entry.statusText, tint = t.textLight)
            }
        }
        if (entry.path.isNotBlank()) {
            Text(
                entry.path,
                fontSize = 11.5.sp,
                color = t.textLight,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (entry.remark.isNotBlank() && entry.remark != entry.title) {
            Text(entry.remark, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        val times = listOfNotNull(
            entry.addTime.takeIf { it.isNotBlank() }?.let { "建于 $it" },
            entry.expireTime.takeIf { it.isNotBlank() && it != "0000-00-00" }?.let { "到期 $it" }
        )
        if (times.isNotEmpty()) {
            Text(times.joinToString(" · "), fontSize = 11.sp, color = t.textLight)
        }
        if (entry.extraKeys.isNotEmpty()) {
            Text(
                "面板还给了：${entry.extraKeys.joinToString("、")}",
                fontSize = 11.sp,
                color = t.textLight,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ProxyRowAction("规则和设置", enabled = !busy, onClick = onOpen)
            ProxyRowAction(
                text = if (entry.enabled == true) "停用" else "启用",
                enabled = !busy && entry.id.isNotBlank(),
                onClick = onToggle
            )
            ProxyRowAction("备注", enabled = !busy && entry.id.isNotBlank(), onClick = onRemark)
            ProxyRowAction("删除", enabled = !busy && entry.id.isNotBlank(), color = t.healthOver, onClick = onDelete)
        }
    }
}

@Composable
private fun ProxyRowAction(
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
private fun RemarkDialog(
    entry: BtReverseProxyEntry,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf(entry.remark) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${entry.title}」的备注") },
        text = {
            Column {
                Text(
                    "面板这一格叫 `ps`，只存这一句话；改完重读列表才会变。",
                    fontSize = 12.sp,
                    color = LocalNoLine.current.textSecondary,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = !busy) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProxySheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (BtReverseProxyForm) -> Unit
) {
    val t = LocalNoLine.current
    var domains by remember { mutableStateOf("") }
    var proxyPass by remember { mutableStateOf("http://127.0.0.1:") }
    var proxyPath by remember { mutableStateOf("/") }
    var proxyHost by remember { mutableStateOf("\$http_host") }
    var proxyType by remember { mutableStateOf("http") }
    var remark by remember { mutableStateOf("") }
    val form = BtReverseProxyForm(domains, proxyPass, proxyPath, proxyHost, proxyType, remark)
    val problem = form.problem().takeIf { domains.isNotBlank() || proxyPass.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("新建一个反向代理站点", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "面板会为此新建一个站点：域名写进它的 server_name，配置追加进 web 软件那台机器上的" +
                    "那段 proxy 配置。目标地址是这台服务器上真实跑着的服务，填错了这个域名就 502。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))

            LabeledField("域名", domains, { domains = it }, "例：api.example.com", singleLine = true)
            problem?.let {
                Text(it, fontSize = 11.5.sp, color = t.warnText, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
            }
            LabeledField(
                "目标地址",
                proxyPass,
                { proxyPass = it },
                "例：http://127.0.0.1:8080",
                singleLine = true,
                keyboard = KeyboardType.Uri
            )
            LabeledField("匹配路径", proxyPath, { proxyPath = it }, "例：/ 或 /api", singleLine = true)
            LabeledField("转发时的 Host", proxyHost, { proxyHost = it }, "默认 \$http_host", singleLine = true)

            Text("代理类型", fontSize = 12.sp, color = t.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("http", "unix").forEach { type ->
                    NoLineChip(label = type, selected = proxyType == type, onClick = { proxyType = type })
                }
            }
            Text(
                if (proxyType == "unix") "unix：目标地址填的是 socket 文件路径（比如 /run/x.sock），不是 URL。"
                else "文档里这一格只有 http 和 unix 两个值。websocket 不在这儿开——它是整站那一层的开关，去详情页的开关那一格按。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(12.dp))
            LabeledField("备注", remark, { remark = it }, "可选", singleLine = true)

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onSubmit(form) },
                enabled = !busy && form.canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) { Text("交给面板创建", fontSize = 13.5.sp) }
            Spacer(Modifier.height(8.dp))
            Text(
                "文档写死主域名不能用泛解析（不能带 `*`），这一格本地就拦了：面板那边一次失败的调用" +
                    "也记在次数里，攒够 20 次整个 API 会锁一小时。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    Text(label, fontSize = 12.sp, color = LocalNoLine.current.textSecondary)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        placeholder = { Text(placeholder, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = MaterialTheme.shapes.medium,
        colors = noLineFieldColors()
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ProxyLimitsCard() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("这一页做不到、或者要留意的", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        listOf(
            "**改一条代理的转发目标**：面板没有这个接口。网页上看着是改好了，其实后台是「加一条新的、" +
                "再把旧的删掉」。这一版不假装一步能做成，详情页里改目标要自己动手：先加新的，确认转发了再删旧的。",
            "**Basic 认证、IP 限制、内容替换**：官方文档给这一模块的 15 条里没有它们的出口，" +
                "面板自己的动作表里有 `add_ip_limit` / `add_sub_filter` 这些名字，但参数形状没写，" +
                "所以详情页只把全局配置里读到的原文摊出来，不给它们做开关。",
            "**引擎是选出来的，不是问出来的**：这一段写在 URL 里，面板没有接口说这台装的是哪个 web 软件。" +
                "空表要先换引擎再看第二眼。",
            "**读的那几口在正式面板上对过了**：列表、规则、全局设置、域名四个口在 13.1.0 上都回包正常，" +
                "全局设置实际给的是一大堆平铺的 nginx 配置项，比文档示例宽得多，多出来的原样留在「面板还给了」里。" +
                "**写的那几口只在自建测试面板上走过**，正式面板一口都没发过。"
        ).forEach { line ->
            Text(
                line.replace("**", ""),
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}
