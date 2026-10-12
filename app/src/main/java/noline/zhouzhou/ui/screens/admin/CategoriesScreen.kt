package noline.zhouzhou.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.data.model.Category
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.noLineCard
import noline.zhouzhou.ui.typechoViewModel

/** 分类管理：Typecho 的分类就是普通自定义字段，这里能建、能删、能直接去看这一类的文章。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit
) {
    val viewModel = typechoViewModel<CategoriesViewModel>(appContainer) { CategoriesViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()
    val t = LocalNoLine.current
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    categoryToDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text(stringResource(R.string.admin_delete_category)) },
            text = {
                Text(stringResource(R.string.admin_delete_category_confirm, category.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(category.categoryId)
                    categoryToDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showCreateDialog) {
        CreateCategoryDialog(
            parents = uiState.categories.filter { it.parentId == "0" },
            isSaving = uiState.isSaving,
            error = uiState.error,
            onDismiss = {
                showCreateDialog = false
                viewModel.clearError()
            },
            onCreate = { name, slug, parentId, description ->
                viewModel.create(name, slug, parentId, description) { showCreateDialog = false }
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
                        stringResource(R.string.admin_categories),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.textStrong
                    )
                    Spacer(Modifier.weight(1f))
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp).size(17.dp),
                            strokeWidth = 2.dp,
                            color = t.accent
                        )
                    } else {
                        IconButton(onClick = { viewModel.load() }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "刷新",
                                modifier = Modifier.size(19.dp), tint = t.textSecondary)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = t.accent,
                contentColor = t.onBrand,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_new_category)) }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // 建/删失败压一条横条，已有列表照常能看。
            if (!showCreateDialog) {
                uiState.error?.let { message ->
                    ErrorStrip(message = message, onDismiss = viewModel::clearError)
                }
            }

            when {
                uiState.isLoading && uiState.categories.isEmpty() -> {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        repeat(4) { CategorySkeleton() }
                    }
                }
                uiState.categories.isEmpty() && !uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("还没有分类", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = t.textStrong)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "文章按分类归组，先建一个分类，写作时就能选它了。",
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = t.textSecondary
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.categories, key = { it.categoryId }) { category ->
                        CategoryCard(
                            category = category,
                            parentName = uiState.parentName(category),
                            onClick = { onOpenCategory(category.name) },
                            onDelete = { categoryToDelete = category }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: Category,
    parentName: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noLineCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    category.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.textStrong,
                    maxLines = 1
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (category.slug.isNotBlank()) {
                        Text(
                            category.slug,
                            fontSize = 11.5.sp,
                            color = t.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(t.accentBg)
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                        )
                    }
                    parentName?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("上级 $it", fontSize = 11.5.sp, color = t.textLight)
                    }
                }
            }
            RowIconButton(Icons.AutoMirrored.Filled.Article, "查看这一类文章", onClick, t.textSecondary)
            Spacer(Modifier.width(2.dp))
            RowIconButton(Icons.Default.Delete, "删除", onDelete,
                MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
        }
        if (category.description.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                category.description,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = t.textSecondary,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun CategorySkeleton() {
    val t = LocalNoLine.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .noLineCard()
            .padding(16.dp)
    ) {
        Box(
            Modifier.fillMaxWidth(0.4f).height(13.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(t.coverFallback)
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier.fillMaxWidth(0.2f).height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(t.hairline)
        )
    }
}

/** 新建分类：wp.newCategory 只认 name，别名/上级/描述都留空也能建。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateCategoryDialog(
    parents: List<Category>,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, slug: String, parentId: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_new_category)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.editor_category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it },
                    label = { Text(stringResource(R.string.editor_category_slug)) },
                    supportingText = { Text("留空则按分类名自动生成") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.editor_category_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (parents.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("上级分类", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NoLineChip(label = "无", selected = parentId == "0",
                            onClick = { parentId = "0" })
                        parents.forEach { parent ->
                            NoLineChip(
                                label = parent.name,
                                selected = parentId == parent.categoryId,
                                onClick = { parentId = parent.categoryId }
                            )
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, slug, parentId, description) },
                enabled = name.isNotBlank() && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
