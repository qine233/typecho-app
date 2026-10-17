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

    @Test
    fun `budget alert takes the headline only when the queues are clean`() {
        val over = WorkStatus(budgetAlert = "本月买菜超预算 120.00 元")
        assertEquals("本月买菜超预算 120.00 元", over.headline())
        // 还有待交的东西时，那条更要紧——它不处理会真的丢。
        assertEquals(
            "待同步 1 条",
            WorkStatus(pendingBills = 1, budgetAlert = "本月买菜超预算 120.00 元").headline()
        )
        // 草稿同理：超支是「这个月」的事，草稿是「还没写完」的事，前者更能推动人去打开 App。
        assertEquals(
            "本月买菜超预算 120.00 元",
            WorkStatus(drafts = 3, budgetAlert = "本月买菜超预算 120.00 元").headline()
        )
    }

    @Test
    fun `detail mentions the budget alert only when the headline is busy with something else`() {
        val over = "本月买菜超预算 120.00 元"
        // 待同步占着标题，超支这句话就落到正文。
        val pending = WorkStatus(pendingHealth = 2, budgetAlert = over).detail("")
        assertTrue(pending, pending.contains(over))
        assertTrue(pending, pending.startsWith("等有网自动交"))
        // 标题已经是它了，正文就别再念一遍。
        val bare = WorkStatus(budgetAlert = over).detail("")
        assertTrue(bare, !bare.contains(over))
        assertEquals("点开看看这个月哪类超了", bare)
    }

    @Test
    fun `a bare budget alert opens the bills page, anything else opens the queue page`() {
        val over = "本月买菜超预算 120.00 元"
        assertEquals(StatusNotifier.DEEP_LINK_BILLS, WorkStatus(budgetAlert = over).targetLink())
        // 有待交的东西时，点开该看到的是队列出口。
        assertEquals(
            StatusNotifier.DEEP_LINK,
            WorkStatus(pendingBills = 1, budgetAlert = over).targetLink()
        )
        assertEquals(StatusNotifier.DEEP_LINK, WorkStatus().targetLink())
    }
}
