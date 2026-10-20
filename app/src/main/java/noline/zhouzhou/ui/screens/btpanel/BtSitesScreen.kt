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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtSite
import noline.zhouzhou.data.model.BtSiteOptions
import noline.zhouzhou.data.model.btExpireText
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/** 列表页上会动的四类手，删站要亲手打字。 */
private sealed interface SitePending {
    data class Toggle(val site: BtSite, val running: Boolean) : SitePending
    data class Delete(val site: BtSite) : SitePending
    data class Backup(val site: BtSite) : SitePending
}

/**
 * 网站管理列表：面板「网站」那一页的手机版。
 *
 * 一行就是一个站点，域名、根目录、PHP 版本、证书剩余天数、有没有代理/重定向都在行上；
 * 点进去是这一站的九个页签。建站、启停、备份、删站四个手在列表上就能做完。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtSitesScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onOpenSite: (BtSite) -> Unit
) {
    val viewModel = typechoViewModel<BtSitesViewModel>(appContainer) { BtSitesViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var pending by remember { mutableStateOf<SitePending?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    when (val action = pending) {
        is SitePending.Toggle -> ConfirmAction(
            title = if (action.running) "启用「${action.site.name}」？" else "停用「${action.site.name}」？",
            body = if (action.running) "站点恢复对外提供服务，配置和证书都不动。"
            else "停用之后这个域名立刻打不开，面板会把它的配置留着，随时可以再启用。",
            keyword = if (action.running) "启用" else "停用",
            mustType = !action.running,
            onConfirm = {
                viewModel.setRunning(action.site, action.running)
                pending = null
            },
            onDismiss = { pending = null }
        )
        is SitePending.Delete -> ConfirmAction(
            title = "删除「${action.site.name}」？",
            body = "面板会把站点配置、证书、日志一起清掉。根目录里的文件属于文件管理那一边，" +
                "这一条动不了它，但站点在面板列表里会立刻消失，救不回来。",
            keyword = "删除",
            mustType = true,
            onConfirm = {
                viewModel.deleteSite(action.site)
                pending = null
            },
            onDismiss = { pending = null }
        )
        is SitePending.Backup -> ConfirmAction(
            title = "立刻备份「${action.site.name}」？",
            body = "面板当场打一包，落在 /www/backup/site/ 下面。站点大的时候这一步会占几秒到几十秒。",
            keyword = "备份",
            mustType = false,
            onConfirm = {
                viewModel.backup(action.site)
                pending = null
            },
            onDismiss = { pending = null }
        )
        null -> Unit
    }

    if (adding) {
        // 一点「建这个站」就收起来：成了列表自己会重取，没成失败卡片就摊在列表顶上。
        AddSiteSheet(
            options = state.options,
            busy = state.busy,
            onDismiss = { adding = false },
            onSubmit = { domain, php, remark, typeId, withDb, withFtp ->
                adding = false
                viewModel.createSite(domain, php, remark, typeId, withDb, withFtp)
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
                    Text("网站管理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "建站", modifier = Modifier.size(21.dp), tint = t.accent)
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
                    label = { Text("按域名或备注找") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = MaterialTheme.shapes.medium,
                    colors = noLineFieldColors()
                )
                IconButton(onClick = viewModel::search, enabled = !state.busy) {
                    Icon(Icons.Default.Search, contentDescription = "查询", tint = t.accent)
                }
            }
            Spacer(Modifier.height(8.dp))

            val heading = state.total?.let { "共 $it 个站点，已取 ${state.sites.size} 个" }
                ?: "已取 ${state.sites.size} 个站点"
            Text(heading, fontSize = 12.sp, color = t.textLight)
            Spacer(Modifier.height(6.dp))

            if (state.sites.isEmpty() && !state.busy) {
                Text(
                    if (state.keyword.isNotBlank()) "这个关键词没匹配到站点，面板那边也没给别的行。"
                    else if (!state.configured) "还没配好面板地址和密钥，回到宝塔那一页先保存自检。"
                    else "面板回了一个空列表：这台服务器上一个站点都没有。",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
                )
            }

            state.sites.forEachIndexed { index, site ->
                if (index > 0) HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 6.dp))
                SiteRow(
                    site = site,
                    busy = state.busy,
                    onOpen = { onOpenSite(site) },
                    onToggle = { pending = SitePending.Toggle(site, !site.running) },
                    onBackup = { pending = SitePending.Backup(site) },
                    onDelete = { pending = SitePending.Delete(site) }
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
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SiteRow(
    site: BtSite,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
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
                site.name,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            if (!site.running) StatPill("已停用", tint = t.warnText)
            else if (site.typeLabel.isNotBlank()) StatPill(site.typeLabel, tint = t.textSecondary)
        }
        if (site.remark.isNotBlank() && site.remark != site.name) {
            Text(site.remark, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        val line = listOfNotNull(
            site.subtitle.takeIf { it.isNotBlank() },
            site.phpVersion.takeIf { it.isNotBlank() && it != "静态" }?.let { "PHP $it" },
            site.backupCount?.takeIf { it > 0 }?.let { "备份 $it 份" },
            "到期 ${btExpireText(site.expireDate)}"
        ).joinToString(" · ")
        if (line.isNotBlank()) {
            Text(line, fontSize = 11.5.sp, color = t.textLight, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (site.ssl.deployed) {
            Spacer(Modifier.height(4.dp))
            StatPill(
                "证书 ${site.ssl.summary}",
                tint = if (site.ssl.isExpiring) t.healthOver else t.textSecondary
            )
        }
        if (site.hasProxy || site.hasRedirect) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (site.hasProxy) StatPill("有反向代理", tint = t.textSecondary)
                if (site.hasRedirect) StatPill("有重定向", tint = t.textSecondary)
            }
        }
        if (site.stopReason.isNotBlank()) {
            Text(
                "停用原因 · ${site.stopReason}",
                fontSize = 11.sp,
                color = t.warnText,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onToggle,
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text(if (site.running) "停用" else "启用", fontSize = 12.5.sp, color = t.accent) }
            TextButton(
                onClick = onBackup,
                enabled = !busy && site.id.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("备份", fontSize = 12.5.sp, color = t.textSecondary) }
            TextButton(
                onClick = onDelete,
                enabled = !busy && site.id.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("删站", fontSize = 12.5.sp, color = t.healthOver) }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("管理", fontSize = 12.5.sp, color = t.textSecondary) }
        }
    }
}

/** 建站面板：域名 + 运行环境 + 分类 + 顺带建库/FTP，四项都按面板建站长的那个顺序摆。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSiteSheet(
    options: BtSiteOptions?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (domain: String, php: String, remark: String, typeId: String, withDb: Boolean, withFtp: Boolean) -> Unit
) {
    val t = LocalNoLine.current
    var domain by remember { mutableStateOf("") }
    var php by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var typeId by remember { mutableStateOf("0") }
    var withDb by remember { mutableStateOf(false) }
    var withFtp by remember { mutableStateOf(false) }
    val error = BtNewSiteForm.domainError(domain).takeIf { domain.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("建一个站点", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "根目录按面板的规矩固定在 /www/wwwroot/域名 下面，这里不改。",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("域名") },
                placeholder = { Text("sub.example.com") },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = t.warnText) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(14.dp))
            Text("运行环境", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            if (options == null) {
                Text("正在问面板装了哪些 PHP…", fontSize = 12.sp, color = t.textLight)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val phpChoices = options.phpVersions.filter { it.installed }.distinctBy { it.version }
                    // 面板清单里已经带了 `00 纯静态`，再补一颗就是两个一模一样的选项。
                    if (phpChoices.none { it.version == "00" }) {
                        NoLineChip(label = "纯静态", selected = php == "00", onClick = { php = "00" })
                    }
                    phpChoices.forEach { item ->
                        NoLineChip(
                            label = item.name.ifBlank { "PHP ${item.version}" },
                            selected = php == item.version,
                            onClick = { php = item.version }
                        )
                    }
                    if (options.phpVersions.none { it.installed }) {
                        Text("面板说这台机器没装 PHP，只能建纯静态站。", fontSize = 12.sp, color = t.warnText)
                    }
                }
            }
            if (options?.defaultSite?.isNotBlank() == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前的默认站点是 ${options.defaultSite}，新建的站不会抢走它。",
                    fontSize = 11.5.sp,
                    color = t.textLight,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("网站分类", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NoLineChip(label = "默认", selected = typeId == "0", onClick = { typeId = "0" })
                options?.siteTypes?.filter { it.id != "0" }?.forEach { type ->
                    NoLineChip(
                        label = type.name.ifBlank { "分类 ${type.id}" },
                        selected = typeId == type.id,
                        onClick = { typeId = type.id }
                    )
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
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = withDb, onCheckedChange = { withDb = it })
                Text("顺带建一个数据库", fontSize = 13.sp, color = t.textMain)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = withFtp, onCheckedChange = { withFtp = it })
                Text("顺带建一个 FTP", fontSize = 13.sp, color = t.textMain)
            }
            if (withDb || withFtp) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "这两样是面板连着建的：任何一半失败整个站就不成立，" +
                        "账号和密码由面板自己生成，建完到数据库 / FTP 那两页去看。",
                    fontSize = 11.5.sp,
                    color = t.warnText,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = { onSubmit(domain, php, remark, typeId, withDb, withFtp) },
                enabled = !busy && error == null && php.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "正在发给面板…" else "建这个站", fontSize = 14.sp) }
        }
    }
}
