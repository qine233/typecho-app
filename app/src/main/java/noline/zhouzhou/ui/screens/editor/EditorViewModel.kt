package noline.zhouzhou.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.Category
import noline.zhouzhou.data.model.LocalDraft
import noline.zhouzhou.data.model.Post
import noline.zhouzhou.util.HtmlToMarkdown
import noline.zhouzhou.util.MarkdownImages
import noline.zhouzhou.util.CompressedImage
import noline.zhouzhou.util.ImageCompressor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val postId: String? = null,
    val title: String = "",
    val content: String = "",
    val contentSelection: TextRange = TextRange(0),
    val excerpt: String = "",
    val tagsText: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategories: List<String> = emptyList(),
    val status: String = "publish",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isUploadingImage: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
    val layout: EditorLayout = EditorLayout.WRITE,
    val hasChanges: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isImmersive: Boolean = false,
    /** 最近一次本地稿落盘的时间，0 = 还没存过。 */
    val draftSavedAt: Long = 0L,
    /** 非空表示发现一份和当前编辑器内容不同的本地稿，等用户决定。 */
    val pendingDraft: LocalDraft? = null,
    /** 这篇的发布时间（epoch 秒）。保存时原样送回，别让 Typecho 自己推算成「现在」。 */
    val publishAt: Long = System.currentTimeMillis() / 1000L,
    /** 发布成功但站点没回传编号时的提醒，跟着 Toast 说一次就清掉。 */
    val saveNotice: String? = null
)

/** 编辑器三种排布：只写、边写边看（分屏）、只看渲染结果。 */
enum class EditorLayout { WRITE, SPLIT, PREVIEW }

class EditorViewModel(
    private val container: AppContainer,
    initialPostId: String?,
    private val isPage: Boolean = false
) : ViewModel() {

    /**
     * 新建成功后必须换成站点返回的 cid：否则同一个编辑器里再点一次保存，
     * 又会走 newPost 插出第二篇。
     */
    private var postId: String? = initialPostId

    private val _uiState = MutableStateFlow(EditorUiState(postId = postId))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // 正文撤销/重做：以「快照」为步长，输入停顿 600ms 或点击工具栏时提交一次。
    private data class Snapshot(val text: String, val selection: TextRange)
    private val history = mutableListOf(Snapshot("", TextRange(0)))
    private var historyIndex = 0
    private var commitJob: Job? = null
    private var draftJob: Job? = null
    private var draftChecked = false

    init {
        if (!isPage) loadCategories()
        if (postId != null) loadPost() else offerDraft()
        watchDrafts()
    }

    // ---- 本地自动存稿 ----

    /** 编辑停顿 1.2 秒落一次盘；只在用户真改过东西之后才存，别把服务器原文当草稿存一份。 */
    private fun watchDrafts() {
        viewModelScope.launch {
            _uiState.map { draftOf(it) }
                .distinctUntilChanged()
                .collect { draft ->
                    val s = _uiState.value
                    if (draft.isBlank || !s.hasChanges || s.pendingDraft != null) return@collect
                    draftJob?.cancel()
                    draftJob = launch {
                        delay(1200)
                        val stamped = draft.copy(updatedAt = System.currentTimeMillis())
                        container.draftStore.write(stamped)
                        _uiState.update { it.copy(draftSavedAt = stamped.updatedAt) }
                    }
                }
        }
    }

    private fun draftOf(s: EditorUiState) = LocalDraft(
        postId = postId ?: "",
        isPage = isPage,
        title = s.title,
        content = s.content,
        excerpt = s.excerpt,
        tagsText = s.tagsText,
        selectedCategories = s.selectedCategories,
        status = s.status,
        publishAt = s.publishAt
    )

    /** 服务器版本就位之后再比本地稿：一模一样就顺手清掉，不同才弹窗问用户。 */
    private fun offerDraft() {
        if (draftChecked) return
        draftChecked = true
        viewModelScope.launch {
            val draft = container.draftStore.load(postId, isPage) ?: return@launch
            if (draftOf(_uiState.value) == draft.copy(updatedAt = 0L)) {
                container.draftStore.discard(postId, isPage)
                return@launch
            }
            _uiState.update { it.copy(pendingDraft = draft) }
        }
    }

    fun restoreDraft() {
        val draft = _uiState.value.pendingDraft ?: return
        _uiState.update {
            it.copy(
                pendingDraft = null,
                title = draft.title,
                content = draft.content,
                contentSelection = TextRange(draft.content.length),
                excerpt = draft.excerpt,
                tagsText = draft.tagsText,
                selectedCategories = draft.selectedCategories,
                status = draft.status,
                publishAt = draft.publishAt.takeIf { it > 0L } ?: it.publishAt,
                hasChanges = true
            )
        }
        resetHistory(draft.content)
    }

    fun dropDraft() {
        _uiState.update { it.copy(pendingDraft = null, draftSavedAt = 0L) }
        container.draftStore.discard(postId, isPage)
    }

    override fun onCleared() {
        // 弹窗还开着就别写：那会儿编辑器里是服务器原文，写下去正好把本地稿盖掉。
        val s = _uiState.value
        if (s.pendingDraft == null && s.hasChanges) container.draftStore.write(draftOf(s))
        super.onCleared()
    }

    private fun resetHistory(text: String) {
        history.clear()
        history.add(Snapshot(text, TextRange(text.length)))
        historyIndex = 0
        refreshUndoRedo()
    }

    private fun refreshUndoRedo() {
        _uiState.update {
            it.copy(canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex)
        }
    }

    private fun commitNow() {
        val s = _uiState.value
        val snap = Snapshot(s.content, s.contentSelection)
        if (snap.text == history.getOrNull(historyIndex)?.text) return
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        history.add(snap)
        historyIndex = history.size - 1
        refreshUndoRedo()
    }

    private fun scheduleCommit() {
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            delay(600)
            commitNow()
        }
    }

    fun undo() {
        if (historyIndex <= 0) return
        commitJob?.cancel()
        historyIndex--
        val snap = history[historyIndex]
        _uiState.update { it.copy(content = snap.text, contentSelection = snap.selection, hasChanges = true) }
        refreshUndoRedo()
    }

    fun redo() {
        if (historyIndex >= history.lastIndex) return
        commitJob?.cancel()
        historyIndex++
        val snap = history[historyIndex]
        _uiState.update { it.copy(content = snap.text, contentSelection = snap.selection, hasChanges = true) }
        refreshUndoRedo()
    }

    fun toggleImmersive() = _uiState.update { it.copy(isImmersive = !it.isImmersive) }

    private fun loadPost() {
        viewModelScope.launch {
            val fill: (Post) -> Unit = { post ->
                val md = HtmlToMarkdown.convert(post.content)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        title = post.title,
                        content = md,
                        excerpt = post.excerpt,
                        tagsText = post.tags.joinToString(", "),
                        selectedCategories = post.categories,
                        status = post.status,
                        // 站点上不少老日记 created=0（接口给 1970），这种就按「现在」显示，
                        // 让编辑器里看到的和存回去的是同一个时刻。
                        publishAt = post.dateCreated.takeIf { it > 0L } ?: it.publishAt
                    )
                }
                resetHistory(md)
                offerDraft()
            }
            // 优先用列表缓存，绕开站点 getPost 的 500。
            if (!isPage) {
                container.postCache.get(postId!!)?.let { cached ->
                    fill(cached)
                    return@launch
                }
            }
            _uiState.update { it.copy(isLoading = true) }
            val config = getSiteConfig()
            // 页面不在文章接口里，只能走 wp.getPage。
            val result = if (isPage) {
                container.postRepository.getPage(config, postId!!)
            } else {
                container.postRepository.getPost(config, postId!!)
            }
            result.fold(
                onSuccess = { post ->
                    if (!isPage) container.postCache.put(post)
                    fill(post)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    // 拉不到服务器版本时，本地稿更要露出来。
                    offerDraft()
                }
            )
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            val config = getSiteConfig()
            val result = container.postRepository.getCategories(config)
            result.onSuccess { cats -> _uiState.update { it.copy(categories = cats) } }
        }
    }

    fun onTitleChange(t: String) = _uiState.update { it.copy(title = t, hasChanges = true) }
    fun onContentChange(c: String) = _uiState.update { it.copy(content = c, hasChanges = true) }

    fun onContentValueChange(value: TextFieldValue) {
        _uiState.update { it.copy(content = value.text, contentSelection = value.selection, hasChanges = true) }
        scheduleCommit()
    }

    fun applyMarkdown(action: MarkdownAction) {
        commitJob?.cancel()
        commitNow() // 把工具栏操作前的文本作为一步存档
        val state = _uiState.value
        val (text, selection) = action.apply(state.content, state.contentSelection)
        _uiState.update { it.copy(content = text, contentSelection = selection, hasChanges = true) }
        commitNow() // 工具栏结果作为独立一步
    }

    /** 相册选图 → 本地压缩 → XML-RPC 上传 → 在光标处插入 Markdown 图片。 */
    fun onImagePicked(uri: Uri) {
        if (_uiState.value.isUploadingImage) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingImage = true, error = null) }
            val outcome = runCatching {
                val image = withContext(Dispatchers.IO) {
                    ImageCompressor.compress(container.application.contentResolver, uri)
                }
                // Cloudreve 的直链末段是时间戳，拿它当 alt 只会写出一串数字。
                uploadImage(image) to image.name.substringBeforeLast('.')
            }
            outcome.fold(
                onSuccess = { (url, alt) ->
                    insertAtCaret("![$alt](${url.replace(" ", "%20")})")
                    _uiState.update { it.copy(isUploadingImage = false) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isUploadingImage = false, error = "图片上传失败：${e.message}")
                    }
                }
            )
        }
    }

    /**
     * 配了 Cloudreve 就只走 Cloudreve：它给的 `/f/` 直链是永久的，也不吃 Typecho 的附件目录。
     * 这里不悄悄退回 XML-RPC，图床坏了得让人当场看见。
     */
    private suspend fun uploadImage(image: CompressedImage): String =
        if (container.cloudreveRepository.isConfigured()) {
            container.cloudreveRepository.uploadImage(image.bytes, image.mimeType)
        } else {
            container.postRepository.uploadImage(
                getSiteConfig(), image.name, image.mimeType, image.bytes
            ).getOrThrow()
        }

    private fun insertAtCaret(snippet: String) {
        commitJob?.cancel()
        commitNow()
        val state = _uiState.value
        val pos = maxOf(state.contentSelection.min, state.contentSelection.max)
            .coerceIn(0, state.content.length)
        // 行中插入会把行首的列表/引用符号串到图片前面，独立成行更稳。
        val needsLineBreak = pos > 0 && state.content[pos - 1] != '\n'
        val prefix = if (needsLineBreak) "\n" else ""
        val newText = state.content.substring(0, pos) + prefix + snippet + state.content.substring(pos)
        _uiState.update {
            it.copy(content = newText, contentSelection = TextRange(pos + prefix.length + snippet.length), hasChanges = true)
        }
        commitNow()
    }
    fun onExcerptChange(e: String) = _uiState.update { it.copy(excerpt = e, hasChanges = true) }
    fun onTagsChange(t: String) = _uiState.update { it.copy(tagsText = t, hasChanges = true) }
    fun onStatusChange(s: String) = _uiState.update { it.copy(status = s, hasChanges = true) }
    fun onDateChange(epochSeconds: Long) =
        _uiState.update { it.copy(publishAt = epochSeconds, hasChanges = true) }

    fun toggleCategory(name: String) {
        _uiState.update { state ->
            val current = state.selectedCategories.toMutableList()
            if (current.contains(name)) current.remove(name) else current.add(name)
            state.copy(selectedCategories = current, hasChanges = true)
        }
    }

    fun setLayout(layout: EditorLayout) = _uiState.update { it.copy(layout = layout) }

    /** 顶栏那颗按钮只管「看不看预览」：只写 ↔ 边写边看；全屏预览时按它回到只写。 */
    fun togglePreview() = _uiState.update {
        it.copy(
            layout = if (it.layout == EditorLayout.WRITE) EditorLayout.SPLIT else EditorLayout.WRITE
        )
    }

    /** 拖拽排序：只搬正文里那张 `![..](..)`，算成一步撤销。 */
    fun moveImage(from: Int, to: Int) {
        val moved = MarkdownImages.move(_uiState.value.content, from, to)
        if (moved == _uiState.value.content) return
        commitJob?.cancel()
        commitNow()
        _uiState.update { it.copy(content = moved, hasChanges = true) }
        commitNow()
    }

    fun save(publish: Boolean) {
        val state = _uiState.value
        if (state.content.isBlank() && state.title.isBlank()) {
            _uiState.update { it.copy(error = "写点什么再保存吧") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            val config = getSiteConfig()
            val editing = postId

            val post = Post(
                postId = editing ?: "",
                title = state.title.ifBlank { "未命名文档" },
                content = state.content,
                excerpt = state.excerpt,
                tags = state.tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() },
                categories = state.selectedCategories,
                status = if (publish) "publish" else "draft",
                dateCreated = state.publishAt
            )

            val result = if (editing != null) {
                container.postRepository.updatePost(config, editing, post, publish, isPage).map { editing }
            } else {
                container.postRepository.createPost(config, post, publish, isPage)
            }

            result.fold(
                onSuccess = { id ->
                    // 新建时站点会回传 cid，但本站的 PHP 警告会占掉响应头部，
                    // 小于 228 字节的小回复（比如 just an int）会被整个吞掉，
                    // 这时 cid 拿不到。只有拿到才认它，别让下一篇也以为还是这篇。
                    if (editing == null && id.isNotBlank()) postId = id
                    // 已经交到站点上了，本地稿就没必要留着下次问一遍。
                    container.draftStore.discard(editing, isPage)
                    if (!isPage) syncPostCache(post)
                    // 列表页的 ViewModel 不会随返回重建，得靠这条事件才会去重取。
                    container.notifyContentChanged()
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saveSuccess = true,
                            hasChanges = false,
                            draftSavedAt = 0L,
                            postId = postId,
                            saveNotice = if (editing == null && id.isBlank())
                                "站点没有回传这篇的编号，内容已经发上去了。请从列表里继续编辑它，别在这里重复点发布。"
                            else null
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isSaving = false, error = e.message) } }
            )
        }
    }

    fun clearSaveSuccess() = _uiState.update { it.copy(saveSuccess = false, saveNotice = null) }

    /**
     * 详情页读的是文章缓存（本站 getPost 会 500，命中缓存就再也不回源），
     * 存完不更新它，退回去看到的还是改之前的样子。
     *
     * 只覆盖编辑器认识的字段：手里这篇是「半成品」，link / author / 评论数一概没有，
     * 整篇替换会把缓存里那些只有接口才知道的信息抹掉。
     */
    private suspend fun syncPostCache(edited: Post) {
        val id = edited.postId.ifBlank { postId.orEmpty() }
        if (id.isBlank()) return
        val merged = container.postCache.get(id)
            ?.copy(
                postId = id,
                title = edited.title,
                content = edited.content,
                excerpt = edited.excerpt,
                tags = edited.tags,
                categories = edited.categories,
                status = edited.status,
                dateCreated = edited.dateCreated
            )
            ?: edited.copy(postId = id)
        container.postCache.put(merged)
        container.postCacheStore.put(merged)
    }

    private suspend fun getSiteConfig(): noline.zhouzhou.data.model.SiteConfig {
        return container.settingsRepository.siteConfig.first()
    }
}

/** 编辑器工具栏支持的 Markdown 操作。每个操作把 (文本, 选区) 变换为新的 (文本, 选区)。 */
enum class MarkdownAction {
    Bold, Italic, Strike, InlineCode, CodeBlock, Link,
    Heading1, Heading2, Heading3, Quote, BulletList, OrderedList, Table, Divider;

    fun apply(text: String, sel: TextRange): Pair<String, TextRange> = when (this) {
        Bold -> wrapInline(text, sel, "**", "**", "加粗")
        Italic -> wrapInline(text, sel, "*", "*", "斜体")
        Strike -> wrapInline(text, sel, "~~", "~~", "删除线")
        InlineCode -> wrapInline(text, sel, "`", "`", "code")
        CodeBlock -> wrapInline(text, sel, "```\n", "\n```", "代码块")
        Link -> wrapInline(text, sel, "[", "](https://)", "链接文字")
        Heading1 -> linePrefix(text, sel, "# ")
        Heading2 -> linePrefix(text, sel, "## ")
        Heading3 -> linePrefix(text, sel, "### ")
        Quote -> linePrefix(text, sel, "> ")
        BulletList -> linePrefix(text, sel, "- ")
        OrderedList -> numberedPrefix(text, sel)
        Table -> insertBlock(text, sel, "\n| 列 | 列 |\n| --- | --- |\n| 内容 | 内容 |\n", 1)
        Divider -> insertBlock(text, sel, "\n---\n", 1)
    }

    private fun wrapInline(
        text: String, sel: TextRange, prefix: String, suffix: String, placeholder: String
    ): Pair<String, TextRange> {
        val start = minOf(sel.min, sel.max).coerceIn(0, text.length)
        val end = maxOf(sel.min, sel.max).coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val inner = selected.ifEmpty { placeholder }
        val newText = text.substring(0, start) + prefix + inner + suffix + text.substring(end)
        val newStart = start + prefix.length
        return newText to TextRange(newStart, newStart + inner.length)
    }

    private fun linePrefix(text: String, sel: TextRange, marker: String): Pair<String, TextRange> {
        val start = lineStart(text, minOf(sel.min, sel.max))
        val end = lineEnd(text, maxOf(sel.min, sel.max))
        val block = text.substring(start, end)
        val lines = block.split("\n")
        val allHave = lines.all { it.trimStart().startsWith(marker.trim()) || it.startsWith(marker) }
        val newLines = if (allHave) {
            lines.map { l ->
                when {
                    l.startsWith(marker) -> l.removePrefix(marker)
                    else -> l.replaceFirst(marker.trim() + " ", "").let { x -> if (x == l) x.replaceFirst(marker.trim(), "") else x }
                }
            }
        } else {
            lines.map { l -> if (l.startsWith(marker)) l else marker + l }
        }
        val newBlock = newLines.joinToString("\n")
        val newText = text.substring(0, start) + newBlock + text.substring(end)
        return newText to TextRange(start, start + newBlock.length)
    }

    private fun numberedPrefix(text: String, sel: TextRange): Pair<String, TextRange> {
        val start = lineStart(text, minOf(sel.min, sel.max))
        val end = lineEnd(text, maxOf(sel.min, sel.max))
        val block = text.substring(start, end)
        val lines = block.split("\n")
        val allHave = lines.all { Regex("^\\s*\\d+\\.\\s").containsMatchIn(it) }
        val newLines = if (allHave) {
            lines.map { it.replaceFirst(Regex("^\\s*\\d+\\.\\s"), "") }
        } else {
            lines.mapIndexed { i, l -> "${i + 1}. ${l.replaceFirst(Regex("^\\s*[-*]\\s"), "").replaceFirst(Regex("^\\s*\\d+\\.\\s"), "")}" }
        }
        val newBlock = newLines.joinToString("\n")
        val newText = text.substring(0, start) + newBlock + text.substring(end)
        return newText to TextRange(start, start + newBlock.length)
    }

    private fun insertBlock(text: String, sel: TextRange, snippet: String, caretOffset: Int): Pair<String, TextRange> {
        val pos = maxOf(sel.min, sel.max).coerceIn(0, text.length)
        val newText = text.substring(0, pos) + snippet + text.substring(pos)
        val caret = (pos + snippet.length - caretOffset).coerceIn(0, newText.length)
        return newText to TextRange(caret)
    }

    private fun lineStart(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        return i
    }
}
