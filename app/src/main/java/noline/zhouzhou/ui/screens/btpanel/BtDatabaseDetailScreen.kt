package noline.zhouzhou.ui.screens.btpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtDatabase
import noline.zhouzhou.data.model.BtDbBackup
import noline.zhouzhou.data.model.BtDbGrant
import noline.zhouzhou.data.model.BtDbInfo
import noline.zhouzhou.data.model.BtNewDatabaseForm
import noline.zhouzhou.data.repository.BtSourceFailure
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.StatPill
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

/**
 * 单个库的详情页。
 *
 * 面板上这一页有六个页签，接口只撑得起三个：大小与表、授权、备份。
 * 底下那张「这一格接口做不到」的卡片把剩下的事写清楚，免得人以为这里少了个按钮是 App 的毛病。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtDatabaseDetailScreen(
    appContainer: AppContainer,
    database: BtDatabase,
    onBack: () -> Unit
) {
    val viewModel = typechoViewModel<BtDatabaseDetailViewModel>(appContainer) {
        BtDatabaseDetailViewModel(it, database)
    }
    val state by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var askBackup by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.note) {
        if (state.note.isNotBlank()) {
            delay(4200)
            viewModel.consumeNote()
        }
    }

    if (askBackup) {
        ConfirmAction(
            title = "立刻备份「${database.name}」？",
            body = "面板当场打一发 .sql.gz，落在 /www/backup/database/mysql/${database.name}/ 下面。" +
                "库大的时候这一步要占几秒到几十秒，期间这一页都是转的。",
            keyword = "备份",
            mustType = false,
            onConfirm = { askBackup = false; viewModel.backup() },
            onDismiss = { askBackup = false }
        )
    }
    if (askDelete) {
        ConfirmAction(
            title = "删除「${database.name}」？",
            body = "面板会把库、账号和它的权限一起删掉，里面所有数据当场没了。" +
                "唯一不会被带走的是备份：/www/backup/database/mysql/${database.name}/ 那个目录会留在服务器上。",
            keyword = "删除",
            mustType = true,
            onConfirm = { askDelete = false; viewModel.deleteDatabase(onDone = onBack) },
            onDismiss = { askDelete = false }
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
                            database.name.ifBlank { "（面板没给库名）" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = t.textStrong,
                            maxLines = 1
                        )
                        Text(
                            listOfNotNull(
                                database.access.takeIf { it.isNotBlank() },
                                database.sid.takeIf { it.isNotBlank() }?.let { "所属服务器 $it" }
                            ).joinToString(" · "),
                            fontSize = 11.sp,
                            color = t.textLight,
                            maxLines = 1
                        )
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
            SummaryCard(database)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BtDbTab.entries.forEach { tab ->
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

            state.detail?.let { detail ->
                when (state.tab) {
                    BtDbTab.Tables -> TablesSection(detail.info, detail.tableNames)
                    BtDbTab.Grants -> GrantsSection(detail.grants, database.name)
                    BtDbTab.Backups -> BackupsSection(detail.backups)
                }
                detail.failures.forEach { SourceFailureCard(it) }
            }

            if (state.detail == null && !state.busy) {
                HintCard(
                    "这一页要问面板四张口（库信息、表清单、授权、备份），一张都没回来。" +
                        "回到上一页重新点进来，或者看看地址和密钥还配不配得对。"
                )
            }

            Spacer(Modifier.height(6.dp))
            SectionTitle("这一页上的两只手")
            HintText(
                "备份是当场打一发文件；删库会带走库、账号和权限。这两条都不认撤销。"
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { askBackup = true },
                    enabled = !state.busy && database.id.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("立刻备份", fontSize = 13.5.sp) }
                OutlinedButton(
                    onClick = { askDelete = true },
                    enabled = !state.busy && database.id.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("删掉这个库", fontSize = 13.5.sp, color = t.healthOver) }
            }
            Spacer(Modifier.height(14.dp))

            LimitsCard(database.name)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SummaryCard(db: BtDatabase) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                db.name.ifBlank { "（面板没给库名）" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = t.textStrong,
                modifier = Modifier.weight(1f)
            )
            if (db.engine.isNotBlank()) StatPill(db.engine, tint = t.textSecondary)
        }
        if (db.subtitle.isNotBlank()) {
            Text(
                db.subtitle,
                fontSize = 12.sp,
                color = t.textSecondary,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        // 访问来源是整个互联网的话，这一格只靠密码挡着，必须在详情页也提醒一次。
        if (BtNewDatabaseForm.isWideOpen(db.accept)) {
            Spacer(Modifier.height(6.dp))
            StatPill("允许任意地址连接", tint = t.warnText)
        }
        if (db.remark.isNotBlank() && db.remark != db.name) {
            Text(db.remark, fontSize = 11.5.sp, color = t.textLight, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 表页签：面板那张带大小的清单 + MySQL 自己数出来的表名，两份都摆出来。 */
@Composable
private fun TablesSection(info: BtDbInfo, nativeNames: List<String>) {
    val t = LocalNoLine.current
    SectionTitle("库大小")
    Text(
        if (info.sizeText.isBlank()) "面板这一格没给大小。" else info.sizeText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = t.textStrong
    )
    Spacer(Modifier.height(12.dp))

    SectionTitle("面板的表清单（${info.tables.size} 张）")
    if (info.isEmpty) {
        HintText("这一格是空的：要么这库还没建表，要么面板那张表这次没带回来。")
    } else {
        info.tables.forEach { table ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    table.name.ifBlank { "（无名）" },
                    fontSize = 13.sp,
                    color = t.textMain,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(table.detail, fontSize = 11.sp, color = t.textLight, maxLines = 1)
            }
            HorizontalDivider(color = t.hairline)
        }
    }

    Spacer(Modifier.height(10.dp))
    SectionTitle("MySQL 自己数的表（${nativeNames.size} 张）")
    HintText(
        "这一份走的是原生 SHOW TABLES，只有表名。两边数目对不上说明有表不是面板建的——" +
            "面板那张清单只认它自己记录里的东西。"
    )
    if (nativeNames.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            nativeNames.joinToString("、"),
            fontSize = 12.sp,
            color = t.textSecondary,
            fontFamily = FontFamily.Monospace,
            lineHeight = 19.sp
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun GrantsSection(grants: List<BtDbGrant>, dbName: String) {
    val t = LocalNoLine.current
    SectionTitle("这个账号能碰什么")
    HintText(
        "面板的授权口回的是这台服务器上所有用户能碰哪些库，这里只留下库名和「全局权限」那几条，" +
            "也就是 ${if (dbName.isBlank()) "这个库的账号" else "「$dbName」的账号"} 实际拿到的范围。"
    )
    Spacer(Modifier.height(10.dp))
    if (grants.isEmpty()) {
        HintText("面板没回任何授权行。有可能是这个库的账号名和库名不同，面板按库名筛就筛空了。")
    } else {
        grants.forEach { grant ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .noLineCard(RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    if (grant.database == "*") "全局权限" else grant.database,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textStrong
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // 面板把「整库」那一行写成一条叫「所有」的表，照抄会让人以为真有这张表。
                    when {
                        grant.tables.isEmpty() -> "（这一行面板没给表清单）"
                        grant.tables == listOf("所有") -> "整库所有表（面板给的 *）"
                        else -> grant.tables.joinToString("、")
                    },
                    fontSize = 11.5.sp,
                    color = t.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    HintText("整库读写是建库时默认授出去的权限；面板网页上那一格改权限，接口没有对应的口。")
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun BackupsSection(backups: List<BtDbBackup>) {
    val t = LocalNoLine.current
    SectionTitle("备份文件（${backups.size} 份）")
    if (backups.isEmpty()) {
        HintText("这库还没有备份。点下面「立刻备份」现场打一份。")
    } else {
        backups.forEach { file ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .noLineCard(RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    file.name.ifBlank { file.path.substringAfterLast('/') },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textStrong,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        file.humanSize.takeIf { it.isNotBlank() },
                        file.timeText.takeIf { it.isNotBlank() }
                    ).joinToString(" · ").ifBlank { "面板没给这份的大小和时间" },
                    fontSize = 11.5.sp,
                    color = t.textSecondary
                )
                if (file.path.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(file.path, fontSize = 10.5.sp, color = t.textLight, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        HintText(
            "这里只列得出来、点不动：面板的删备份口（DelBackup）实测无论带什么参数都回一句「备份已删除！」" +
                "而磁盘上的文件一个没少，所以这个 App 里不摆这只假手。要删、要还原，去面板网页的文件管理。"
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SourceFailureCard(source: BtSourceFailure) {
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

/**
 * 接口做不到的事说在这一页上。
 *
 * 三条都是实测出来的：`DelBackup` 假删；导入 SQL 只认服务器磁盘上已有的文件；
 * 查库类型的口直接报错（'dict_obj' object has no attribute 'db_type'）。
 */
@Composable
private fun LimitsCard(dbName: String) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("这一格面板接口做不到", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
        listOf(
            "改库的字符集：面板那个查类型的口实测报错，建库之后就只能靠 SQL 自己 ALTER。",
            "还原 / 导入 SQL：接口只认服务器磁盘上已有的 .sql 文件路径，手机上没有这一条路。",
            "删备份文件：${if (dbName.isBlank()) "" else "「$dbName」的"}面板删备份口回「已删除」而文件不动，这里宁可不给按钮。"
        ).forEach { line ->
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text("· ", fontSize = 12.sp, color = t.textSecondary)
                Text(line, fontSize = 12.sp, color = t.textSecondary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val t = LocalNoLine.current
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun HintText(text: String) {
    val t = LocalNoLine.current
    Text(text, fontSize = 11.5.sp, color = t.textLight, lineHeight = 17.sp)
}

@Composable
private fun HintCard(text: String) {
    val t = LocalNoLine.current
    Text(
        text,
        fontSize = 12.5.sp,
        color = t.textSecondary,
        lineHeight = 19.sp,
        modifier = Modifier.fillMaxWidth().noLineCard(RoundedCornerShape(12.dp)).padding(16.dp)
    )
    Spacer(Modifier.height(12.dp))
}
