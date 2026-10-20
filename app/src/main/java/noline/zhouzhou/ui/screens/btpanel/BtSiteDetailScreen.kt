package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtProxyRule
import noline.zhouzhou.data.model.BtRedirectRule
import noline.zhouzhou.data.model.BtSite
import noline.zhouzhou.data.model.btExpireText
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/** 详情页上需要问一句才发的手。删规则要亲手打「删除」，其余只问一句。 */
private sealed interface DetailPending {
    data class Confirm(val label: String, val body: String, val keyword: String, val mustType: Boolean, val run: () -> Unit) : DetailPending
}

/**
 * 一个站点的详情页：面板「网站设置」那一排页签的手机版。
 *
 * 每个页签自己拉自己的接口，切过去才发请求；面板没给的那一格写「面板没给」，不摆 0。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtSiteDetailScreen(
    appContainer: AppContainer,
    siteId: String,
    siteName: String,
    sitePath: String,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtSiteDetailViewModel>(appContainer) {
        BtSiteDetailViewModel(it, siteId, siteName, sitePath)
    }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pending by remember { mutableStateOf<DetailPending.Confirm?>(null) }
    var sslSheet by remember { mutableStateOf(false) }
    var redirectDraft by remember { mutableStateOf<BtRedirectRule?>(null) }
    var newRedirect by remember { mutableStateOf(false) }
    var proxyDraft by remember { mutableStateOf<BtProxyRule?>(null) }
    var newProxy by remember { mutableStateOf(false) }

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
            onConfirm = {
                ask.run()
                pending = null
            },
            onDismiss = { pending = null }
        )
    }

    if (sslSheet) {
        SslSheet(busy = state.busy, onDismiss = { sslSheet = false }, onSubmit = { cert, key ->
            sslSheet = false
            viewModel.deploySsl(cert, key)
        })
    }
    if (newRedirect || redirectDraft != null) {
        RedirectSheet(
            existing = redirectDraft,
            siteDomain = state.site.name,
            busy = state.busy,
            onDismiss = { newRedirect = false; redirectDraft = null },
            onSubmit = { name, target, domains, path, kind, code, hold ->
                val wasExisting = redirectDraft != null
                newRedirect = false
                redirectDraft = null
                viewModel.saveRedirect(name, target, domains, path, kind, code, hold, existing = wasExisting)
            }
        )
    }
    if (newProxy || proxyDraft != null) {
        ProxySheet(
            existing = proxyDraft,
            busy = state.busy,
            onDismiss = { newProxy = false; proxyDraft = null },
            onSubmit = { name, dir, target, cache, minutes ->
                val wasExisting = proxyDraft != null
                newProxy = false
                proxyDraft = null
                viewModel.saveProxy(name, dir, target, cache, minutes, existing = wasExisting)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.site.name.ifBlank { siteName },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = t.textStrong,
                            maxLines = 1
                        )
                        Text(sitePath, fontSize = 11.sp, color = t.textLight, maxLines = 1)
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
            HeaderLine(state.site)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BtSiteTab.entries.forEach { tab ->
                    NoLineChip(label = tab.label, selected = state.tab == tab, onClick = { viewModel.selectTab(tab) })
                }
            }
            Spacer(Modifier.height(12.dp))

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

            when (state.tab) {
                BtSiteTab.Domains -> DomainsTab(state, viewModel)
                BtSiteTab.Settings -> SettingsTab(state, viewModel) { pending = it }
                BtSiteTab.Rewrite -> RewriteTab(state)
                BtSiteTab.Ssl -> SslTab(state, viewModel) { ask, sheet ->
                    if (sheet) sslSheet = true else pending = ask
                }
                BtSiteTab.Redirect -> RedirectTab(state, viewModel, { pending = it }, { redirectDraft = it }, { newRedirect = true })
                BtSiteTab.Proxy -> ProxyTab(state, viewModel, { pending = it }, { proxyDraft = it }, { newProxy = true })
                BtSiteTab.Log -> LogTab(state, viewModel) { pending = it }
                BtSiteTab.Security -> SecurityTab(state, viewModel) { pending = it }
                BtSiteTab.Backup -> BackupTab(state, viewModel) { pending = it }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun HeaderLine(site: BtSite) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (site.running) "在运行" else "已停用",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (site.running) t.accent else t.warnText
            )
            if (site.typeLabel.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                StatPill(site.typeLabel, tint = t.textSecondary)
            }
            if (site.ssl.deployed) {
                Spacer(Modifier.width(8.dp))
                StatPill(site.ssl.summary, tint = if (site.ssl.isExpiring) t.healthOver else t.textSecondary)
            }
        }
        if (site.stopReason.isNotBlank()) {
            Text(
                "停用原因 · ${site.stopReason}",
                fontSize = 11.5.sp,
                color = t.warnText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        val foot = listOfNotNull(
            site.phpVersion.takeIf { it.isNotBlank() && it != "静态" }?.let { "PHP $it" },
            site.domainCount?.takeIf { it > 0 }?.let { "$it 个域名" },
            site.backupCount?.let { "备份 $it 份" },
            "到期 ${btExpireText(site.expireDate)}"
        ).joinToString(" · ")
        if (foot.isNotBlank()) {
            Text(foot, fontSize = 11.5.sp, color = t.textLight, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ── 通用的块 ──

@Composable
private fun SectionCard(
    title: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 11.5.sp, color = t.textSecondary, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val t = LocalNoLine.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.width(92.dp))
        Text(value.ifBlank { Missing }, fontSize = 12.sp, color = t.textMain, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LoadingLine(tab: BtSiteTab) {
    val t = LocalNoLine.current
    Text(
        "正在读「${tab.label}」这一页…",
        fontSize = 12.5.sp,
        color = t.textLight,
        modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
    )
}

@Composable
private fun NothingLine(text: String) {
    val t = LocalNoLine.current
    Text(
        text,
        fontSize = 12.5.sp,
        color = t.textSecondary,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
    )
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    placeholder: String? = null
) {
    val t = LocalNoLine.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, fontSize = 12.sp, color = t.textLight) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.medium,
        colors = noLineFieldColors()
    )
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalNoLine.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, fontSize = 13.sp, color = t.textMain)
    }
}

// ── 域名 ──

@Composable
private fun DomainsTab(state: BtSiteDetailUiState, viewModel: BtSiteDetailViewModel) {
    val t = LocalNoLine.current
    val rows = state.domains
    var draft by remember { mutableStateOf("") }
    if (rows == null) {
        LoadingLine(BtSiteTab.Domains)
        return
    }
    SectionCard(
        title = "域名管理",
        hint = "主域名排在最前。加进来的域名立刻生效，面板会在 nginx 的配置里追加一条 server_name。"
    ) {
        rows.forEachIndexed { index, domain ->
            if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domain.name, fontSize = 13.5.sp, color = t.textStrong, modifier = Modifier.weight(1f))
                if (domain.isSubDirectoryBinding) StatPill("目录绑定", tint = t.textSecondary)
                if (domain.sslApplied) StatPill("已配证书", tint = t.accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(
                value = draft,
                onValueChange = { draft = it },
                label = "要加的域名",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Uri,
                placeholder = "alias.example.com"
            )
            FilledTonalButton(onClick = { viewModel.addDomain(draft); draft = "" }, enabled = !state.busy) {
                Text("加上", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "这一页只有「看」和「加」：官方接口里没有任何一个删域名的 action 能用，" +
                "remove_domain / del_domain / RemoveDomain 三条都只回「指定参数无效!」，" +
                "要摘域名得去面板上点。",
            fontSize = 11.5.sp,
            color = t.warnText,
            lineHeight = 17.sp
        )
    }
}

// ── 网站设置 ──

@Composable
private fun SettingsTab(
    state: BtSiteDetailUiState,
    viewModel: BtSiteDetailViewModel,
    request: (DetailPending.Confirm) -> Unit
) {
    val t = LocalNoLine.current
    val data = state.settings
    if (data == null) {
        LoadingLine(BtSiteTab.Settings)
        return
    }
    var indexDraft by remember(data.index.files) { mutableStateOf(data.index.files.joinToString(",")) }
    val phpChoices = data.phpVersions.filter { it.installed }.distinctBy { it.version }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("PHP 版本", hint = "切换不需要先把站点停用，面板自己会换。选「纯静态」等于关掉 PHP 解析。") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 面板的版本清单里本来就带 `00 纯静态`，再硬加一颗就出现两颗一样的。
                if (phpChoices.none { it.version == "00" }) {
                    NoLineChip(
                        label = "纯静态",
                        selected = data.phpCurrent == "00",
                        onClick = { request(phpAsk("纯静态", "00", viewModel)) }
                    )
                }
                phpChoices.forEach { item ->
                    NoLineChip(
                        label = item.name.ifBlank { "PHP ${item.version}" },
                        selected = data.phpCurrent == item.version,
                        onClick = { request(phpAsk(item.name.ifBlank { "PHP ${item.version}" }, item.version, viewModel)) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            InfoLine("当前", data.phpCurrent.ifBlank { Missing })
        }

        SectionCard("运行目录", hint = "就是站点根目录下面那一层。ThinkPHP、Laravel 这类框架要指到 public。") {
            if (data.runPath.dirs.isEmpty()) {
                Text("面板没给出可选目录。", fontSize = 12.sp, color = t.textLight)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    data.runPath.dirs.forEach { dir ->
                        NoLineChip(
                            label = dir.ifBlank { "/" },
                            selected = data.runPath.runPath == dir,
                            onClick = {
                                if (data.runPath.runPath != dir) {
                                    request(
                                        DetailPending.Confirm(
                                            label = "把运行目录改成「$dir」？",
                                            body = "改完这个站就开始从新目录里找文件，原来的目录还在，只是不再被访问。",
                                            keyword = "运行目录",
                                            mustType = false,
                                            run = { viewModel.setRunPath(dir) }
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            InfoLine("当前", data.runPath.runPath)
        }

        SectionCard("默认文档", hint = "顺序就是匹配优先级，逗号分隔。") {
            Field(value = indexDraft, onValueChange = { indexDraft = it }, label = "默认文档列表")
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { viewModel.setIndex(indexDraft.split(',').map { it.trim() }.filter { it.isNotBlank() }) },
                enabled = !state.busy
            ) { Text("保存默认文档", fontSize = 13.sp) }
        }

        SectionCard("绑定的东西", hint = "只读：改绑要去 FTP 那一页，目录绑定要去面板的「子目录绑定」。") {
            InfoLine("FTP 账户", data.ftp)
            if (data.binding.dirs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "目录绑定 · " + data.binding.dirs.joinToString("、"),
                    fontSize = 11.5.sp,
                    color = t.textSecondary,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun phpAsk(label: String, version: String, viewModel: BtSiteDetailViewModel) =
    DetailPending.Confirm(
        label = "把 PHP 切成「$label」？",
        body = "面板会换掉这个站的解析方式。和站点代码要求的版本对不上时，网站会直接 502 或白屏，" +
            "改回来也在这一格。",
        keyword = "切换",
        mustType = false,
        run = { viewModel.setPhpVersion(version) }
    )

// ── 伪静态（只读）──

@Composable
private fun RewriteTab(state: BtSiteDetailUiState) {
    val t = LocalNoLine.current
    val data = state.rewrite ?: run {
        LoadingLine(BtSiteTab.Rewrite)
        return
    }
    SectionCard(
        title = "伪静态模板",
        hint = "这台服务器上现成的模板名单。"
    ) {
        if (data.templates.isEmpty()) {
            Text("面板没给出任何模板。", fontSize = 12.sp, color = t.textLight)
        } else {
            data.templates.forEach { name ->
                Text(name, fontSize = 12.5.sp, color = t.textMain, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "这一页改不了：官方文档写的 SetRewriteLists 实测发不出去——带 JSON 数组回 HTTP 400，" +
                "换成单个站点名只回一个空数组且不写文件。面板上也看不出这站在用哪个模板，" +
                "所以要套伪静态还是得去面板点那一下。",
            fontSize = 11.5.sp,
            color = t.warnText,
            lineHeight = 17.sp
        )
    }
}

// ── SSL ──

@Composable
private fun SslTab(
    state: BtSiteDetailUiState,
    viewModel: BtSiteDetailViewModel,
    onRequest: (DetailPending.Confirm?, Boolean) -> Unit
) {
    val t = LocalNoLine.current
    val data = state.ssl ?: run {
        LoadingLine(BtSiteTab.Ssl)
        return
    }
    SectionCard(
        title = "SSL 证书",
        hint = "这里只贴现成的证书和私钥（PEM）。签 Let's Encrypt 那套要走证书模块，不在这条接口上。"
    ) {
        InfoLine("状态", if (data.deployed) "已部署" else "未部署")
        if (data.domains.isNotEmpty()) InfoLine("覆盖域名", data.domains.joinToString("、"))
        if (data.authType.isNotBlank()) {
            // 面板这里给的是 ACME 的验证方式原值，念成人话更好读，认不出的照原样露出。
            val auth = when (data.authType.lowercase()) {
                "http" -> "HTTP 验证"
                "dns" -> "DNS 验证"
                "ftp" -> "FTP 验证"
                else -> data.authType
            }
            InfoLine("签发方式", auth)
        }
        if (data.tlsVersions.isNotEmpty()) InfoLine("启用的协议", data.tlsVersions.joinToString("、"))
        // GetSSL 这一份不给日期，站点表那行给了，读得到就用它，别让「面板没给」白占一行。
        InfoLine("到期", data.expiresAt.ifBlank { state.site.ssl.notAfter }.ifBlank { Missing })
        Spacer(Modifier.height(10.dp))
        SwitchLine(
            label = "强制 HTTPS（http 一律 301 到 https）",
            checked = data.httpToHttps,
            enabled = !state.busy
        ) { enabled -> viewModel.setForceHttps(enabled) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onRequest(null, true) }, enabled = !state.busy) {
                Text("贴证书部署", fontSize = 13.sp)
            }
            if (data.deployed) {
                OutlinedButton(
                    onClick = {
                        onRequest(
                            DetailPending.Confirm(
                                label = "关闭这个站的 SSL？",
                                body = "证书配置会被撤掉，https 立刻打不开；强制 HTTPS 也跟着失效。文件本身不动。",
                                keyword = "关闭",
                                mustType = false,
                                run = viewModel::closeSsl
                            ),
                            false
                        )
                    },
                    enabled = !state.busy
                ) { Text("关闭 SSL", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "私钥只在发出去那一刻进表单体，App 不显示、不记录，回包里那份原文也不落到任何一屏。",
            fontSize = 11.sp,
            color = t.textLight,
            lineHeight = 16.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SslSheet(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    val t = LocalNoLine.current
    var cert by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("贴证书部署 SSL", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "两半都要 PEM 原文，含 -----BEGIN 那两行。面板会当场校验是否配对，配不上就原样回一句。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            Field(
                value = cert,
                onValueChange = { cert = it },
                label = "证书（PEM）",
                singleLine = false,
                minLines = 4,
                keyboardType = KeyboardType.Ascii
            )
            Spacer(Modifier.height(10.dp))
            Field(
                value = key,
                onValueChange = { key = it },
                label = "私钥（PEM）",
                singleLine = false,
                minLines = 4,
                secret = true,
                keyboardType = KeyboardType.Ascii
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "私钥这一格显示成圆点：贴错了想知道贴进去的是什么，重新贴一次比显示出来安全。",
                fontSize = 11.sp,
                color = t.textLight,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = { onSubmit(cert, key) },
                enabled = !busy && cert.isNotBlank() && key.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("发给面板", fontSize = 14.sp) }
        }
    }
}

// ── 重定向 ──

@Composable
private fun RedirectTab(
    state: BtSiteDetailUiState,
    viewModel: BtSiteDetailViewModel,
    request: (DetailPending.Confirm) -> Unit,
    edit: (BtRedirectRule) -> Unit,
    create: () -> Unit
) {
    val t = LocalNoLine.current
    val rows = state.redirects ?: run {
        LoadingLine(BtSiteTab.Redirect)
        return
    }
    SectionCard("重定向", hint = "按路径或按域名把请求转到别处。规则写在 nginx 配置里，删了就没有了。") {
        if (rows.isEmpty()) NothingLine("这个站一条重定向都没有。") else rows.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong, modifier = Modifier.weight(1f))
                    if (rule.code.isNotBlank()) StatPill(rule.code, tint = t.textSecondary)
                }
                val line = listOfNotNull(
                    rule.kind.takeIf { it.isNotBlank() }?.let { if (it == "domain") "按域名" else "按路径" },
                    rule.path.takeIf { it.isNotBlank() },
                    rule.domains.joinToString("、").takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(line, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    "→ ${rule.target.ifBlank { Missing }}",
                    fontSize = 12.sp,
                    color = t.textMain,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { edit(rule) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("改", fontSize = 12.5.sp, color = t.accent)
                    }
                    TextButton(
                        onClick = {
                            request(
                                DetailPending.Confirm(
                                    label = "删除重定向「${rule.name}」？",
                                    body = "面板会把这条规则和它写进 nginx 的那一段一起撤掉，原来的地址立刻不再跳转。",
                                    keyword = "删除",
                                    mustType = true,
                                    run = { viewModel.deleteRedirect(rule.name) }
                                )
                            )
                        },
                        enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("删", fontSize = 12.5.sp, color = t.healthOver) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = create, enabled = !state.busy) { Text("加一条重定向", fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedirectSheet(
    existing: BtRedirectRule?,
    siteDomain: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, target: String, domains: String, path: String, kind: String, code: String, hold: Boolean) -> Unit
) {
    val t = LocalNoLine.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var target by remember { mutableStateOf(existing?.target ?: "") }
    var domains by remember { mutableStateOf(existing?.domains?.joinToString(",") ?: siteDomain) }
    var path by remember { mutableStateOf(existing?.path ?: "/") }
    var kind by remember { mutableStateOf(existing?.kind?.ifBlank { "path" } ?: "path") }
    var code by remember { mutableStateOf(existing?.code?.takeIf { it == "1" || it == "0" } ?: "1") }
    var hold by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(if (existing == null) "加一条重定向" else "改「${existing.name}」", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(12.dp))
            Field(value = name, onValueChange = { name = it }, label = "规则名称", placeholder = "跳转接口")
            Spacer(Modifier.height(10.dp))
            Field(value = target, onValueChange = { target = it }, label = "目标地址", keyboardType = KeyboardType.Uri, placeholder = "https://example.com/new")
            Spacer(Modifier.height(10.dp))
            Field(value = domains, onValueChange = { domains = it }, label = "来源域名（逗号分隔）")
            Spacer(Modifier.height(10.dp))
            Field(value = path, onValueChange = { path = it }, label = "来源路径", keyboardType = KeyboardType.Uri, placeholder = "/old")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoLineChip(label = "按路径", selected = kind == "path", onClick = { kind = "path" })
                NoLineChip(label = "按域名", selected = kind == "domain", onClick = { kind = "domain" })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoLineChip(label = "301 永久", selected = code == "1", onClick = { code = "1" })
                NoLineChip(label = "临时", selected = code == "0", onClick = { code = "0" })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "这两个码是面板文档给的定义（1 永久、0 临时），列表里显示的面板原话为准。",
                fontSize = 11.sp,
                color = t.textLight,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            SwitchLine(label = "保留原路径拼到目标后面", checked = hold, enabled = true) { hold = it }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = { onSubmit(name, target, domains, path, kind, code, hold) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (existing == null) "建这条" else "保存修改", fontSize = 14.sp) }
        }
    }
}

// ── 反向代理 ──

@Composable
private fun ProxyTab(
    state: BtSiteDetailUiState,
    viewModel: BtSiteDetailViewModel,
    request: (DetailPending.Confirm) -> Unit,
    edit: (BtProxyRule) -> Unit,
    create: () -> Unit
) {
    val t = LocalNoLine.current
    val rows = state.proxies ?: run {
        LoadingLine(BtSiteTab.Proxy)
        return
    }
    SectionCard("反向代理", hint = "把站点里的一段路径转到本机另一个端口上，比如 /api → http://127.0.0.1:8080。") {
        if (rows.isEmpty()) NothingLine("这个站还没有反向代理规则。") else rows.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(rule.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
                Text(
                    "${rule.directory.ifBlank { "/" }} → ${rule.target.ifBlank { Missing }}",
                    fontSize = 12.sp,
                    color = t.textMain,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (rule.cache.isNotBlank()) {
                    Text("缓存 ${rule.cache}", fontSize = 11.sp, color = t.textLight, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { edit(rule) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("改", fontSize = 12.5.sp, color = t.accent)
                    }
                    TextButton(
                        onClick = {
                            request(
                                DetailPending.Confirm(
                                    label = "删除代理「${rule.name}」？",
                                    body = "删掉之后这段路径不再转发，回到站点自己的目录里找文件。面板认的 action 是 RemoveProxy，只有这一个名字。",
                                    keyword = "删除",
                                    mustType = true,
                                    run = { viewModel.deleteProxy(rule.name) }
                                )
                            )
                        },
                        enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("删", fontSize = 12.5.sp, color = t.healthOver) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = create, enabled = !state.busy) { Text("加一条反向代理", fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySheet(
    existing: BtProxyRule?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, directory: String, target: String, cache: Boolean, minutes: String) -> Unit
) {
    val t = LocalNoLine.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var dir by remember { mutableStateOf(existing?.directory?.ifBlank { "/" } ?: "/") }
    var target by remember { mutableStateOf(existing?.target ?: "") }
    var cache by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf("0") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(if (existing == null) "加一条反向代理" else "改「${existing.name}」", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "三条替换规则用面板的默认空值：要改响应内容里的域名的话，得到面板上那条表单里填。",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Field(value = name, onValueChange = { name = it }, label = "代理名称", placeholder = "接口转发")
            Spacer(Modifier.height(10.dp))
            Field(value = dir, onValueChange = { dir = it }, label = "转发路径", keyboardType = KeyboardType.Uri, placeholder = "/api")
            Spacer(Modifier.height(10.dp))
            Field(value = target, onValueChange = { target = it }, label = "后端地址", keyboardType = KeyboardType.Uri, placeholder = "http://127.0.0.1:8080")
            Spacer(Modifier.height(10.dp))
            SwitchLine(label = "开启缓存", checked = cache, enabled = true) { cache = it }
            if (cache) {
                Spacer(Modifier.height(8.dp))
                Field(value = minutes, onValueChange = { minutes = it }, label = "缓存分钟数", keyboardType = KeyboardType.Number)
            }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = { onSubmit(name, dir, target, cache, minutes) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (existing == null) "建这条" else "保存修改", fontSize = 14.sp) }
        }
    }
}

// ── 日志 ──

@Composable
private fun LogTab(state: BtSiteDetailUiState, viewModel: BtSiteDetailViewModel, request: (DetailPending.Confirm) -> Unit) {
    val t = LocalNoLine.current
    val data = state.logs ?: run {
        LoadingLine(BtSiteTab.Log)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            title = "访问日志",
            hint = "开关是面板上的一个 toggle，点一下就翻面；当前状态读的是目录级配置里那一格。"
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "写入 · " + when (state.accessLogEnabled) {
                        true -> "开着"
                        false -> "关着"
                        null -> Missing
                    },
                    fontSize = 13.sp,
                    color = t.textMain,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        request(
                            DetailPending.Confirm(
                                label = "翻转访问日志的开关？",
                                body = "面板这个接口只有「翻」这一个动作，说不准会翻成开还是关；" +
                                    "发完 App 会把状态读回来，看一眼再决定要不要翻第二次。",
                                keyword = "翻转",
                                mustType = false,
                                run = viewModel::toggleAccessLog
                            )
                        )
                    },
                    enabled = !state.busy
                ) { Text("翻转开关", fontSize = 13.sp) }
            }
            if (data.path.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                InfoLine("日志目录", data.path)
            }
        }
        LogBlock("访问日志正文", data.access.text)
        LogBlock("错误日志正文", data.error.text)
    }
}

@Composable
private fun LogBlock(title: String, text: String) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        Spacer(Modifier.height(8.dp))
        if (text.isBlank()) {
            Text("这个文件目前是空的，或者面板没给正文。", fontSize = 12.sp, color = t.textLight)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(t.surfaceCode, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = t.textMain,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ── 安全 ──

@Composable
private fun SecurityTab(state: BtSiteDetailUiState, viewModel: BtSiteDetailViewModel, request: (DetailPending.Confirm) -> Unit) {
    val t = LocalNoLine.current
    val data = state.security ?: run {
        LoadingLine(BtSiteTab.Security)
        return
    }
    var perServer by remember { mutableStateOf(data.limit.perServer?.toString() ?: "10") }
    var perIp by remember { mutableStateOf(data.limit.perIp?.toString() ?: "5") }
    var rate by remember { mutableStateOf(data.limit.rateKb?.toString() ?: "1024") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var from301 by remember { mutableStateOf("http://" + state.site.name) }
    var to301 by remember { mutableStateOf("") }
    var xss by remember(data.headers) { mutableStateOf(data.headers.xssProtection) }
    var types by remember(data.headers) { mutableStateOf(data.headers.contentTypeOptions) }
    var referrer by remember(data.headers) { mutableStateOf(data.headers.referrerPolicy) }
    var csp by remember(data.headers) { mutableStateOf(data.headers.contentSecurityPolicy) }
    var permissions by remember(data.headers) { mutableStateOf(data.headers.permissionsPolicy) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("流量限制", hint = "三个值都指单个连接：总并发、单 IP 并发、单连接速率（KB/s）。") {
            InfoLine("当前", if (data.limit.enabled) "${data.limit.perServer ?: Missing} / ${data.limit.perIp ?: Missing} / ${data.limit.rateKb ?: Missing}" else "没开")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(value = perServer, onValueChange = { perServer = it }, label = "总并发", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                Field(value = perIp, onValueChange = { perIp = it }, label = "单 IP", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                Field(value = rate, onValueChange = { rate = it }, label = "KB/s", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        viewModel.setLimitNet(perServer.toIntOrNull() ?: 0, perIp.toIntOrNull() ?: 0, rate.toIntOrNull() ?: 0)
                    },
                    enabled = !state.busy
                ) { Text("开启限流", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        request(
                            DetailPending.Confirm(
                                label = "关闭这个站的限流？",
                                body = "关闭走的是面板的批量口 multiple_limit_net，只有它能把三个值一起清零；" +
                                    "SetLimitNet 发全 0 会被面板直接拒绝。",
                                keyword = "关闭",
                                mustType = false,
                                run = viewModel::closeLimitNet
                            )
                        )
                    },
                    enabled = !state.busy
                ) { Text("关闭限流", fontSize = 13.sp) }
            }
        }

        SectionCard(
            title = "目录密码保护",
            hint = "面板只回开没开，不回用户名——所以改密码必须账号和密码一起重填一遍。"
        ) {
            InfoLine("状态", if (data.password.enabled) "已开启" + data.password.username.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() else "没开")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(value = user, onValueChange = { user = it }, label = "账号", modifier = Modifier.weight(1f))
                Field(value = pass, onValueChange = { pass = it }, label = "密码（≤8 位）", modifier = Modifier.weight(1f), secret = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { viewModel.setPasswordProtect(user, pass) }, enabled = !state.busy) {
                    Text("设置密码", fontSize = 13.sp)
                }
                if (data.password.enabled) {
                    OutlinedButton(
                        onClick = {
                            request(
                                DetailPending.Confirm(
                                    label = "关闭目录密码？",
                                    body = "整个站不再要账号密码，之前那对凭据当场失效。",
                                    keyword = "关闭",
                                    mustType = false,
                                    run = viewModel::closePasswordProtect
                                )
                            )
                        },
                        enabled = !state.busy
                    ) { Text("关闭", fontSize = 13.sp) }
                }
            }
        }

        SectionCard(
            title = "301 转页",
            hint = "整域跳转。两个地址都得带协议前缀，实测只给域名面板回「Url地址不正确!」。"
        ) {
            InfoLine("状态", if (data.redirect301.enabled) "${data.redirect301.from} → ${data.redirect301.to}" else "没开")
            Spacer(Modifier.height(8.dp))
            Field(value = from301, onValueChange = { from301 = it }, label = "来源地址", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            Field(value = to301, onValueChange = { to301 = it }, label = "目标地址", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = { viewModel.set301(from301, to301) }, enabled = !state.busy) { Text("设置 301", fontSize = 13.sp) }
            Spacer(Modifier.height(6.dp))
            Text(
                "写完这一条别指望 Get301Status 立刻承认：实测它还会显示未开启，判断成没成看面板那句原话。",
                fontSize = 11.sp,
                color = t.textLight,
                lineHeight = 16.sp
            )
        }

        SectionCard("安全响应头") {
            SwitchLine("X-XSS-Protection", xss, !state.busy) { xss = it }
            SwitchLine("X-Content-Type-Options", types, !state.busy) { types = it }
            SwitchLine("Referrer-Policy", referrer, !state.busy) { referrer = it }
            SwitchLine("Content-Security-Policy", csp, !state.busy) { csp = it }
            SwitchLine("Permissions-Policy", permissions, !state.busy) { permissions = it }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = {
                    viewModel.setSecurityHeaders(
                        mapOf(
                            "x_xss_protection" to xss,
                            "x_content_type_options" to types,
                            "referrer_policy" to referrer,
                            "content_security_policy" to csp,
                            "permissions_policy" to permissions
                        )
                    )
                },
                enabled = !state.busy
            ) { Text("保存这五项", fontSize = 13.sp) }
        }
    }
}

// ── 备份与到期 ──

@Composable
private fun BackupTab(state: BtSiteDetailUiState, viewModel: BtSiteDetailViewModel, request: (DetailPending.Confirm) -> Unit) {
    val t = LocalNoLine.current
    val site = state.site
    var expire by remember(site.expireDate) { mutableStateOf(site.expireDate.ifBlank { "0000-00-00" }) }
    var remark by remember(site.remark) { mutableStateOf(site.remark) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            title = "备份",
            hint = "面板当场把站点目录打成一包，落在 /www/backup/site/ 下面。备份列表属于备份模块，App 这一版还没接。"
        ) {
            InfoLine("已有份数", site.backupCount?.toString() ?: Missing)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    request(
                        DetailPending.Confirm(
                            label = "立刻备份一次？",
                            body = "同步跑的一包，站点大的时候几十秒，期间面板那边会有占用。",
                            keyword = "备份",
                            mustType = false,
                            run = viewModel::backup
                        )
                    )
                },
                enabled = !state.busy && site.id.isNotBlank()
            ) { Text("立即备份", fontSize = 13.sp) }
        }

        SectionCard("到期时间", hint = "0000-00-00 是面板里的「永久」。") {
            Field(value = expire, onValueChange = { expire = it }, label = "到期日期", keyboardType = KeyboardType.Text, placeholder = "2027-12-31")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { viewModel.setExpireDate(expire) }, enabled = !state.busy) { Text("保存", fontSize = 13.sp) }
                TextButton(onClick = { viewModel.setExpireDate("0000-00-00") }, enabled = !state.busy) { Text("设为永久", fontSize = 13.sp) }
            }
        }

        SectionCard("备注名", hint = "面板列表上显示的那行小字，不改域名本身。") {
            Field(value = remark, onValueChange = { remark = it }, label = "备注")
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = { viewModel.setRemark(remark) }, enabled = !state.busy) { Text("保存备注", fontSize = 13.sp) }
        }
    }
}

private const val Missing = "面板没给"
