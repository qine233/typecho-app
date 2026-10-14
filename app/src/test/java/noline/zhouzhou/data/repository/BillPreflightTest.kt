package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.BillAggregate
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.BillRecord
import noline.zhouzhou.data.model.BillSpan
import noline.zhouzhou.data.model.billAmountText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 记账预检 + 客户端算的涨跌。规矩来自 Bills.php：
 * 品名和金额越界是整条拒，分类认错和长文本截断是**静默改**，所以后者只能由 App 提前说。
 */
class BillPreflightTest {

    @Test
    fun `blank title and non numeric amount stop the submit`() {
        val empty = BillPreflight.validate("   ", "3.5", "2026-10-14")
        assertTrue(empty.any { it.field == "title" && it.blocking })

        val text = BillPreflight.validate("黄瓜", "三块五", "2026-10-14")
        assertTrue(text.any { it.field == "amount" && it.blocking })

        // 正常一条不该有任何提示，否则面板一打开就满屏红。
        assertTrue(BillPreflight.validate("黄瓜", "3.5", "2026-10-14").isEmpty())
    }

    @Test
    fun `amount out of range is rejected whole while three decimals only warns`() {
        assertTrue(BillPreflight.validate("黄瓜", "0", "2026-10-14").any { it.field == "amount" && it.blocking })
        assertTrue(BillPreflight.validate("黄瓜", "200000", "2026-10-14").any { it.field == "amount" && it.blocking })

        val rounding = BillPreflight.validate("黄瓜", "3.456", "2026-10-14")
        assertEquals(1, rounding.size)
        assertFalse(rounding.single().blocking)
    }

    @Test
    fun `a title over the cap warns instead of blocking`() {
        // 60 字整是上限，服务器不动它
        assertTrue(BillPreflight.validate("x".repeat(60), "3.5", "2026-10-14").isEmpty())
        val over = BillPreflight.validate("x".repeat(61), "3.5", "2026-10-14")
        assertEquals("title", over.single().field)
        assertFalse(over.single().blocking)
        // 警告归警告，提交前自己截到 60，别指望服务器截。
        assertEquals(60, BillPreflight.toInput("2026-10-14", "x".repeat(61), "3.5", "", "", "").title.length)
    }

    @Test
    fun `feb 30 and a future day are both blocking`() {
        // STRICT 解析：2 月 30 日不是「被服务器挪到 28 日」，是根本不存在，checkdate 直接拒。
        assertTrue(BillPreflight.validate("黄瓜", "3.5", "2026-02-30").any { it.field == "date" && it.blocking })
        val tomorrow = LocalDate.now().plusDays(1).toString()
        assertTrue(BillPreflight.validate("黄瓜", "3.5", tomorrow).any { it.field == "date" && it.blocking })
    }

    @Test
    fun `input is washed into what the api wants`() {
        val input = BillPreflight.toInput(
            date = "2026-10-14",
            title = "  三文鱼  ",
            amountText = " 59.949 ",
            kind = "fish",
            shop = " 盒马 ",
            note = "  "
        )
        assertEquals("三文鱼", input.title)
        assertEquals(59.95, input.amount, 0.0001)
        assertEquals("fish", input.kind)
        assertEquals("盒马", input.shop)
        // 空可选字段整个不发，别让服务器当成「传了个空值」。
        assertNull(input.note)

        val bare = BillPreflight.toInput("2026-10-14", "盐", "2", "", "", "")
        assertNull(bare.kind)
        assertNull(bare.shop)
        assertEquals(2.0, bare.amount, 0.0001)
    }

    @Test
    fun `update carries the server id and keeps blanks as clears`() {
        val record = BillRecord(id = "20261014-002", date = "2026-10-14", title = "三文鱼", amount = 59.9, kind = "fish", shop = "盒马")
        val update = BillPreflight.toUpdate(record, "2026-10-13", "金枪鱼", "88", "fish", "", "")
        assertEquals("20261014-002", update.id)
        assertEquals("2026-10-13", update.date)
        // PUT 里 "" 是清空，不是「没带这个键」。
        assertEquals("", update.shop)
        assertEquals(88.0, update.amount, 0.0001)
    }

    @Test
    fun `unknown kind codes fall back to other`() {
        // 服务器会把认不出的代码静默归 other，选择器只能给这 8 个。
        assertEquals("other", BillKind.normalize("seafood"))
        assertEquals("veg", BillKind.normalize("veg"))
        assertEquals(8, BillKind.Codes.size)
        assertEquals("其他", BillKind.label("seafood"))
    }

    @Test
    fun `delta hides itself when the baseline period is empty`() {
        val current = BillAggregate(amount = 30.0, count = 2)
        assertNull(billDelta(current, null))
        // 上期为 0 笔时「省了本期全部」是假话，画 —。
        assertNull(billDelta(current, BillAggregate(amount = 0.0, count = 0)))
        assertNull(billDelta(null, current))
        assertEquals(-12.0, billDelta(current, BillAggregate(amount = 42.0, count = 3)) ?: 0.0, 0.0001)
        assertEquals(30.0, billDelta(current, BillAggregate(amount = 0.0, count = 1)) ?: 0.0, 0.0001)
    }

    @Test
    fun `partial periods are labelled with the days already gone`() {
        assertEquals("1/7 天", BillSpan(weekday = 1).weekFraction)
        assertEquals("7/7 天", BillSpan(weekday = 99).weekFraction)
        assertEquals("1/30 天", BillSpan(monthDays = 30).monthFraction)
        assertEquals("31/31 天", BillSpan(monthDays = 31, monthDayOf = 31).monthFraction)
    }

    @Test
    fun `money always shows two decimals`() {
        assertEquals("3.50", billAmountText(3.5))
        assertEquals("88.00", billAmountText(88.0))
    }
}
