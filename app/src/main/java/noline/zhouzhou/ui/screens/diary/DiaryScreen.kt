package noline.zhouzhou.ui.screens.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.ui.navigation.rememberTabBarInset
import noline.zhouzhou.R
import noline.zhouzhou.ui.components.PageTopBar
import noline.zhouzhou.ui.components.NotePostCard
import noline.zhouzhou.ui.theme.LocalNoLine
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
    val t = LocalNoLine.current
    val tabBarInset = rememberTabBarInset()

    // 头部盖在内容上，列表铺满整屏、从它底下穿过去（同首页）
    var barH by remember { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(t.pageBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = barH)
        ) {
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
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp + tabBarInset),
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

        PageTopBar(
            onBarHeight = { barH = it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Text("日记动态", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textStrong)
            if (uiState.totalCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text("${uiState.totalCount} 篇日常碎片", fontSize = 12.sp, color = t.textLight)
            }
        }
    }
}

@Composable
private fun DayHeader(day: DiaryDay) {
    val t = LocalNoLine.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    ) {
        Text(
            text = DateUtils.formatDiaryDay(day.timestamp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = t.textSecondary
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(t.hairline))
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
