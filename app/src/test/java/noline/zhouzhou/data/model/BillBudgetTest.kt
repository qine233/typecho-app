package noline.zhouzhou.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算是 App 自己算的进度：站点没有预算端点，所以「超没超」这句话一旦算错，
 * 用户在通知里看到的就是错的。
 */
class BillBudgetTest {

    @Test
    fun `an unset budget says nothing`() {
        val status = billBudgetStatus(BillBudget(), spent = 500.0, kindSpent = mapOf("veg" to 500.0))
        assertTrue(!status.active)
        assertTrue(!status.monthlyOver)
        assertEquals(0f, status.usedFraction, 0.001f)
        assertEquals("", status.alert())
    }

    @Test
    fun `monthly progress reports what is left, not what is spent only`() {
        val status = billBudgetStatus(BillBudget(monthly = 800.0), spent = 680.0, kindSpent = emptyMap())
        assertTrue(status.active)
        assertTrue(!status.monthlyOver)
        assertEquals(120.0, status.monthlyLeft, 0.001)
        assertEquals(0.85f, status.usedFraction, 0.001f)
        // 「还剩很多」不是要打扰人的事。
        assertEquals("", status.alert())
    }

    @Test
    fun `overspending the total is what puts a line in the notification`() {
        val status = billBudgetStatus(BillBudget(monthly = 800.0), spent = 920.5, kindSpent = emptyMap())
        assertTrue(status.monthlyOver)
        assertEquals(120.5, status.monthlyOverBy, 0.001)
        // 条子不许画爆表：超过 100% 也只能是 100%。
        assertEquals(1f, status.usedFraction, 0.001f)
        assertTrue(status.alert(), status.alert().startsWith("本月买菜超预算"))
        assertTrue(status.alert(), status.alert().contains("120.50"))
    }

    @Test
    fun `only the kinds with a limit are tracked`() {
        val budget = BillBudget(monthly = 800.0, perKind = mapOf("veg" to 100.0, "fish" to 200.0))
        val status = billBudgetStatus(
            budget,
            spent = 500.0,
            kindSpent = mapOf("veg" to 90.0, "fish" to 260.0, "meat" to 150.0)
        )
        // 没设额度的「肉禽」不进这张表，否则用户会以为那也是条预算线。
        assertEquals(listOf("veg", "fish"), status.kinds.map { it.kind })
        assertEquals(90.0, status.kinds.first { it.kind == "veg" }.spent, 0.001)
        assertEquals(listOf("fish"), status.kindsOver.map { it.kind })
        assertEquals(60.0, status.kindsOver.first().overBy, 0.001)
    }

    @Test
    fun `kind overrun is named even when the total is still inside`() {
        val budget = BillBudget(perKind = mapOf("fish" to 200.0))
        val status = billBudgetStatus(budget, spent = 300.0, kindSpent = mapOf("fish" to 260.0))
        assertTrue(!status.monthlyOver)
        assertTrue(status.alert(), status.alert().contains("水产超了"))
        assertTrue(status.alert(), !status.alert().contains("本月买菜超预算"))
    }

    @Test
    fun `a pile of overrun kinds is capped instead of filling the shade`() {
        val budget = BillBudget(
            monthly = 100.0,
            perKind = mapOf("veg" to 10.0, "fish" to 10.0, "meat" to 10.0, "egg" to 10.0)
        )
        val spent = mapOf("veg" to 50.0, "fish" to 50.0, "meat" to 50.0, "egg" to 50.0)
        val status = billBudgetStatus(budget, spent = 200.0, kindSpent = spent)
        assertEquals(4, status.kindsOver.size)
        val alert = status.alert()
        assertTrue(alert, alert.contains("等 4 类"))
        // 总预算那句话 + 被点名的前两类，再多就不说了。
        assertTrue(alert, alert.contains("本月买菜超预算"))
        assertTrue(alert, 1 == alert.count { it == '，' })
    }

    @Test
    fun `a centimeter of rounding is not overspending`() {
        // 金额两位小数，浮点相加会带出 800.0000000000001 这种数；别为它报「超了」。
        val status = billBudgetStatus(BillBudget(monthly = 800.0), spent = 800.0000001, kindSpent = emptyMap())
        assertTrue(!status.monthlyOver)
        assertEquals("", status.alert())
    }

    @Test
    fun `budget text accepts blank and plain numbers only`() {
        assertEquals(0.0, parseBillBudgetText("")!!, 0.001)
        assertEquals(0.0, parseBillBudgetText("  ")!!, 0.001)
        assertEquals(800.0, parseBillBudgetText("800")!!, 0.001)
        assertEquals(800.55, parseBillBudgetText(" 800.55 ")!!, 0.001)
        assertNull(parseBillBudgetText("八百"))
        assertNull(parseBillBudgetText("-5"))
        assertNull(parseBillBudgetText("1000001"))
    }

    @Test
    fun `saving normalizes away zeros and kinds the server does not know`() {
        val normalized = BillBudget(
            monthly = 799.999,
            perKind = mapOf("veg" to 0.0, "fish" to 199.999, "mystery" to 50.0)
        ).normalized()
        assertEquals(800.0, normalized.monthly, 0.001)
        assertEquals(listOf("fish"), normalized.perKind.keys.toList())
        assertEquals(200.0, normalized.perKind["fish"] ?: 0.0, 0.001)
        // other 也算「没设」，所以整个预算等于没设。
        assertTrue(!BillBudget(perKind = mapOf("veg" to 0.0)).active)
    }
}
