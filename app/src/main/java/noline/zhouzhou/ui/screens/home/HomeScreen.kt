package noline.zhouzhou.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.ui.components.CategoryFilterRow
import noline.zhouzhou.ui.components.LoadMoreFooter
import noline.zhouzhou.ui.components.SearchPill
import noline.zhouzhou.ui.components.WaterfallPostCard
import noline.zhouzhou.ui.components.WaterfallSkeleton
import noline.zhouzhou.ui.components.XhsEntrance
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.typechoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appContainer: AppContainer,
    initialCategory: String = "",
    onPostClick: (String) -> Unit
) {
    val viewModel = typechoViewModel<HomeViewModel>(appContainer) {
        HomeViewModel(it, initialCategory)
    }
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyStaggeredGridState()

    // 触底自动翻页
    LaunchedEffect(gridState, uiState.posts.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 1 && lastVisible >= total - 1) viewModel.loadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = uiState.blogName.ifBlank { stringResource(R.string.app_name) },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.width(12.dp))
            SearchPill(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                placeholder = stringResource(R.string.search_hint),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        CategoryFilterRow(
            categories = uiState.categories,
            selected = uiState.selectedCategory,
            onSelect = viewModel::selectCategory
        )
        Spacer(Modifier.height(if (uiState.categories.isEmpty()) 2.dp else 8.dp))

        when {
            uiState.isLoading && uiState.posts.isEmpty() -> {
                WaterfallSkeleton(Modifier.fillMaxSize().padding(top = 4.dp))
            }

            uiState.error != null && uiState.posts.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.posts_error),
                    onRetry = viewModel::loadPosts
                )
            }

            uiState.posts.isEmpty() -> EmptyState()

            else -> PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (uiState.filteredPosts.isEmpty()) {
                    NoResultState(query = uiState.searchQuery, category = uiState.selectedCategory)
                } else {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp
                    ) {
                        itemsIndexed(
                            items = uiState.filteredPosts,
                            key = { _, post -> post.postId }
                        ) { index, post ->
                            XhsEntrance(index = index, modifier = Modifier.fillMaxWidth()) {
                                WaterfallPostCard(
                                    post = post,
                                    onClick = { onPostClick(post.postId) }
                                )
                            }
                        }
                        item(span = StaggeredGridItemSpan.FullLine) {
                            LoadMoreFooter(
                                loading = uiState.isLoadingMore,
                                canLoadMore = uiState.canLoadMore,
                                loadedCount = uiState.posts.size
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoResultState(query: String, category: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                query.isNotBlank() -> "没有找到「$query」相关的笔记"
                category != null -> "「$category」下还没有内容"
                else -> "这里空空如也"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = LocalAccent.current.copy(alpha = 0.75f))
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_retry), color = LocalAccent.current)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.posts_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
