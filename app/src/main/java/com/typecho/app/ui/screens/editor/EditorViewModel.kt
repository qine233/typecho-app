package com.typecho.app.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.typecho.app.AppContainer
import com.typecho.app.data.model.Category
import com.typecho.app.data.model.Post
import com.typecho.app.util.HtmlToMarkdown
import com.typecho.app.util.ImageCompressor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val isPreview: Boolean = false,
    val hasChanges: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isImmersive: Boolean = false
)

class EditorViewModel(
    private val container: AppContainer,
    private val postId: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState(postId = postId))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // 正文撤销/重做：以「快照」为步长，输入停顿 600ms 或点击工具栏时提交一次。
    private data class Snapshot(val text: String, val selection: TextRange)
    private val history = mutableListOf(Snapshot("", TextRange(0)))
    private var historyIndex = 0
    private var commitJob: Job? = null

    init {
        loadCategories()
        if (postId != null) loadPost()
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
                        status = post.status
                    )
                }
                resetHistory(md)
            }
            // 优先用列表缓存，绕开站点 getPost 的 500。
            container.postCache.get(postId!!)?.let { cached ->
                fill(cached)
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            val config = getSiteConfig()
            val result = container.postRepository.getPost(config, postId!!)
            result.fold(
                onSuccess = { post ->
                    container.postCache.put(post)
                    fill(post)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
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
            val config = getSiteConfig()
            val result = try {
                val image = withContext(Dispatchers.IO) {
                    ImageCompressor.compress(container.application.contentResolver, uri)
                }
                container.postRepository.uploadImage(
                    config, image.name, image.mimeType, image.bytes
                )
            } catch (e: Exception) {
                Result.failure<String>(e)
            }
            result.fold(
                onSuccess = { url ->
                    val src = url.replace(" ", "%20")
                    val alt = src.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[\\[\\]()\"]"), "")
                    insertAtCaret("![$alt]($src)")
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

    fun toggleCategory(name: String) {
        _uiState.update { state ->
            val current = state.selectedCategories.toMutableList()
            if (current.contains(name)) current.remove(name) else current.add(name)
            state.copy(selectedCategories = current, hasChanges = true)
        }
    }

    fun togglePreview() = _uiState.update { it.copy(isPreview = !it.isPreview) }

    fun save(publish: Boolean) {
        val state = _uiState.value
        if (state.content.isBlank() && state.title.isBlank()) {
            _uiState.update { it.copy(error = "写点什么再保存吧") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            val config = getSiteConfig()

            val post = Post(
                postId = postId ?: "",
                title = state.title.ifBlank { "未命名文档" },
                content = state.content,
                excerpt = state.excerpt,
                tags = state.tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() },
                categories = state.selectedCategories,
                status = if (publish) "publish" else "draft"
            )

            val result = if (postId != null) {
                container.postRepository.updatePost(config, postId, post, publish)
            } else {
                container.postRepository.createPost(config, post, publish)
            }

            result.fold(
                onSuccess = { _uiState.update { it.copy(isSaving = false, saveSuccess = true, hasChanges = false) } },
                onFailure = { e -> _uiState.update { it.copy(isSaving = false, error = e.message) } }
            )
        }
    }

    fun clearSaveSuccess() = _uiState.update { it.copy(saveSuccess = false) }

    private suspend fun getSiteConfig(): com.typecho.app.data.model.SiteConfig {
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
