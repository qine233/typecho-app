package noline.zhouzhou.data.repository

import noline.zhouzhou.ui.screens.settings.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageRepositoryTest {

    @Test
    fun `cache files are classified by the first path segment only`() {
        assertEquals(CacheClass.IMAGE, classifyCache("coil3-cache/abc.jpg"))
        assertEquals(CacheClass.IMAGE, classifyCache("coil3-http"))
        assertEquals(CacheClass.UPDATE, classifyCache("updates/NLecho-v1.2.0-debug.apk"))
        assertEquals(CacheClass.OTHER, classifyCache("oat/arm64/base.odex"))
        // 深层目录里出现 coil 不算图片缓存，否则会误删别的目录
        assertEquals(CacheClass.OTHER, classifyCache("tmp/coil3-cache/x"))
        assertEquals(CacheClass.OTHER, classifyCache(""))
    }

    @Test
    fun `totals add up and grouped lookups only count the requested keys`() {
        val usage = StorageUsage(
            entries = listOf(
                StorageEntry(StorageRepository.KEY_APK, "安装包", 10_000_000L, false),
                StorageEntry(StorageRepository.KEY_IMAGE, "图片缓存", 4_000_000L, true),
                StorageEntry(StorageRepository.KEY_POSTS, "文章缓存", 1_500_000L, true),
                StorageEntry(StorageRepository.KEY_DRAFTS, "本地草稿", 2_000L, false),
                StorageEntry(StorageRepository.KEY_DATA_OTHER, "设置与账号数据", 30_000L, false)
            ),
            freeBytes = 1_000_000_000L
        )

        assertEquals(15_532_000L, usage.totalBytes)
        assertEquals(5_500_000L, usage.bytesOf(StorageRepository.CACHE_KEYS))
        assertEquals(10_000_000L, usage.bytesOf(StorageRepository.KEY_APK))
        assertEquals(0L, usage.bytesOf("missing-key"))
        assertEquals("本地草稿", usage.entry(StorageRepository.KEY_DRAFTS)?.label)
        assertNull(usage.entry("missing-key"))
    }

    @Test
    fun `byte formatting is locale independent`() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("0 B", formatBytes(-1L))
        assertEquals("999 B", formatBytes(999L))
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("1.5 MB", formatBytes(1_572_864L))
        assertEquals("1.00 GB", formatBytes(1_073_741_824L))
        assertEquals("18.8 MB", formatBytes(19_722_165L))
    }
}
