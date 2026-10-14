package noline.zhouzhou.ui.screens.drive

import noline.zhouzhou.data.remote.CloudreveShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网盘页上「点了以后要讲给人听」的那几句话，以及本地文件名的兜底处理。 */
class DriveCopyTest {

    @Test
    fun `the share hint spells out what the other side sees`() {
        assertEquals("任何人打开就能看，长期有效。", shareHint("", 0))
        assertEquals("任何人打开就能看，7 天后失效。", shareHint("", 604_800))
        assertEquals(
            "链接末尾已经带上提取码，对方不用手输，1 天后失效。",
            shareHint("qoder777", 86_400)
        )
        // 不是整天的有效期只能按小时说，别说成「0 天后失效」
        assertEquals("任何人打开就能看，12 小时后失效。", shareHint("", 43_200))
    }

    @Test
    fun `a local file name is made safe for the drive path`() {
        assertEquals("照片.jpg", sanitizeDriveName("照片.jpg"))
        // SAF 偶尔给带路径的名字，斜杠会被 Cloudreve 当成目录层级
        assertEquals("a-b.jpg", sanitizeDriveName("a/b.jpg"))
        assertEquals("未命名文件", sanitizeDriveName("   "))
        assertEquals("未命名文件", sanitizeDriveName("..."))
        assertEquals("report.pdf", sanitizeDriveName(" report.pdf "))
    }

    @Test
    fun `a share line names the access and the password state`() {
        val share = CloudreveShare(
            id = "8Lid", name = "mimi.png", url = "https://p/s/8Lid/qoder777",
            visited = 3, downloaded = 1, password = "qoder777", passwordProtected = true
        )
        assertEquals("需要提取码 · 访问 3 次 · 下载 1 次", share.shareMetaText())

        val open = CloudreveShare(id = "9Yke", name = "image", visited = 0)
        assertEquals("公开 · 访问 0 次 · 下载 0 次", open.shareMetaText())

        val gone = open.copy(expired = true)
        assertTrue(gone.shareMetaText().contains("已过期"))
    }

    /** 不过期的分享要么不给 expires，要么给 Go 的零值时间，两种都得说成「长期有效」。 */
    @Test
    fun `the expiry line tells future and past apart`() {
        assertEquals("长期有效", CloudreveShare(id = "1").lifeText())
        assertEquals("长期有效", CloudreveShare(id = "1", expires = "0001-01-01T00:00:00Z").lifeText())
        assertEquals("长期有效", CloudreveShare(id = "1", expires = "not a time").lifeText())

        val future = CloudreveShare(id = "1", expires = "2999-01-02T03:04:00+08:00")
        assertTrue(future.lifeText(), future.lifeText().startsWith("到 2999-01-02 03:04"))

        val past = CloudreveShare(id = "1", expires = "2000-01-02T03:04:00+08:00")
        assertTrue(past.lifeText(), past.lifeText().startsWith("已于 2000-01-02 03:04"))
    }

    @Test
    fun `an unparsable created time is left out instead of invented`() {
        assertEquals("2026-10-14 10:00", CloudreveShare(createdAt = "2026-10-14T10:00:00+08:00").createdText())
        assertEquals("", CloudreveShare(createdAt = "").createdText())
        assertEquals("", CloudreveShare(createdAt = "昨天").createdText())
        assertFalse(CloudreveShare(name = "").isFolder)
        assertTrue(CloudreveShare(name = "", sourceType = 1).isFolder)
    }
}
