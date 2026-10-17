package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.BillInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月报和预算都要把「还没上传的新增」算进去，靠的就是这个摊平。
 * 它出错的方式很隐蔽：要么离线记的账在报告里凭空消失，要么同一笔被算两遍。
 */
class UnsentBillRowsTest {

    private fun add(uid: String, serverId: String = "", title: String = "番茄", kind: String? = "veg") =
        PendingBill(
            uid = uid,
            serverId = serverId,
            op = BillOp.Add,
            input = BillInput(date = "2026-10-05", title = title, amount = 12.5, kind = kind),
            queuedAt = 1_700_000_000_000L
        )

    @Test
    fun `only adds that never reached the server become rows`() {
        val ops = listOf(
            add("u-1"),
            add("u-2", serverId = "20261005-001"),
            PendingBill(uid = "u-3", serverId = "20261005-002", op = BillOp.Update, queuedAt = 1L),
            PendingBill(uid = "u-4", serverId = "20261005-003", op = BillOp.Delete, queuedAt = 1L),
            PendingBill(uid = "u-5", op = BillOp.Add, input = null, queuedAt = 1L)
        )
        // 第二条已经在服务器上，概览那边会带回它；再摊平一次就成两笔了。
        assertEquals(listOf("u-1"), ops.unsentAdds().map { it.id })
    }

    @Test
    fun `a row keeps the local uid so it can still be edited or dropped`() {
        val row = listOf(add("u-1")).unsentAdds().first()
        assertEquals("u-1", row.id)
        assertEquals("番茄", row.title)
        assertEquals(12.5, row.amount, 0.001)
        assertEquals("veg", row.kind)
        // 记账的行用秒；队列里存的是毫秒。
        assertEquals(1_700_000_000L, row.time)
    }

    @Test
    fun `a missing kind shows up as other, the same way the server files it`() {
        val row = listOf(add("u-1", kind = null)).unsentAdds().first()
        assertEquals("other", row.kind)
        assertTrue(row.shop.isEmpty())
        assertTrue(row.note.isEmpty())
    }
}
