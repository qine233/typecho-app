package noline.zhouzhou.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.data.model.BtCatalog

/**
 * 接口目录的读法：随包打的那份 `assets/bt_catalog.json` 只在第一次用到时读进内存，
 * 之后每次进调用台都直接吃缓存——它跟 APK 一起发版，进程活着期间不会变。
 */
class BtCatalogStore(private val context: Context) {

    @Volatile
    private var cached: BtCatalog? = null

    suspend fun load(): BtCatalog = withContext(Dispatchers.IO) {
        cached ?: readAsset()?.also { cached = it } ?: BtCatalog(emptyList())
    }

    /** 读坏了就当没有：调用台会明说「目录没读出来」，而不是摆一张空列表。 */
    private fun readAsset(): BtCatalog? = runCatching {
        val text = context.assets.open(AssetName).bufferedReader().use { it.readText() }
        BtCatalog.parse(text)
    }.getOrNull()?.takeIf { it.endpoints.isNotEmpty() }

    companion object {
        const val AssetName = "bt_catalog.json"
    }
}
