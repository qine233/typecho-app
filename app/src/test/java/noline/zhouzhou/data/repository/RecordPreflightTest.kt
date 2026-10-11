package noline.zhouzhou.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 客户端预检。服务器的宽松程度不一致：体重越界整条被拒，体脂越界只丢字段，
 * 备注超长直接截断，未来的日期根本不拦——所以哪些必须客户端自己拦，这里钉死。
 */
class RecordPreflightTest {

    private fun errorsOf(date: String, weight: String, fat: String = "", note: String = "") =
        RecordPreflight.validate(date, weight, fat, note)

    // 预检会拦「未来的日期」，所以样例日期必须跟着跑测试这台机器的今天走，不能写死。
    private val today: String get() = LocalDate.now().toString()

    private fun blocking(date: String, weight: String, fat: String = "", note: String = "") =
        errorsOf(date, weight, fat, note).filter { it.blocking }.map { it.field }

    @Test
    fun `normal entry passes`() {
        assertEquals(emptyList<String>(), errorsOf(today, "66.4", "18.2", "早上称的"))
    }

    @Test
    fun `fat and note are optional`() {
        assertTrue(errorsOf(today, "66.4").isEmpty())
    }

    @Test
    fun `impossible date is rejected`() {
        assertEquals(listOf("date"), blocking("2026-02-30", "66.4"))
    }

    @Test
    fun `non date string is rejected`() {
        assertEquals(listOf("date"), blocking("2026/10/12", "66.4"))
        assertTrue(RecordPreflight.parseDate("2026-10-12") == LocalDate.of(2026, 10, 12))
    }

    @Test
    fun `future date is blocked client side`() {
        // 接口自己不拦「明天」，客户端必须拦。
        val tomorrow = LocalDate.now().plusDays(1).toString()
        assertEquals(listOf("date"), blocking(tomorrow, "66.4"))
        assertTrue(blocking(LocalDate.now().toString(), "66.4").isEmpty())
    }

    @Test
    fun `weight must be a number in range`() {
        assertEquals(listOf("weight"), blocking(today, "abc"))
        assertEquals(listOf("weight"), blocking(today, "19.9"))
        assertEquals(listOf("weight"), blocking(today, "400.1"))
        assertTrue(blocking(today, "20").isEmpty())
        assertTrue(blocking(today, "400").isEmpty())
    }

    @Test
    fun `out of range fat is a warning not a blocker`() {
        val errors = errorsOf(today, "66.4", "0.5")
        assertEquals(listOf("fat"), errors.map { it.field })
        assertFalse(errors.first().blocking)
        assertTrue(
            "得说清服务器只会丢这个字段，不是整条被拒",
            errors.first().message.contains("丢掉")
        )
    }

    @Test
    fun `non numeric fat blocks`() {
        assertEquals(listOf("fat"), blocking(today, "66.4", "低"))
    }

    @Test
    fun `over long note is a warning`() {
        val errors = errorsOf(today, "66.4", "", "字".repeat(201))
        assertEquals(listOf("note"), errors.map { it.field })
        assertFalse(errors.first().blocking)
    }

    @Test
    fun `today is formatted the way the api wants`() {
        val fromApi = RecordPreflight.today()
        assertEquals(10, fromApi.length)
        assertEquals(LocalDate.now().toString(), fromApi)
    }
}
