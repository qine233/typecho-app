package noline.zhouzhou.ui.screens.btpanel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtDirPage
import noline.zhouzhou.data.model.BtFileAccess
import noline.zhouzhou.data.model.BtFileBody
import noline.zhouzhou.data.model.BtFileEntry
import noline.zhouzhou.data.model.BtFileHistoryFile
import noline.zhouzhou.data.model.BtFilesForm
import noline.zhouzhou.data.model.BtPathProbe
import noline.zhouzhou.data.model.BtRecycleEntry
import noline.zhouzhou.data.model.BtRecyclePage
import noline.zhouzhou.data.model.btFileNameOf
import noline.zhouzhou.data.model.btJoinPath
import noline.zhouzhou.data.model.btParentPath
import noline.zhouzhou.data.remote.BtUploadPart
import noline.zhouzhou.data.repository.BtFailure
import noline.zhouzhou.data.repository.BtFilesTools
import noline.zhouzhou.data.repository.BtHistoryPage
import noline.zhouzhou.data.repository.btIsArchive
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 这一页的三个视图，对应面板文件管理里的「目录 / 回收站 / 操作历史」。 */
enum class BtFilesView(val label: String) {
    Browse("目录"),
    Recycle("回收站"),
    History("操作历史")
}

/**
 * 文本编辑那一格。`baseline` 是刚读回来的原文，用它判断改没改，
 * 免得人在没动过一个字的情况下按下「保存」也写盘一次。
 */
data class BtEditorState(
    val body: BtFileBody,
    val content: String,
    val loading: Boolean = false
) {
    val dirty: Boolean get() = content != body.content
}

/**
 * 弹层里那些「填一句话就能提交」的动作。合成一个类而不是八种布尔，
 * 是因为它们的界面长得一模一样：一个标题、一个说明、一个输入格、一颗提交。
 */
data class BtFilesDialog(
    val kind: Kind,
    val title: String,
    val label: String,
    val initial: String,
    val hint: String,
    val entry: BtFileEntry? = null
) {
    enum class Kind { NewDir, NewFile, Rename, Move, Copy, Link, Unzip, RestoreTo, OpenPath }
}

data class BtFilesUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val view: BtFilesView = BtFilesView.Browse,
    val path: String = "/www/wwwroot",
    val keyword: String = "",
    val page: BtDirPage? = null,
    val bin: BtRecyclePage? = null,
    val binPage: Int = 1,
    val binKeyword: String = "",
    val history: BtHistoryPage? = null,
    val historyPage: Int = 1,
    /** 面板里有编辑版本的那些文件（`file_history_list`），和流水一起摆在「操作历史」那一格。 */
    val historyFiles: List<BtFileHistoryFile> = emptyList(),
    val tools: BtFilesTools? = null,
    val editor: BtEditorState? = null,
    val accessFor: BtFileEntry? = null,
    val access: BtFileAccess? = null,
    val accessPerm: String = "",
    val accessOwner: String = "",
    val actionFor: BtFileEntry? = null,
    val dialog: BtFilesDialog? = null,
    /** 「恢复到指定位置」问的是回收站里那一条，不是目录里的，所以单独存一份。 */
    val restoreFor: BtRecycleEntry? = null,
    val batch: Boolean = false,
    val picked: Set<String> = emptySet(),
    val probe: BtPathProbe? = null,
    val failure: BtFailure? = null,
    val note: String = ""
) {
    /** 这一页有没有可编辑的东西在等保存；退出时得问一句。 */
    val unsaved: Boolean get() = editor?.dirty == true
}

/**
 * 文件管理器。
 *
 * 这一页的写法被实测压出四条规矩，都写进了界面文案，不靠代码注释兜：
 * 1. **路径不存在时面板不报错**，它把 `path` 换成 `/www/wwwroot` 并照常列一堆条目，
 *    所以每次进目录都拿回声比对，对不上就在页顶摊一句「这个路径不存在」而不是展示别人的目录；
 * 2. **写完一律重读目录。** 递归改权限那句「设置成功」是假的（实测子文件纹丝不动），
 *    界面上那句结果来自重读，不来自面板措辞；
 * 3. **手输路径先探测。** `test_path` 是这一页唯一的「先问再动」，输完先探一次再进；
 * 4. **回收站是真分页，每页 10 条、目录在前。** 所以那一格给的是 ‹ › 而不是「加载更多」。
 */
class BtFilesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btFilesRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtFilesUiState())
    val uiState: StateFlow<BtFilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            panel.configFlow.collect { config ->
                _uiState.update { it.copy(configured = config.isComplete) }
            }
        }
        refresh()
    }

    // ── 切视图 ──

    fun setView(view: BtFilesView) {
        _uiState.update { it.copy(view = view, actionFor = null, dialog = null) }
        when (view) {
            BtFilesView.Browse -> loadDir()
            BtFilesView.Recycle -> loadBin(1)
            BtFilesView.History -> loadHistory(1)
        }
    }

    fun refresh() = when (_uiState.value.view) {
        BtFilesView.Browse -> loadDir()
        BtFilesView.Recycle -> loadBin(_uiState.value.binPage)
        BtFilesView.History -> loadHistory(_uiState.value.historyPage)
    }

    // ── 浏览 ──

    fun setKeyword(keyword: String) = _uiState.update { it.copy(keyword = keyword) }

    /** 按搜索键只是重读这一层：筛名字是界面本地按 `name` 做的，不发面板那一格 `search`。 */
    fun search() = loadDir()

    fun clearSearch() {
        _uiState.update { it.copy(keyword = "") }
        loadDir()
    }

    fun enter(entry: BtFileEntry) {
        if (!entry.isDir) return
        _uiState.update { it.copy(actionFor = null) }
        goTo(entry.path)
    }

    fun goTo(path: String) {
        _uiState.update { it.copy(path = path, keyword = "") }
        loadDir()
    }

    fun up() = goTo(btParentPath(_uiState.value.path))

    /** 手输路径：先探一次，`exists` 为假就别进去（进去也是空或者别人的目录）。 */
    fun openPath(raw: String) {
        val error = BtFilesForm.pathError(raw)
        if (error != null) {
            _uiState.update { it.copy(failure = BtFailure("路径不行", error, "从 / 开头写完整路径")) }
            return
        }
        val path = raw.trim()
        _uiState.update { it.copy(dialog = null, busy = true, probe = null) }
        viewModelScope.launch {
            val result = repository.probe(path)
            val probe = result.getOrNull()
            _uiState.update { it.copy(busy = false, probe = probe) }
            when {
                probe == null -> _uiState.update { it.copy(failure = result.exceptionOrNull()?.let(repository::describeFailure)) }
                !probe.exists -> _uiState.update {
                    it.copy(failure = BtFailure("这个路径不存在", "$path 在那台机器上没有", "看一下大小写，或者用面包屑一层层进"))
                }
                probe.isDir -> goTo(path)
                else -> {
                    // 指的是文件：直接开编辑器，别让人先进父目录再找。
                    openEditor(BtFileEntry(path, path, isDir = false, null, null, "", "", "", "", false, "", false))
                }
            }
        }
    }

    private fun loadDir() {
        val path = _uiState.value.path
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            // 不发 `search`：实测面板那一格看了等于没看（照样回整层），却会把关键词记进它自己的
            // `search_history`。筛名字这层由界面上按 name 做，不欠面板一次带副作用的请求。
            val result = repository.dir(path)
            val page = result.getOrNull()
            _uiState.update {
                it.copy(
                    busy = false,
                    page = page ?: it.page,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                        ?: page?.let { p -> if (p.pathMatches) null else BtFailure(
                            "这个路径不存在",
                            "发过去的是 $path，面板把这一页换成了 ${p.echoedPath}",
                            "宝塔在路径不存在时不报错，它把人领到 /www/wwwroot。检查一下大小写和斜杠，或者用上面的面包屑一层层进",
                            fromPanel = false
                        ) }
                )
            }
            loadTools(path)
        }
    }

    private fun loadTools(path: String) {
        viewModelScope.launch {
            val result = repository.tools(path)
            _uiState.update {
                it.copy(tools = result.getOrNull() ?: it.tools)
            }
        }
    }

    // ── 单条动作 ──

    fun showAction(entry: BtFileEntry) = _uiState.update { it.copy(actionFor = entry) }

    fun closeAction() = _uiState.update { it.copy(actionFor = null) }

    /** 文件点开就是正文；目录在这一格只读，面板没有「编辑目录」。 */
    fun openEditor(entry: BtFileEntry) {
        if (entry.isDir) return
        _uiState.update { it.copy(actionFor = null, editor = BtEditorState(BtFileBody(entry.path, "", "", null, false, false, 0, null), "", loading = true)) }
        viewModelScope.launch {
            val result = repository.body(entry.path)
            val body = result.getOrNull()
            _uiState.update {
                it.copy(
                    editor = body?.let { b -> BtEditorState(b, b.content) } ?: it.editor,
                    busy = false,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun closeEditor() = _uiState.update { it.copy(editor = null) }

    fun setEditorContent(content: String) =
        _uiState.update { it.copy(editor = it.editor?.copy(content = content)) }

    fun saveEditor() {
        val editor = _uiState.value.editor ?: return
        closeEditor()
        write("保存 ${btFileNameOf(editor.body.path)}") { repository.saveBody(editor.body, editor.content) }
    }

    fun showAccess(entry: BtFileEntry) {
        _uiState.update {
            it.copy(
                actionFor = null,
                accessFor = entry,
                access = null,
                accessPerm = entry.perm,
                accessOwner = entry.owner
            )
        }
        viewModelScope.launch {
            val result = repository.access(entry.path)
            val access = result.getOrNull()
            _uiState.update {
                it.copy(
                    access = access ?: it.access,
                    accessPerm = access?.perm?.ifBlank { it.accessPerm }.orEmpty().ifBlank { entry.perm },
                    accessOwner = access?.owner?.ifBlank { it.accessOwner }.orEmpty().ifBlank { entry.owner },
                    failure = result.exceptionOrNull()?.let(repository::describeFailure) ?: it.failure
                )
            }
        }
    }

    fun closeAccess() = _uiState.update { it.copy(accessFor = null, access = null) }

    fun setAccessPerm(value: String) = _uiState.update { it.copy(accessPerm = value) }

    fun setAccessOwner(value: String) = _uiState.update { it.copy(accessOwner = value) }

    fun submitAccess() {
        val entry = _uiState.value.accessFor ?: return
        val problems = listOfNotNull(
            BtFilesForm.permError(_uiState.value.accessPerm),
            _uiState.value.accessOwner.trim().takeIf { it.isBlank() }?.let { "属主要有一个人" }
        )
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(failure = BtFailure("权限还没填好", problems.joinToString("；"), "权限写三位八进制，比如 755"))
            }
            return
        }
        val perm = _uiState.value.accessPerm.trim()
        val owner = _uiState.value.accessOwner.trim()
        closeAccess()
        write("把 ${entry.name} 改成 $perm/$owner") { repository.setAccess(entry.path, perm, owner) }
    }

    // ── 弹层：一句话就能提交的动作 ──

    fun openDialog(kind: BtFilesDialog.Kind, entry: BtFileEntry? = null) {
        val path = _uiState.value.path
        val dialog = when (kind) {
            BtFilesDialog.Kind.NewDir -> BtFilesDialog(
                kind, "在这一层建目录", "目录名", "",
                "父级必须已经存在，面板不替你逐级建。$path", entry
            )
            BtFilesDialog.Kind.NewFile -> BtFilesDialog(
                kind, "在这一层建文件", "文件名（含后缀）", "",
                "建出来是空文件，直接就能编辑。$path", entry
            )
            BtFilesDialog.Kind.Rename -> BtFilesDialog(
                kind, "改名", "新名字", entry?.name.orEmpty(),
                "只改名字，位置不动。改名后这一层要重读一次。", entry
            )
            BtFilesDialog.Kind.Move -> BtFilesDialog(
                kind, "移动到哪里", "目标目录", _uiState.value.path,
                "面板的 `MvFile` 只认「目标全路径」，这里给目录，App 替你把名字拼上去。", entry
            )
            BtFilesDialog.Kind.Copy -> BtFilesDialog(
                kind, "复制到哪个目录", "目标目录", _uiState.value.path,
                "复制完原文件还在。目标目录已有同名时面板自己决定，界面上会先问一句。", entry
            )
            BtFilesDialog.Kind.Link -> BtFilesDialog(
                kind, "在这里建软链", "链接的完整路径", btJoinPath(path, entry?.name.orEmpty() + "-link"),
                "源是 ${entry?.path.orEmpty()}。链接建在哪个目录、叫什么名字，这一格写的就是那个「叫什么」。", entry
            )
            BtFilesDialog.Kind.Unzip -> BtFilesDialog(
                kind, "解压到哪里", "目标目录", path,
                "目标目录不存在时面板会建出来。压缩格式按后缀认（${entry?.name.orEmpty()}）。", entry
            )
            BtFilesDialog.Kind.RestoreTo -> BtFilesDialog(
                kind, "恢复到指定位置", "目标完整路径", _uiState.value.restoreFor?.originalPath.orEmpty(),
                "这一格要的是「放到哪 + 叫什么」的全路径，不是目录名。那个位置已经有东西时，面板会先把它改名成 原名_时间戳.bak 留着。", entry
            )
            BtFilesDialog.Kind.OpenPath -> BtFilesDialog(
                kind, "跳到哪个路径", "绝对路径", _uiState.value.path,
                "从 / 开头写完整路径。进去之前会先探一次在不在。", entry
            )
        }
        _uiState.update { it.copy(actionFor = null, dialog = dialog) }
    }

    fun closeDialog() = _uiState.update { it.copy(dialog = null, restoreFor = null) }

    fun submitDialog(raw: String) {
        val dialog = _uiState.value.dialog ?: return
        val value = raw.trim()
        val entry = dialog.entry
        val problems = when (dialog.kind) {
            BtFilesDialog.Kind.NewDir, BtFilesDialog.Kind.NewFile ->
                listOfNotNull(BtFilesForm.nameError(value))
            BtFilesDialog.Kind.Rename -> listOfNotNull(BtFilesForm.nameError(value))
            BtFilesDialog.Kind.Move, BtFilesDialog.Kind.Copy, BtFilesDialog.Kind.Unzip ->
                listOfNotNull(BtFilesForm.pathError(value))
            BtFilesDialog.Kind.Link, BtFilesDialog.Kind.RestoreTo, BtFilesDialog.Kind.OpenPath ->
                listOfNotNull(BtFilesForm.pathError(value))
        }
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(failure = BtFailure("还差一点", problems.joinToString("；"), dialog.hint))
            }
            return
        }
        closeDialog()
        when (dialog.kind) {
            BtFilesDialog.Kind.NewDir -> write("建目录 $value") { repository.createDir(_uiState.value.path, value) }
            BtFilesDialog.Kind.NewFile -> write("建文件 $value") { repository.createFile(_uiState.value.path, value) }
            BtFilesDialog.Kind.Rename -> if (entry != null) {
                write("改名成 $value") { repository.rename(entry, value) }
            }
            BtFilesDialog.Kind.Move -> if (entry != null) {
                write("把 ${entry.name} 移到 $value") { repository.moveInto(entry, value) }
            }
            BtFilesDialog.Kind.Copy -> if (entry != null) {
                write("复制 ${entry.name} 到 $value") { repository.copyInto(entry, value) }
            }
            BtFilesDialog.Kind.Link -> if (entry != null) {
                write("给 ${entry.name} 建软链") { repository.link(entry, value) }
            }
            BtFilesDialog.Kind.Unzip -> {
                val targets = chosenEntries().ifEmpty { listOfNotNull(entry) }
                write("解压 ${targets.size} 个到 $value") { repository.unzip(targets, value) }
            }
            BtFilesDialog.Kind.OpenPath -> openPath(value)
            BtFilesDialog.Kind.RestoreTo -> {
                val target = _uiState.value.restoreFor ?: return
                _uiState.update { it.copy(restoreFor = null) }
                write("把 ${target.name} 恢复到 $value") { repository.restore(target, value) }
            }
        }
    }

    // ── 删除 / 批量 ──

    fun setBatch(batch: Boolean) = _uiState.update { it.copy(batch = batch, picked = emptySet()) }

    fun togglePick(entry: BtFileEntry) = _uiState.update {
        it.copy(picked = if (entry.path in it.picked) it.picked - entry.path else it.picked + entry.path)
    }

    /** 勾的是「这一页屏幕上看得见的那些」：列表按 `keyword` 本地筛过，全选必须跟眼睛看到的一致，
     *  否则筛出 1 条却勾走 3 条，删除时就变成误伤。 */
    fun pickVisible() {
        val keyword = _uiState.value.keyword.trim()
        _uiState.update {
            it.copy(
                picked = it.page?.entries.orEmpty()
                    .filter { entry -> keyword.isBlank() || entry.name.contains(keyword, ignoreCase = true) }
                    .map { entry -> entry.path }
                    .toSet()
            )
        }
    }

    fun clearPicks() = _uiState.update { it.copy(picked = emptySet()) }

    private fun chosenEntries(): List<BtFileEntry> =
        _uiState.value.page?.entries.orEmpty().filter { it.path in _uiState.value.picked }

    fun delete(entry: BtFileEntry) =
        write("删除 ${entry.name}", clearsPicked = true) { repository.delete(entry) }

    fun deleteChosen() {
        val entries = chosenEntries()
        if (entries.isEmpty()) return
        write("删除这 ${entries.size} 条", clearsPicked = true) { repository.deleteMany(entries) }
    }

    fun unzipChosen(target: String) {
        val entries = chosenEntries().filter { btIsArchive(it.name) }
        if (entries.isEmpty()) return
        write("解压 ${entries.size} 个") { repository.unzip(entries, target) }
    }

    fun scan(entry: BtFileEntry) = write("扫描 ${entry.name}") {
        if (entry.isDir) repository.scanDir(entry.path) else repository.scanFile(entry.path)
    }

    fun scanHere() = write("扫描这一层") { repository.scanDir(_uiState.value.path) }

    fun installRar() = write("装 RAR 支持") { repository.installRar() }

    fun updateComposer() = write("更新 Composer") { repository.updateComposer() }

    /**
     * 上传。SAF 给的 [uri] 只能按 ContentResolver 读，所以名字和大小在屏面那边查好再传进来。
     */
    fun upload(context: Context, uri: Uri, name: String, size: Long, mime: String) {
        val dir = _uiState.value.path
        val resolver = context.contentResolver
        write("上传 $name") {
            // 面板的 UploadFile 靠 Content-Length 定边界，SAF 报不出长度时发过去就是残缺文件。
            if (size <= 0L) {
                Result.failure(
                    IllegalStateException("这个文件在手机上读不出长度（有些内容提供器就是不给），发过去只会是个残缺文件，换一次来源再试。")
                )
            } else {
                repository.upload(
                    dir,
                    BtUploadPart("zunfile", name, size, mime.ifBlank { "application/octet-stream" }) {
                        openStream(resolver, uri)
                    }
                )
            }
        }
    }

    private fun openStream(resolver: android.content.ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IllegalStateException("这个文件打不开，重选一次")

    // ── 回收站 ──

    fun setBinKeyword(keyword: String) = _uiState.update { it.copy(binKeyword = keyword) }

    fun loadBin(page: Int = _uiState.value.binPage) {
        _uiState.update { it.copy(busy = true, failure = null, binPage = page) }
        viewModelScope.launch {
            val result = repository.recycle(page, _uiState.value.binKeyword.trim())
            _uiState.update {
                it.copy(
                    busy = false,
                    bin = result.getOrNull() ?: it.bin,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    fun restore(entry: BtRecycleEntry) =
        write("放回 ${entry.name}") { repository.restore(entry) }

    fun purge(entry: BtRecycleEntry) =
        write("彻底删掉 ${entry.name}") { repository.purge(entry) }

    fun clearBin() = write("清空回收站") { repository.clearBin() }

    /** 「恢复到指定位置」：先把回收站那条记住，再让弹层去读它当默认值。 */
    fun openRestoreTo(entry: BtRecycleEntry) {
        _uiState.update { it.copy(restoreFor = entry) }
        openDialog(BtFilesDialog.Kind.RestoreTo)
    }

    // ── 操作历史 ──

    fun loadHistory(page: Int = _uiState.value.historyPage) {
        _uiState.update { it.copy(busy = true, failure = null, historyPage = page) }
        viewModelScope.launch {
            val result = repository.history(page, "")
            _uiState.update {
                it.copy(
                    busy = false,
                    history = result.getOrNull() ?: it.history,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
            // 「哪些文件有版本」和流水是两个口，各拿各的，少一边就少摆一块。
            val files = repository.historyFiles()
            _uiState.update { it.copy(historyFiles = files.getOrNull() ?: it.historyFiles) }
        }
    }

    /**
     * 目录那一格面板给的 `sz` 是 inode 大小，跟里面装了多少东西没关系，所以占用要单独问
     * `get_path_size`（这台实测 /tmp 报 3772868，而 inode 只有 4096）。
     */
    fun sizeOf(entry: BtFileEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, note = "") }
            val result = repository.size(entry.path)
            _uiState.update {
                it.copy(
                    busy = false,
                    note = result.getOrNull()?.let { size ->
                        "${entry.name} 里外一共 ${size.text.ifBlank { "面板没报数" }}"
                    }.orEmpty(),
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    // ── 写操作的公共骨架 ──

    /**
     * 每一发写操作都跟着重读当前视图：面板那句「成功」不可信的地方太多
     * （递归改权限就是其一），界面上看到的必须是重读回来的样子。
     */
    private var pendingWrite = false

    private fun write(label: String, clearsPicked: Boolean = false, block: suspend () -> Result<String>) {
        if (pendingWrite) return
        pendingWrite = true
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, note = "") }
            val result = block()
            pendingWrite = false
            _uiState.update {
                it.copy(
                    busy = false,
                    note = result.getOrNull()?.let { text -> "$label：$text" }.orEmpty(),
                    failure = result.exceptionOrNull()?.let(repository::describeFailure),
                    picked = if (clearsPicked || result.isSuccess) emptySet() else it.picked
                )
            }
            if (result.isSuccess) refresh()
        }
    }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
