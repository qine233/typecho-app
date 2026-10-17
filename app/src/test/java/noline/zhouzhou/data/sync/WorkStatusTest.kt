package noline.zhouzhou.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkStatusTest {

    @Test
    fun `pending count adds both queues`() {
        assertEquals(5, WorkStatus(pendingHealth = 2, pendingBills = 3).pendingTotal)
    }

    @Test
    fun `headline prefers pending over drafts`() {
        // 待同步是「不处理就会丢」的那一类，草稿只是还没写完，所以前者优先。
        assertEquals(
            "待同步 2 条",
            WorkStatus(pendingBills = 2, drafts = 7).headline()
        )
        assertEquals("本地草稿 7 份", WorkStatus(drafts = 7).headline())
        assertEquals("没有待办", WorkStatus().headline())
    }

    @Test
    fun `detail says background flush is off instead of waiting for network`() {
        val text = WorkStatus(autoSyncOn = false, pendingHealth = 1).detail("10-18 03:00")
        assertTrue(text, text.startsWith("后台补交已关"))
        // 关了后台补交就不该再承诺「等有网自动交」
        assertTrue(text, !text.contains("等有网"))
    }

    @Test
    fun `detail reports the last flush with the note from the repository`() {
        val text = WorkStatus(lastFlushAt = 1L, lastFlushNote = "记账已交完").detail("10-18 03:00")
        assertEquals("上次补交 10-18 03:00 · 记账已交完", text)
    }

    @Test
    fun `detail falls back to a plain line when nothing happened`() {
        assertEquals("队列是空的", WorkStatus().detail(""))
        assertEquals(
            "草稿要在 App 里处理",
            WorkStatus(drafts = 2).detail("")
        )
    }
}
