package noline.zhouzhou.ui.screens.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.remote.CloudreveCapacity
import noline.zhouzhou.data.remote.CloudreveFileNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 网盘根目录的 uri：Cloudreve 用 cloudreve://my 表示「我的文件」，子路径直接往后拼。 */
const val DriveRootUri = "cloudreve://my"

data class DriveCrumb(val label: String, val uri: String)

data class DriveLink(val node: CloudreveFileNode, val url: String)

data class DriveUiState(
    val dirUri: String = DriveRootUri,
    val nodes: List<CloudreveFileNode> = emptyList(),
    val nextToken: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** 建目录/重命名/移动/删除正在跑，用来挡住重复点击。 */
    val isBusy: Boolean = false,
    val error: String? = null,
    /** 取直链是「点了等结果」的动作，取到就弹一层带链接的对话框。 */
    val link: DriveLink? = null,
    val isFetchingLink: Boolean = false,
    /** 只是页面顶部一行说明，取不到就整块不显示，不占错误位。 */
    val capacity: CloudreveCapacity? = null
)

class DriveViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(DriveUiState(isLoading = true))
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    init {
        load(DriveRootUri)
        loadCapacity()
    }

    fun open(uri: String) {
        if (uri == _uiState.value.dirUri) return
        load(uri)
    }

    fun reload() {
        load(_uiState.value.dirUri)
        loadCapacity()
    }

    /** 容量跟当前在哪个目录无关，所以刷新按钮才顺手带一次，进子目录不用重复取。 */
    private fun loadCapacity() {
        viewModelScope.launch {
            _uiState.update { it.copy(capacity = container.cloudreveRepository.capacity()) }
        }
    }

    private fun load(uri: String) {
        viewModelScope.launch {
            // 换目录时旧列表先留着，新数据到了整体替换，中途只有顶部那颗圈在转。
            _uiState.update { it.copy(dirUri = uri, isLoading = true, error = null, nextToken = "") }
            container.cloudreveRepository.list(uri).fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            nodes = page.files,
                            nextToken = page.pagination?.nextToken.orEmpty(),
                            isLoading = false
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    /** 游标翻页：上一页给的 next_token 原样传回去，回来的追加到列表尾。 */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.nextToken.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            container.cloudreveRepository.list(state.dirUri, state.nextToken).fold(
                onSuccess = { page ->
                    _uiState.update {
                        val seen = it.nodes.map { n -> n.path }.toSet()
                        it.copy(
                            nodes = it.nodes + page.files.filter { f -> f.path !in seen },
                            nextToken = page.pagination?.nextToken.orEmpty(),
                            isLoadingMore = false
                        )
                    }
                },
                // 翻页失败不能把已经列出来的东西扔掉，交给错误条说一声。
                onFailure = { e -> _uiState.update { it.copy(isLoadingMore = false, error = e.message) } }
            )
        }
    }

    // ── 改动网盘 ──

    /** 「移动到」弹层自己记着目录栈，只借这条路取子目录，不碰主列表的状态。 */
    suspend fun foldersIn(uri: String): Result<List<CloudreveFileNode>> =
        container.cloudreveRepository.listFolders(uri)

    fun createFolder(name: String) {
        viewModelScope.launch {
            val dir = _uiState.value.dirUri
            if (!runOp { container.cloudreveRepository.createFolder(dir, name) }) return@launch
            // 新目录不在本地列表里，只能重拉；顺手把游标也归零，免得追加的是旧一页。
            reload()
        }
    }

    fun rename(node: CloudreveFileNode, newName: String) {
        viewModelScope.launch {
            if (!runOp { container.cloudreveRepository.rename(node, newName) }) return@launch
            // path 的尾段就是文件名，改名之后旧 path 已经不存在了，必须一起换掉。
            val renamed = node.copy(
                name = newName,
                path = node.path.substringBeforeLast('/') + "/" + newName
            )
            _uiState.update { state ->
                state.copy(nodes = state.nodes.map { if (it.path == node.path) renamed else it })
            }
        }
    }

    fun move(node: CloudreveFileNode, dstDirUri: String) {
        viewModelScope.launch {
            if (!runOp { container.cloudreveRepository.move(node, dstDirUri) }) return@launch
            // 移走的东西不属于这个目录了，本地摘掉；目标目录那边界面会自己去拉。
            dropNode(node)
        }
    }

    /** 只丢进回收站（Cloudreve 默认留 7 天），删完本地摘掉这一行，整表重拉会把位置甩回第一页。 */
    fun softDelete(node: CloudreveFileNode) {
        viewModelScope.launch {
            if (!runOp { container.cloudreveRepository.softDelete(node) }) return@launch
            dropNode(node)
        }
    }

    fun fetchLink(node: CloudreveFileNode) {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingLink = true, error = null) }
            container.cloudreveRepository.directLink(node).fold(
                onSuccess = { url ->
                    _uiState.update { it.copy(isFetchingLink = false, link = DriveLink(node, url)) }
                },
                onFailure = { e -> _uiState.update { it.copy(isFetchingLink = false, error = e.message) } }
            )
        }
    }

    fun clearLink() = _uiState.update { it.copy(link = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private suspend fun runOp(block: suspend () -> Result<Unit>): Boolean {
        _uiState.update { it.copy(isBusy = true, error = null) }
        val result = block()
        _uiState.update { it.copy(isBusy = false, error = result.exceptionOrNull()?.message) }
        return result.isSuccess
    }

    private fun dropNode(node: CloudreveFileNode) {
        _uiState.update { state ->
            state.copy(nodes = state.nodes.filterNot { it.path == node.path })
        }
    }
}

/** 面包屑自己从 dirUri 推出来，跳回任意一层都不用另存一条栈。 */
fun driveCrumbTrail(uri: String): List<DriveCrumb> {
    val crumbs = mutableListOf(DriveCrumb("我的文件", DriveRootUri))
    var path = DriveRootUri
    // 根目录要整段等值判断，光去前缀会把 cloudreve://my 自己拆成两段。
    uri.removePrefix(DriveRootUri).trimStart('/').split('/').filter { it.isNotBlank() }.forEach { segment ->
        path = "$path/$segment"
        crumbs.add(DriveCrumb(segment, path))
    }
    return crumbs
}
