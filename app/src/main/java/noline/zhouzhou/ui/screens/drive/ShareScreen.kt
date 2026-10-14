package noline.zhouzhou.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.remote.CloudreveShare
import noline.zhouzhou.ui.screens.admin.ErrorStrip
import noline.zhouzhou.ui.screens.admin.RowIconButton
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

/**
 * 分享管理：这个账号建过的分享都在这里，能复制链接、也能撤销。
 * 撤销只让链接失效，网盘里的文件不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(appContainer: AppContainer, onBack: () -> Unit) {
    val viewModel = typechoViewModel<ShareViewModel>(appContainer) { ShareViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    val clipboard = LocalClipboardManager.current

    var shareToRevoke by remember { mutableStateOf<CloudreveShare?>(null) }

    LaunchedEffect(uiState.notice) {
        if (uiState.notice != null) {
            delay(NoticeLingerMs)
            viewModel.clearNotice()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, uiState.shares.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 1 && lastVisible >= total - 2) viewModel.loadMore()
        }
    }

    shareToRevoke?.let { share ->
        AlertDialog(
            onDismissRequest = { shareToRevoke = null },
            title = { Text(stringResource(R.string.share_revoke)) },
            text = { Text(stringResource(R.string.share_revoke_confirm) + "\n\n" + share.name) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(share)
                    shareToRevoke = null
                }) {
                    Text(stringResource(R.string.share_revoke), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { shareToRevoke = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            modifier = Modifier.size(20.dp), tint = t.textStrong)
                    }
                    Text(
                        stringResource(R.string.share_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong
                    )
                    Spacer(Modifier.weight(1f))
                    if (uiState.isLoading || uiState.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp).size(17.dp),
                            strokeWidth = 2.dp,
                            color = t.accent
                        )
                    } else {
                        IconButton(onClick = viewModel::reload, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "刷新",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            uiState.error?.let { message ->
                ErrorStrip(message = message, onDismiss = viewModel::clearError)
            }
            uiState.notice?.let { message ->
                Text(
                    message,
                    fontSize = 12.5.sp,
                    color = t.accent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            when {
                uiState.isLoading && uiState.shares.isEmpty() -> {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        repeat(4) { ShareSkeleton() }
                    }
                }
                uiState.shares.isEmpty() && !uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(R.string.share_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = t.textStrong
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.share_empty),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = t.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)
                ) {
                    items(uiState.shares, key = { it.id }) { share ->
                        ShareRow(
                            share = share,
                            onCopy = {
                                clipboard.setText(AnnotatedString(share.url))
                                viewModel.markCopied(share)
                            },
                            onRevoke = { shareToRevoke = share }
                        )
                    }
                    item(key = "footer") {
                        if (uiState.isLoadingMore) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                color = t.accent
                            )
                        } else if (uiState.nextToken.isBlank()) {
                            Text(
                                "共 ${uiState.shares.size} 条分享",
                                fontSize = 12.sp,
                                color = t.textLight,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRow(
    share: CloudreveShare,
    onCopy: () -> Unit,
    onRevoke: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .clickable(onClick = onCopy)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (share.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (share.isFolder) t.accent else t.textSecondary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                share.name.ifBlank { "未命名" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = t.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (share.expired) {
                Text(
                    stringResource(R.string.share_expired),
                    fontSize = 11.sp,
                    color = t.warnText,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(t.surfaceSunken)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            RowIconButton(Icons.Default.ContentCopy, "复制链接", onCopy, t.accent)
            RowIconButton(Icons.Default.Delete, "撤销分享", onRevoke,
                MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
        }
        Spacer(Modifier.height(5.dp))
        Text(
            share.shareMetaText(),
            fontSize = 11.5.sp,
            color = t.textLight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            share.lifeText() + " · 创建于 " + share.createdText(),
            fontSize = 11.5.sp,
            color = t.textLight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            share.url,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = t.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ShareSkeleton() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .noLineCard()
            .padding(14.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp)
                .clip(CircleShape)
                .background(t.coverFallback)
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.75f)
                .height(10.dp)
                .clip(CircleShape)
                .background(t.hairline)
        )
    }
}
