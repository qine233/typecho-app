package noline.zhouzhou.ui.screens.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.ui.components.NotePostCard
import noline.zhouzhou.ui.typechoViewModel
import noline.zhouzhou.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    appContainer: AppContainer,
    onPostClick: (String) -> Unit
) {
    val viewModel = typechoViewModel<DiaryViewModel>(appContainer) { DiaryViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("日记动态", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        if (uiState.totalCount > 0) {
                            Text("${uiState.totalCount} 篇日常碎片",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading && uiState.days.isEmpty() -> CenterLoader("正在翻开日记本…")

                uiState.error != null && uiState.days.isEmpty() -> ErrorState(
                    message = uiState.error ?: "加载失败",
                    onRetry = viewModel::load
                )

                uiState.isEmpty -> EmptyState()

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        uiState.days.forEach { day ->
                            item(key = "header_${day.dayKey}") { DayHeader(day) }
                            items(day.posts, key = { "post_${it.postId}" }) { post ->
                                NotePostCard(post = post, onClick = { onPostClick(post.postId) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: DiaryDay) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    ) {
        Text(
            text = DateUtils.formatDiaryDay(day.timestamp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CenterLoader(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState() {
    AnimatedVisibility(visible = true, enter = fadeIn(tween(400))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_empty_diary),
                contentDescription = null,
                modifier = Modifier.size(140.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text("还没有日记", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("把「日记」分类下的文章写在这里，就会出现在这条时间线里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.AutoStories, contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) { Text("重试") }
    }
}
