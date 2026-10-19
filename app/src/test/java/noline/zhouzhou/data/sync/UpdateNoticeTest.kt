package noline.zhouzhou.data.sync

import noline.zhouzhou.data.repository.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知栏那几行字是纯函数，所以能在这儿验。
 * 值得写测试的地方是「字节数怎么报」和「来源没报总长时别编百分比」——
 * 这两处一旦写错，人看到的就是个假进度。
 */
class UpdateNoticeTest {

    @Test
    fun `标题带上在下的版本号`() {
        assertEquals("NLecho · 正在下载 1.15.0", updateNoticeTitle("1.15.0"))
    }

    @Test
    fun `有总长时报字节数和百分比`() {
        val progress = DownloadProgress(writtenBytes = 5_242_880, totalBytes = 22_020_090)
        assertEquals("5.0 MB / 21.0 MB · 23%", updateNoticeText(progress))
    }

    @Test
    fun `来源没报总长时不硬编百分比`() {
        val progress = DownloadProgress(writtenBytes = 1_048_576, totalBytes = 0)
        assertFalse(progress.hasTotal)
        assertEquals(0f, progress.fraction)
        assertEquals(-1, updatePercent(progress))
        assertEquals("已下 1.0 MB，这条来源没报总大小", updateNoticeText(progress))
    }

    @Test
    fun `超发的字节数按满格算`() {
        // 镜像站报的 Content-Length 偶尔比实际少，越界必须夹住，不然通知条画到屏外。
        val progress = DownloadProgress(writtenBytes = 200, totalBytes = 100)
        assertEquals(1f, progress.fraction)
        assertEquals(100, updatePercent(progress))
    }

    @Test
    fun `刚起步是 0 而不是没报`() {
        val progress = DownloadProgress(writtenBytes = 0, totalBytes = 22_020_090)
        assertTrue(progress.hasTotal)
        assertEquals(0, updatePercent(progress))
    }

    @Test
    fun `小文件按 KB 和 B 报`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
    }

    @Test
    fun `失败那条要说清是没下成`() {
        assertEquals(
            "更新包没下成：ghfast.top 拿到的包大小不对（100 / 200 字节）",
            updateFailedText("ghfast.top 拿到的包大小不对（100 / 200 字节）")
        )
    }
}
