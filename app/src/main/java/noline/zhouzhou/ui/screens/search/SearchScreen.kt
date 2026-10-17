package noline.zhouzhou.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.data.remote.SearchFailure
import noline.zhouzhou.ui.components.SearchPill
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

@Composable
fun SearchScreen(
    appContainer: AppContainer,
    initialQuery: String = "",
    onBack: () -> Unit,
    onPostClick: (String) -> Unit
) {
    val viewModel = typechoViewModel<SearchViewModel>(appContainer) {
        SearchViewModel(it, initialQuery)
    }
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 14.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            SearchPill(
                placeholder = stringResource(R.string.search_hint),
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::submit,
                autofocus = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))

        if (state.query.isBlank()) {
            IdleState(
                history = state.history,
                onPick = viewModel::pickHistory,
                onClearHistory = viewModel::clearHistory
            )
        } else {
            ResultList(
                state = state,
                onRetry = viewModel::retry,
                onPostClick = { postId ->
                    viewModel.onResultOpened()
                    onPostClick(postId)
                }
            )
        }
    }
}

@Composable
private fun IdleState(history: List<String>, onPick: (String) -> Unit, onClearHistory: () -> Unit) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        if (history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null,
                    modifier = Modifier.size(15.dp), tint = t.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text("最近搜过", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearHistory) {
                    Text("清空", fontSize = 12.sp, color = t.textLight)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.forEach { term ->
                    Text(
                        text = term,
                        fontSize = 13.sp,
                        color = t.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .heightIn(min = 30.dp)
                            .clip(CircleShape)
                            .background(t.surfaceSoft)
                            .clickable { onPick(term) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noLineCard(RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "怎么搜",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = t.textStrong
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "关键词交给站点的全文搜索，它一次最多回 10 篇；" +
                        "本机已经加载过的笔记会跟着一起找，所以离线也能翻出看过的东西。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp,
                    color = t.textSecondary
                )
            }
        }
    }
}

@Composable
private fun ResultList(state: SearchUiState, onRetry: () -> Unit, onPostClick: (String) -> Unit) {
    val t = LocalNoLine.current
    val results = state.results

    if (state.isSearching && !state.hasResults) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = t.accent)
            Spacer(Modifier.height(12.dp))
            Text("正在问站点…", fontSize = 13.sp, color = t.textSecondary)
        }
        return
    }

    if (state.searched && !state.hasResults) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.SearchOff, contentDescription = null,
                modifier = Modifier.size(44.dp), tint = t.textLight)
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (results.failure == null) "「${state.query.trim()}」没搜到东西"
                       else "「${state.query.trim()}」没搜成",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textMain
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (results.failure != null) results.failureMessage
                       else "换个更短的关键词试试，站点那边按整词匹配。",
                style = MaterialTheme.typography.bodySmall,
                color = t.textLight
            )
            // 是词本身不认，重趟一趟也不会变，就别给个按了没用的按钮。
            if (results.failure != null && results.failure != SearchFailure.KeywordUnusable) {
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onRetry) { Text("再搜一次", color = t.accent, fontSize = 13.sp) }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (results.failure != null) {
            item {
                FailureStrip(
                    message = results.failureMessage,
                    onRetry = onRetry,
                    retryable = results.failure != SearchFailure.KeywordUnusable
                )
            }
        }
        if (results.server.isNotEmpty()) {
            item { SectionHeader("站点全文 · ${results.server.size} 篇") }
            items(items = results.server, key = { "s_" + it.postId }) { post ->
                SearchResultRow(post = post, query = state.query, onClick = { onPostClick(post.postId) })
            }
        }
        if (results.localOnly.isNotEmpty()) {
            item { SectionHeader("本机已加载 · ${results.localOnly.size} 篇") }
            items(items = results.localOnly, key = { "l_" + it.postId }) { post ->
                SearchResultRow(post = post, query = state.query, onClick = { onPostClick(post.postId) })
            }
        }
        if (results.probablyCapped) {
            item {
                Text(
                    text = "站点一次最多回 10 篇，这里多半不是全部。想翻更多就换个更具体的词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textLight,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val t = LocalNoLine.current
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = t.textSecondary,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
    )
}

@Composable
private fun FailureStrip(message: String, onRetry: () -> Unit, retryable: Boolean) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(t.warnBg)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = t.warnText,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry, enabled = retryable) {
            Text("重试", fontSize = 12.sp, color = t.warnText)
        }
    }
}

@Composable
private fun SearchResultRow(post: Post, query: String, onClick: () -> Unit) {
    val t = LocalNoLine.current
    val shape = RoundedCornerShape(12.dp)
    val title = if (post.hasMeaningfulTitle) post.displayTitle else ""
    val body = if (post.hasMeaningfulTitle) post.plainExcerpt else post.displayTitle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard(shape)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(Modifier.weight(1f)) {
            if (title.isNotBlank()) {
                Text(
                    text = highlight(title, query),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = t.textStrong
                )
                Spacer(Modifier.height(5.dp))
            }
            if (body.isNotBlank()) {
                Text(
                    text = highlight(body, query),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = t.textMain
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                post.categories.firstOrNull()?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = t.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(t.accentBg)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                if (post.commentCount > 0) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
                        modifier = Modifier.size(12.dp), tint = t.textLight)
                    Text("${post.commentCount}", style = MaterialTheme.typography.labelSmall, color = t.textLight)
                }
                if (post.displayDate.isNotBlank()) {
                    Text(post.displayDate, style = MaterialTheme.typography.labelSmall, color = t.textLight)
                }
            }
        }

        post.firstImage?.let { url ->
            Spacer(Modifier.width(10.dp))
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(74.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(t.surfaceSunken)
            )
        }
    }
}

/** 命中的那一段压在站点的 mark 底色上，和正文里高亮一个颜色。 */
@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    val t = LocalNoLine.current
    return remember(text, query) {
        buildAnnotatedString {
            SearchHighlight.segments(text, query).forEach { segment ->
                if (segment.matched) {
                    withStyle(SpanStyle(background = t.markBg, color = t.textStrong, fontWeight = FontWeight.Bold)) {
                        append(segment.text)
                    }
                } else {
                    append(segment.text)
                }
            }
        }
    }
}
