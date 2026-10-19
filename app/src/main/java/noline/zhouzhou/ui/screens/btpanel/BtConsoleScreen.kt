package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtDanger
import noline.zhouzhou.data.model.BtEndpoint
import noline.zhouzhou.data.model.BtParamSpec
import noline.zhouzhou.data.repository.BtCallResult
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel

/** 危险三档在界面上的说法。别统称「危险」——那既不区分改和删，也吓不着真要删东西的人。 */
private fun dangerLabel(danger: BtDanger): String = when (danger) {
    BtDanger.Read -> "只读"
    BtDanger.Write -> "会改"
    BtDanger.Destroy -> "会删"
}

private fun dangerBody(danger: BtDanger): String = when (danger) {
    BtDanger.Read -> "只读：这条只是问面板要数据，点下去就跑，动不了服务器上任何东西。"
    BtDanger.Write -> "会改：面板会照着这条动真实配置。发出去之前会再问一句。"
    BtDanger.Destroy -> "会删：站点、库、任务删掉了面板不会自己恢复。要亲手打一遍「删除」两个字才放行。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtConsoleScreen(
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtConsoleViewModel>(appContainer) { BtConsoleViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    val selected = state.selected

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
                    IconButton(
                        onClick = { if (selected != null) viewModel.closeEndpoint() else onBack() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selected?.title ?: "全部接口",
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = t.textStrong,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            selected?.moduleTitle ?: "官方文档里的 ${state.endpointCount} 条，按模块翻",
                            fontSize = 11.sp, color = t.textLight, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selected != null) {
                        DangerPill(selected.danger)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = t.accent,
                    trackColor = t.hairline
                )
                Spacer(Modifier.height(10.dp))
            }

            state.failure?.let {
                FailureCard(it)
                Spacer(Modifier.height(4.dp))
            }

            if (selected == null) {
                Browser(state, viewModel)
            } else {
                Detail(
                    state = state,
                    endpoint = selected,
                    busy = state.busy,
                    onValue = viewModel::setValue,
                    onAddExtra = viewModel::addExtra,
                    onUpdateExtra = viewModel::updateExtra,
                    onRemoveExtra = viewModel::removeExtra,
                    // 只读的直接走，会改/会删的先过确认门；缺必填项哪儿都不去。
                    onFire = viewModel::fire
                )
            }
        }

        if (selected != null && state.pendingConfirm) {
            ConfirmAction(
                title = "发这条给面板？",
                body = "${selected.title}（${selected.name}）\n" +
                    "${selected.spec.v} ${selected.path}\n" +
                    "要带的参数：${paramSummary(state, selected)}",
                keyword = "删除",
                mustType = selected.danger == BtDanger.Destroy,
                onConfirm = viewModel::confirmFire,
                onDismiss = viewModel::cancelConfirm
            )
        }
    }
}

private fun paramSummary(state: BtConsoleUiState, endpoint: BtEndpoint): String {
    val pairs = endpoint.params.mapNotNull { spec ->
        state.values[spec.n]?.takeIf { it.isNotBlank() }?.let { "${spec.n}=$it" }
    } + state.extras.filter { it.key.isNotBlank() }.map { "${it.key}=${it.value}" }
    return if (pairs.isEmpty()) "（不带参数）" else pairs.joinToString("，")
}

@Composable
private fun Browser(state: BtConsoleUiState, viewModel: BtConsoleViewModel) {
    val t = LocalNoLine.current
    var keyword by remember { mutableStateOf(state.keyword) }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                viewModel.setKeyword(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜接口名、说明、路径或参数") },
            trailingIcon = {
                IconButton(onClick = {
                    keyword = ""
                    viewModel.clearKeyword()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "清空搜索", tint = t.textSecondary)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape = MaterialTheme.shapes.medium,
            colors = noLineFieldColors()
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.modules.forEach { module ->
                NoLineChip(
                    label = "${module.title} ${module.endpoints.size}",
                    selected = state.moduleFilter == module.title,
                    onClick = { viewModel.selectModule(module.title) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!state.catalogReady) {
            Text(
                "随包打的那份接口目录没读出来，这一页暂时是空的。重装一次 App 会重新带进来。",
                fontSize = 12.5.sp,
                color = t.warnText,
                lineHeight = 19.sp
            )
            return
        }
        val filtered = state.keyword.isNotBlank() || state.moduleFilter.isNotBlank()
        Text(
            "${state.shown.size} 条" + if (filtered) " · 已经筛过" else "",
            fontSize = 11.5.sp,
            color = t.textLight,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(state.shown, key = { "${it.module}#${it.name}#${it.path}" }) { endpoint ->
                HorizontalDivider(color = t.hairline)
                EndpointRow(endpoint) { viewModel.open(endpoint) }
            }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: BtEndpoint, onClick: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                endpoint.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            DangerPill(endpoint.danger)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "${endpoint.spec.v} ${endpoint.path}" +
                if (endpoint.sendsActionParam) " · action=${endpoint.name}" else "",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = t.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (endpoint.params.isNotEmpty()) {
            Text(
                endpoint.params.joinToString("、") { if (it.r == 1) "${it.n}*" else it.n },
                fontSize = 10.5.sp,
                color = t.textLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DangerPill(danger: BtDanger) {
    val t = LocalNoLine.current
    StatPill(
        text = dangerLabel(danger),
        tint = when (danger) {
            BtDanger.Read -> t.textSecondary
            BtDanger.Write -> t.warnText
            BtDanger.Destroy -> t.healthOver
        }
    )
}

@Composable
private fun Detail(
    state: BtConsoleUiState,
    endpoint: BtEndpoint,
    busy: Boolean,
    onValue: (String, String) -> Unit,
    onAddExtra: () -> Unit,
    onUpdateExtra: (Int, String, String) -> Unit,
    onRemoveExtra: (Int) -> Unit,
    onFire: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "${endpoint.spec.v} ${endpoint.path}" +
                if (endpoint.sendsActionParam) "\n表单体里带 action=${endpoint.name}" else "",
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = t.textSecondary,
            lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            dangerBody(endpoint.danger),
            fontSize = 12.sp,
            color = t.textSecondary,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .noLineCard(RoundedCornerShape(12.dp))
                .padding(12.dp)
        )
        Spacer(Modifier.height(12.dp))

        if (endpoint.params.isEmpty()) {
            Text(
                "文档没给这条的参数表。宝塔有一批接口只要 action 就能回数据，" +
                    "直接点下面的按钮试；面板回一句「参数错误」再回来加一行。",
                fontSize = 12.sp,
                color = t.textLight,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        endpoint.params.forEach { spec ->
            ParamField(spec, state.values[spec.n].orEmpty(), onValue)
            Spacer(Modifier.height(10.dp))
        }

        if (state.extras.isNotEmpty()) {
            Text("自己加的参数", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textLight)
            Spacer(Modifier.height(8.dp))
            state.extras.forEachIndexed { index, extra ->
                ExtraRow(extra, index, onUpdateExtra, onRemoveExtra)
                Spacer(Modifier.height(8.dp))
            }
        }
        TextButton(
            onClick = onAddExtra,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = t.accent)
            Spacer(Modifier.width(4.dp))
            Text("加一行参数", fontSize = 13.sp, color = t.accent)
        }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onFire,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (endpoint.danger == BtDanger.Destroy) t.healthOver else t.accent,
                contentColor = t.onBrand
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(if (busy) "面板在回…" else "发起调用", fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))

        state.result?.let { CallResultCard(it) }

        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ParamField(
    spec: BtParamSpec,
    value: String,
    onValue: (String, String) -> Unit
) {
    val t = LocalNoLine.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.n, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            if (spec.r == 1) {
                Spacer(Modifier.width(6.dp))
                Text("必填", fontSize = 10.5.sp, color = t.healthOver, fontWeight = FontWeight.SemiBold)
            }
            if (spec.t.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(spec.t, fontSize = 10.5.sp, color = t.textLight)
            }
        }
        if (spec.d.isNotBlank()) {
            Text(
                spec.d,
                fontSize = 11.sp,
                color = t.textSecondary,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(spec.n, it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = noLineFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
}

@Composable
private fun ExtraRow(
    extra: ExtraParam,
    index: Int,
    onUpdate: (Int, String, String) -> Unit,
    onRemove: (Int) -> Unit
) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = extra.key,
            onValueChange = { onUpdate(index, it, extra.value) },
            modifier = Modifier.weight(0.4f),
            singleLine = true,
            label = { Text("键") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.small,
            colors = noLineFieldColors()
        )
        OutlinedTextField(
            value = extra.value,
            onValueChange = { onUpdate(index, extra.key, it) },
            modifier = Modifier.weight(0.6f),
            singleLine = true,
            label = { Text("值") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.small,
            colors = noLineFieldColors()
        )
        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete, contentDescription = "删掉这一行",
                modifier = Modifier.size(16.dp), tint = t.textLight
            )
        }
    }
}

@Composable
private fun CallResultCard(result: BtCallResult) {
    val t = LocalNoLine.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${result.endpoint.title} · 回包",
                fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.textStrong,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("${result.tookMs} ms", fontSize = 11.sp, color = t.textLight)
        }
        if (result.msg.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "面板原话：" + result.msg,
                fontSize = 12.sp,
                color = t.textMain,
                lineHeight = 18.sp
            )
        }
        if (result.view.isTable) {
            Spacer(Modifier.height(10.dp))
            ResultTable(result)
        }
        if (result.view.text.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (result.view.isTable) "整包（脱敏后）" else "回包原文（脱敏后）",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textLight,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(result.view.text)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = t.accent
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("复制", fontSize = 12.sp, color = t.accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(t.surfaceSoft)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    result.view.text + if (result.view.truncated) "\n…（太长，只显示前一段）" else "",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = t.textMain,
                    lineHeight = 16.sp
                )
            }
        }
        if (result.view.isEmpty && result.msg.isBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("面板回了个空的东西，连一句话都没带。", fontSize = 12.sp, color = t.textSecondary)
        }
    }
}

@Composable
private fun ResultTable(result: BtCallResult) {
    val t = LocalNoLine.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "摆了 ${result.view.rows.size} 行 × ${result.view.columns.size} 列，其余字段在下面整包里",
            fontSize = 11.sp,
            color = t.textLight,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            result.view.columns.forEach { name ->
                Text(
                    name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                )
            }
        }
        HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 4.dp))
        result.view.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                row.forEach { cell ->
                    Text(
                        cell,
                        fontSize = 11.5.sp,
                        color = t.textMain,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                }
            }
        }
    }
}
