package noline.zhouzhou.ui.screens.drive

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.remote.CloudreveCapacity
import noline.zhouzhou.data.remote.CloudreveFileNode
import noline.zhouzhou.data.repository.CloudreveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 网盘根目录的 uri：Cloudreve 用 cloudreve://my 表示「我的文件」，子路径直接往后拼。 */
const val DriveRootUri = "cloudreve://my"

data class DriveCrumb(val label: String, val uri: String)

/** 弹层里展示的一条链接：直链或分享链接都走它，hint 说清这条会不会过期。 */
data class DriveLink(val name: String, val url: String, val hint: String)

/** 一次正在进行的上传，用来在页面顶部画进度。 */
data class DriveUpload(val name: String, val done: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class DriveUiState(
    val dirUri: String = DriveRootUri,
    val nodes: List<CloudreveFileNode> = emptyList(),
    val nextToken: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** 建目录/重命名/移动/删除正在跑，用来挡住重复点击。 */
    val isBusy: Boolean = false,
    val error: String? = null,
    /** 下载这种「成功没东西可看」的动作给一句短提示，几秒后自己消失。 */
    val notice: String? = null,
    /** 取直链/建分享都是「点了等结果」，拿到就弹一层带链接的对话框。 */
    val link: DriveLink? = null,
    val isFetchingLink: Boolean = false,
    val upload: DriveUpload? = null,
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
                    _uiState.update {
                        it.copy(
                            isFetchingLink = false,
                            link = DriveLink(node.name, url, "这个地址永久有效，游客也能打开，贴进文章不会失效。")
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isFetchingLink = false, error = e.message) } }
            )
        }
    }

    /** 建分享。密码留空就是公开链接；有效期 0 表示不过期。 */
    fun createShare(node: CloudreveFileNode, password: String, expireSeconds: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val result = container.cloudreveRepository.createShare(node, password, expireSeconds, 0)
            _uiState.update { it.copy(isBusy = false, error = result.exceptionOrNull()?.message) }
            val url = result.getOrNull() ?: return@launch
            _uiState.update { it.copy(link = DriveLink(node.name, url, shareHint(password, expireSeconds))) }
        }
    }

    // ── 上传 / 下载 ──

    /** 选完本地文件就传到当前目录；同名会自动加序号，免得撞上存储端的 fail 行为。 */
    fun upload(document: Uri) {
        val context = container.application
        viewModelScope.launch {
            val source = DriveSource.of(context.contentResolver, document)
            if (source == null) {
                _uiState.update { it.copy(error = "读不到这个文件，换一个试试") }
                return@launch
            }
            if (source.size < 0L) {
                _uiState.update { it.copy(error = "读不到 ${source.name} 的大小，传不了") }
                return@launch
            }
            if (source.size == 0L) {
                _uiState.update { it.copy(error = "这个文件是空的，Cloudreve 不收") }
                return@launch
            }
            val name = uniqueName(source.name)
            val uri = CloudreveRepository.childUri(_uiState.value.dirUri, name)
            _uiState.update { it.copy(upload = DriveUpload(name, 0L, source.size), error = null, notice = null) }
            val result = container.cloudreveRepository.uploadFile(
                uri = uri,
                size = source.size,
                mimeType = source.mimeType,
                read = source::read
            ) { sent ->
                _uiState.update { it.copy(upload = DriveUpload(name, sent, source.size)) }
            }
            source.close()
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(upload = null, notice = "已上传 $name") }
                    reload()
                },
                onFailure = { e -> _uiState.update { it.copy(upload = null, error = e.message) } }
            )
        }
    }

    /** 取一次性的签名地址，交给系统下载器，进度在通知栏看。 */
    fun download(node: CloudreveFileNode) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            container.cloudreveRepository.downloadUrl(node).fold(
                onSuccess = { url -> enqueue(node.name, url) },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    private fun enqueue(name: String, url: String) {
        val context = container.application
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager == null) {
            _uiState.update { it.copy(error = "这台设备没有系统下载器") }
            return
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("来自 Cloudreve")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        runCatching { manager.enqueue(request) }
            .onSuccess { _uiState.update { it.copy(notice = "已加入下载队列，在通知栏看进度") } }
            .onFailure { _uiState.update { it.copy(error = "系统下载器没接受这个任务") } }
    }

    /** 上传同名会直接被存储端拒掉，所以先在当前这一页里避开重名。 */
    private fun uniqueName(raw: String): String {
        val taken = _uiState.value.nodes.map { it.name }.toSet()
        if (raw !in taken) return raw
        val stem = raw.substringBeforeLast('.', raw)
        val ext = raw.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem($index)" else "$stem($index).$ext"
            if (candidate !in taken) return candidate
            index += 1
        }
    }

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

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

/** 分享链接的有效期和提取码，用一句话讲清楚对方会看到什么。 */
fun shareHint(password: String, expireSeconds: Int): String {
    val life = when {
        expireSeconds <= 0 -> "长期有效"
        expireSeconds % 86400 == 0 -> "${expireSeconds / 86400} 天后失效"
        else -> "${expireSeconds / 3600} 小时后失效"
    }
    return if (password.isBlank()) "任何人打开就能看，$life。" else "链接末尾已经带上提取码，对方不用手输，$life。"
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
