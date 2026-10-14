package noline.zhouzhou.ui.screens.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.remote.CloudreveShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class ShareUiState(
    val shares: List<CloudreveShare> = emptyList(),
    val nextToken: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

/**
 * 分享管理：列出这个账号建过的所有分享，能复制链接、也能当场撤销。
 * 服务端按创建时间倒序给，游标翻页，翻到底就没有 next_token。
 */
class ShareViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareUiState(isLoading = true))
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun reload() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, nextToken = "") }
            container.cloudreveRepository.shares().fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            shares = page.shares.map { share -> share.withFullUrl(site()) },
                            nextToken = page.pagination?.nextToken.orEmpty(),
                            isLoading = false
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            )
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.nextToken.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            container.cloudreveRepository.shares(state.nextToken).fold(
                onSuccess = { page ->
                    _uiState.update {
                        val seen = it.shares.map { s -> s.id }.toSet()
                        it.copy(
                            shares = it.shares + page.shares
                                .filter { s -> s.id !in seen }
                                .map { s -> s.withFullUrl(site()) },
                            nextToken = page.pagination?.nextToken.orEmpty(),
                            isLoadingMore = false
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoadingMore = false, error = e.message) } }
            )
        }
    }

    /** 撤销只是让链接失效，网盘里的文件还在。 */
    fun revoke(share: CloudreveShare) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val result = container.cloudreveRepository.deleteShare(share)
            _uiState.update { it.copy(isBusy = false, error = result.exceptionOrNull()?.message) }
            if (result.isFailure) return@launch
            _uiState.update { state ->
                state.copy(
                    shares = state.shares.filterNot { it.id == share.id },
                    notice = "已撤销「${share.name}」的分享"
                )
            }
        }
    }

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

    /** 复制动作本身没声音，给一句提示才算交代清楚。 */
    fun markCopied(share: CloudreveShare) =
        _uiState.update { it.copy(notice = "已复制「${share.name}」的链接") }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /** 分享列表给的是相对路径还是完整域名，各版本不一致，所以统一在这里补成能直接打开的地址。 */
    private suspend fun site(): String =
        container.settingsRepository.cloudreveConfig.first().site.trimEnd('/')
}

private fun CloudreveShare.withFullUrl(site: String): CloudreveShare {
    val raw = url.ifBlank { "/s/$id" }
    val full = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        site.isBlank() -> raw
        else -> site + "/" + raw.trimStart('/')
    }
    return copy(url = full)
}

/** 一行说明：公开还是需要提取码、有没有过期、被访问和下载了多少次。 */
fun CloudreveShare.shareMetaText(): String = buildList {
    add(if (hasPassword) "需要提取码" else "公开")
    if (expired) add("已过期")
    add("访问 $visited 次")
    add("下载 $downloaded 次")
}.joinToString(" · ")

/**
 * 有效期。Cloudreve 对不过期的分享要么不给 expires，要么给零值时间，两种都当成「长期有效」。
 */
fun CloudreveShare.lifeText(): String {
    if (expires.isBlank() || expires.startsWith("0001-01-01")) return "长期有效"
    return runCatching {
        val at = OffsetDateTime.parse(expires)
        if (at.isBefore(OffsetDateTime.now())) "已于 " + at.format(ShareTimeFormat) + " 失效"
        else "到 " + at.format(ShareTimeFormat)
    }.getOrDefault("长期有效")
}

private val ShareTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 创建时间；解析不出来就不显示，别编一个假时间给用户。 */
fun CloudreveShare.createdText(): String =
    runCatching { OffsetDateTime.parse(createdAt).format(ShareTimeFormat) }.getOrDefault("")
