package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtReverseProxyConf
import noline.zhouzhou.data.model.BtReverseProxyDomain
import noline.zhouzhou.data.model.BtReverseProxyLog
import noline.zhouzhou.data.model.BtReverseProxyEntry
import noline.zhouzhou.data.model.BtReverseProxyForm
import noline.zhouzhou.data.model.BtReverseProxyRule
import noline.zhouzhou.data.model.isBtSecretKey
import noline.zhouzhou.data.repository.BtProxyDetail
import noline.zhouzhou.data.repository.BtSourceFailure
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/**
 * 一个反向代理站点的详情：规则、全局设置、域名探活、访问日志。
 *
 * 上面四块各自一口接口，任何一块读不到只在那一块底下留一句，其余照常摆——
 * 面板版本之间这两口的形状差得挺多，全页报错等于什么都看不见。
 *
 * 开关写完**不假装读得回来**：`set_global_*` 那几口回的是「设置成功」这句话，
 * 面板随后 `get_global_conf` 会不会跟着变，文档没保证。所以每次写完都重读一遍详情，
 * 界面上摆的永远是面板下一次给的样子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtProxyDetailScreen(
    appContainer: AppContainer,
    entry: BtReverseProxyEntry,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtProxyDetailViewModel>(appContainer) {
        BtProxyDetailViewModel(it, entry)
    }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var askClear by remember { mutableStateOf(false) }
    var newRule by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    if (askClear) {
        ConfirmAction(
            title = "清空「${entry.title}」的代理缓存？",
            body = "面板删的是它自己那一段缓存目录里的响应副本。源站、规则、日志都不动，" +
                "代价是接下来的请求要一个个回源，会有一小段时间比平时慢。",
            keyword = "清缓存",
            mustType = false,
            onConfirm = { askClear = false; viewModel.clearCache() },
            onDismiss = { askClear = false }
        )
    }
    if (newRule) {
        AddRuleSheet(
            siteName = entry.name,
            busy = state.busy,
            onDismiss = { newRule = false },
            onSubmit = { form ->
                newRule = false
                viewModel.addRule(form)
            }
        )
    }
    if (showLog) {
        ProxyLogSheet(
            log = state.log,
            loading = state.loadingLog,
            onDismiss = { showLog = false },
            onReload = viewModel::loadLog
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
                    Text(
                        entry.title.ifBlank { "反向代理" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showLog = true; viewModel.loadLog() }) {
                        Icon(Icons.Default.Visibility, contentDescription = "访问日志", modifier = Modifier.size(20.dp), tint = t.textSecondary)
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
            if (state.busy && state.detail == null) {
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

            SiteSummaryCard(entry = entry, engineLabel = state.engine.label, detail = state.detail)

            Spacer(Modifier.height(14.dp))
            RulesCard(
                rules = state.detail?.rules.orEmpty(),
                failures = state.detail?.failures.orEmpty(),
                busy = state.busy,
                onAdd = { newRule = true }
            )

            Spacer(Modifier.height(14.dp))
            state.detail?.let { GlobalConfCard(conf = it.conf, failures = it.failures) }

            Spacer(Modifier.height(14.dp))
            SwitchesCard(
                conf = state.detail?.conf,
                busy = state.busy,
                onCache = viewModel::setCache,
                onGzip = viewModel::setGzip,
                onWebsocket = viewModel::setWebsocket,
                onLog = viewModel::setLog,
                onClearCache = { askClear = true }
            )

            Spacer(Modifier.height(14.dp))
            DomainCard(domains = state.detail?.domains.orEmpty(), failures = state.detail?.failures.orEmpty())

            Spacer(Modifier.height(14.dp))
            ProxyDetailLimitsCard()
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SiteSummaryCard(
    entry: BtReverseProxyEntry,
    engineLabel: String,
    detail: BtProxyDetail?
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
            StatPill("$engineLabel 底下", tint = t.accent)
        }
        if (entry.path.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(entry.path, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = t.textLight, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        val lines = listOfNotNull(
            entry.statusText.takeIf { it.isNotBlank() }?.let { "面板状态：$it" },
            entry.addTime.takeIf { it.isNotBlank() }?.let { "建于 $it" },
            entry.expireTime.takeIf { it.isNotBlank() && it != "0000-00-00" }?.let { "到期 $it" },
            entry.remark.takeIf { it.isNotBlank() }?.let { "备注：$it" }
        )
        lines.forEach {
            Text(it, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp)
        }
        detail?.domains?.takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "挂的域名：${it.joinToString("、") { d -> d.title }}",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
        }
        if (entry.extraKeys.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("面板还给了：${entry.extraKeys.joinToString("、")}", fontSize = 11.sp, color = t.textLight)
        }
    }
}

@Composable
private fun RulesCard(
    rules: List<BtReverseProxyRule>,
    failures: List<BtSourceFailure>,
    busy: Boolean,
    onAdd: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("代理规则", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd, enabled = !busy) { Text("加一条", fontSize = 12.5.sp, color = t.accent) }
        }
        if (rules.isEmpty()) {
            Text(
                failures.firstOrNull { it.label == "代理规则" }?.failure?.title
                    ?: "这一口回的是空列表：这个站点下一条代理规则都没有。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            failures.filter { it.label == "代理规则" }.forEach { SourceFailureLine(it) }
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.proxyPath.ifBlank { "（没有匹配路径）" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textStrong,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                rule.websocketEnabled?.let { StatPill(if (it) "ws 开" else "ws 关", tint = if (it) t.accent else t.textLight) }
                rule.gzipEnabled?.let { StatPill(if (it) "gzip 开" else "gzip 关", tint = if (it) t.accent else t.textLight) }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${rule.proxyType} → ${rule.proxyPass}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = t.textMain,
                lineHeight = 18.sp
            )
            if (rule.proxyHost.isNotBlank()) {
                Text("Host：${rule.proxyHost}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = t.textLight)
            }
            if (rule.remark.isNotBlank()) {
                Text(rule.remark, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            if (rule.extraKeys.isNotEmpty()) {
                Text("面板还给了：${rule.extraKeys.joinToString("、")}", fontSize = 11.sp, color = t.textLight)
            }
        }
    }
}

@Composable
private fun GlobalConfCard(conf: BtReverseProxyConf, failures: List<BtSourceFailure>) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("全局设置（面板读回来的样子）", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        failures.filter { it.label == "全局设置" }.forEach { SourceFailureLine(it) }
        val lines = listOfNotNull(
            conf.domainList.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "监听：$it" },
            conf.sslEnabled?.let { if (it) "证书：已挂" else "证书：没挂" },
            conf.gzipEnabled?.let { if (it) "gzip：开" else "gzip：关" },
            conf.websocketEnabled?.let { if (it) "websocket：开" else "websocket：关" },
            conf.logType.takeIf { it.isNotBlank() }?.let { "日志去向：$it" }
        )
        if (lines.isEmpty() && conf.raw.isEmpty()) {
            Text(
                "这一口没读出内容：可能是这个面板版本没有 `get_global_conf`，也可能得换上面那个引擎才读得到。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
        }
        lines.forEach {
            Text(it, fontSize = 12.sp, color = t.textSecondary, lineHeight = 19.sp)
        }
        if (conf.extraSections.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "面板还给了这几块，文档没写清字段，原样摊开：",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(4.dp))
            conf.extraSections.forEach { key ->
                RawLines(key, conf.raw[key]).forEach { line ->
                    Text(
                        line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = t.textLight,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFailureLine(failure: BtSourceFailure) {
    val t = LocalNoLine.current
    Text(
        "${failure.label}没读到：${failure.failure.title} · ${failure.failure.detail}",
        fontSize = 11.5.sp,
        color = t.warnText,
        lineHeight = 17.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** 四个开关：缓存 / gzip / websocket / 日志去向，加一格「清空缓存」。 */
@Composable
private fun SwitchesCard(
    conf: BtReverseProxyConf?,
    busy: Boolean,
    onCache: (Boolean, String) -> Unit,
    onGzip: (Boolean, String, String, String) -> Unit,
    onWebsocket: (Boolean) -> Unit,
    onLog: (String, String) -> Unit,
    onClearCache: () -> Unit
) {
    val t = LocalNoLine.current
    var cacheOn by remember(conf) { mutableStateOf(conf?.cacheEnabled) }
    var expires by remember(conf) { mutableStateOf(conf?.cacheExpires?.takeIf { it.isNotBlank() } ?: "1d") }
    var gzipOn by remember(conf) { mutableStateOf(conf?.gzipEnabled) }
    var minLength by remember { mutableStateOf("1k") }
    var compLevel by remember { mutableStateOf("5") }
    var gzipTypes by remember { mutableStateOf("") }
    var wsOn by remember(conf) { mutableStateOf(conf?.websocketEnabled) }
    var logType by remember(conf) { mutableStateOf(conf?.logType?.takeIf { it.isNotBlank() } ?: "default") }
    var logPath by remember { mutableStateOf("/www/wwwlogs/proxy.log") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("开关", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(4.dp))
        Text(
            "下面这几只作用在整个站点上，不是某一条规则。开关现在的样子是面板这一份全局设置里读出来的；" +
                "按下去会立刻发给面板，发完自动重读一次。",
            fontSize = 11.5.sp,
            color = t.textLight,
            lineHeight = 17.sp
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("代理缓存", fontSize = 13.sp, color = t.textStrong, modifier = Modifier.weight(1f))
            cacheOn?.let { Text(if (it) "面板说开着" else "面板说关着", fontSize = 11.5.sp, color = t.textLight) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = expires,
                onValueChange = { expires = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("缓存时长") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Button(onClick = { onCache(true, expires) }, enabled = !busy) { Text("开", fontSize = 13.sp) }
            OutlinedButton(onClick = { onCache(false, expires) }, enabled = !busy) { Text("关", fontSize = 13.sp) }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("gzip", fontSize = 13.sp, color = t.textStrong, modifier = Modifier.weight(1f))
            Switch(checked = gzipOn == true, onCheckedChange = { gzipOn = it }, enabled = !busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField("最小长度", minLength, { minLength = it }, Modifier.weight(1f))
            SmallField("压缩级别", compLevel, { compLevel = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        SmallField("压缩类型（留空按面板默认）", gzipTypes, { gzipTypes = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onGzip(gzipOn == true, minLength, compLevel, gzipTypes) }, enabled = !busy) {
            Text("把 gzip 这格发给面板", fontSize = 12.5.sp)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("websocket 升级", fontSize = 13.sp, color = t.textStrong, modifier = Modifier.weight(1f))
            Switch(checked = wsOn == true, onCheckedChange = { wsOn = it }, enabled = !busy)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "这一格关掉，站点下的 websocket 连接会当场断，得等客户端重连。",
            fontSize = 11.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onWebsocket(wsOn == true) }, enabled = !busy) {
            Text("把 websocket 这格发给面板", fontSize = 12.5.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("访问日志去向", fontSize = 13.sp, color = t.textStrong)
        Spacer(Modifier.height(6.dp))
        listOf(
            listOf("default" to "面板默认", "file" to "写文件"),
            listOf("rsyslog" to "rsyslog", "off" to "不落日志")
        ).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                pair.forEach { (value, label) ->
                    NoLineChip(label = label, selected = logType == value, onClick = { logType = value })
                }
            }
        }
        if (logType == "file" || logType == "rsyslog") {
            Spacer(Modifier.height(8.dp))
            SmallField("日志路径", logPath, { logPath = it }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onLog(logType, logPath) }, enabled = !busy) {
            Text("把日志这格发给面板", fontSize = 12.5.sp)
        }

        Spacer(Modifier.height(18.dp))
        Button(onClick = onClearCache, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("清空代理缓存", fontSize = 13.5.sp)
        }
    }
}

@Composable
private fun SmallField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.5.sp, color = LocalNoLine.current.textSecondary)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = noLineFieldColors()
        )
    }
}

@Composable
private fun DomainCard(domains: List<BtReverseProxyDomain>, failures: List<BtSourceFailure>) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("域名与探活", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        failures.filter { it.label == "域名" }.forEach { SourceFailureLine(it) }
        if (domains.isEmpty() && failures.none { it.label == "域名" }) {
            Text(
                "面板没列出挂在这个代理上的域名。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
        }
        domains.forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(domain.title, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = t.textMain, modifier = Modifier.weight(1f))
                when (domain.healthy) {
                    true -> StatPill("探活通过", tint = t.accent)
                    false -> StatPill("探活不通", tint = t.warnText)
                    null -> if (domain.healthyText.isNotBlank()) StatPill(domain.healthyText, tint = t.textLight)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyLogSheet(
    log: BtReverseProxyLog?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit
) {
    val t = LocalNoLine.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("访问日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong, modifier = Modifier.weight(1f))
                if (log?.size?.isNotBlank() == true) StatPill(log.size, tint = t.textLight)
                IconButton(onClick = onReload, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新读取", tint = t.textSecondary)
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                loading -> Text("正在读…", fontSize = 12.sp, color = t.textLight)
                log == null -> Text(
                    "还没读到日志。面板这一口把正文装在 `data.msg`，日志没开或者文件是空的都会回一句空话。",
                    fontSize = 12.sp,
                    color = t.textSecondary,
                    lineHeight = 18.sp
                )
                log.text.isBlank() -> Text(
                    "面板回了内容，但正文是空的。",
                    fontSize = 12.sp,
                    color = t.textSecondary
                )
                else -> Text(
                    log.text,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = t.textMain,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleSheet(
    siteName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (BtReverseProxyForm) -> Unit
) {
    val t = LocalNoLine.current
    var proxyPath by remember { mutableStateOf("/api") }
    var proxyPass by remember { mutableStateOf("http://127.0.0.1:") }
    var proxyHost by remember { mutableStateOf("\$http_host") }
    var proxyType by remember { mutableStateOf("http") }
    var remark by remember { mutableStateOf("") }
    val form = BtReverseProxyForm(domains = siteName, proxyPass = proxyPass, proxyPath = proxyPath, proxyHost = proxyHost, proxyType = proxyType, remark = remark)
    val problem = form.problem().takeIf { proxyPass.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = t.accent)
                Spacer(Modifier.width(8.dp))
                Text("给「$siteName」加一条规则", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "同一个站点下匹配路径不能重复，面板会拒。加进来的这条立刻生效，不用重载 web 软件。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            SmallField("匹配路径", proxyPath, { proxyPath = it }, Modifier.fillMaxWidth())
            problem?.let {
                Text(it, fontSize = 11.5.sp, color = t.warnText, modifier = Modifier.padding(bottom = 6.dp))
            }
            SmallField("目标地址", proxyPass, { proxyPass = it }, Modifier.fillMaxWidth())
            SmallField("转发时的 Host", proxyHost, { proxyHost = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("http", "unix").forEach { type ->
                    NoLineChip(label = type, selected = proxyType == type, onClick = { proxyType = type })
                }
            }
            Text(
                if (proxyType == "unix") "unix：目标地址填 socket 文件路径（比如 /run/x.sock），不是 URL。"
                else "这一格文档只认 http 和 unix 两个值。",
                fontSize = 11.5.sp,
                color = t.textLight,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(10.dp))
            SmallField("备注", remark, { remark = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Button(onClick = { onSubmit(form) }, enabled = !busy && form.canSubmit, modifier = Modifier.fillMaxWidth()) {
                Text("交给面板", fontSize = 13.5.sp)
            }
        }
    }
}

@Composable
private fun ProxyDetailLimitsCard() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("这一页改不了什么", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        listOf(
            "改一条规则的目标地址：面板没有这个接口，网页上那一下是「加新的、删旧的」两步拼的。" +
                "要改就在这里先「加一条」新的路径，确认转发生效了，再回列表删掉整站重来。",
            "Basic 认证、IP 限制、内容替换这三块：文档只给了动作名，参数形状没写。" +
                "所以只在上面「全局设置」里按面板给的原文摊出来，不做开关——猜参数发过去算一次失败调用，" +
                "攒够 20 次整个面板 API 会锁一小时。",
            "缓存那一格读的是 `proxy_cache.cache_status`。文档写的是 `\"0\"` / `\"1\"` 字符串，" +
                "13.1.0 的面板实际给的是布尔 `false`——所以「面板给了这一格」不等于「缓存开着」，" +
                "两种写法都认。"
        ).forEach { line ->
            Text(line, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * 把文档没写清的那几块原样摊成 `a.b.c = 值` 的行。
 *
 * 只走三层，密钥类字段一律换成「（已隐藏）」——`ssl_info` 里可能装着私钥，
 * 这一页不负责展示它们。
 */
private fun RawLines(prefix: String, element: JsonElement?, depth: Int = 0): List<String> {
    if (element == null) return emptyList()
    if (depth > 3) return listOf("$prefix …")
    return when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            if (isBtSecretKey(key)) listOf("$prefix.$key = （已隐藏）") else RawLines("$prefix.$key", value, depth + 1)
        }
        else -> {
            val text = element.toString().removeSurrounding("\"")
            listOf("$prefix = ${if (text.length > 120) text.take(120) + "…" else text}")
        }
    }
}
