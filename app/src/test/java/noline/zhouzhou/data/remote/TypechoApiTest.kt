package noline.zhouzhou.data.remote

import noline.zhouzhou.data.model.Post
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TypechoApiTest {

    private val api = TypechoApi()

    /** Typecho 那边是定长 substr 解析，格式错一位就把时间读成别的字段。 */
    private fun epochOf(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis / 1000L

    @Test
    fun `formatIso8601 - 定长十字段加冒号`() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            assertEquals("20261012T21:40:00", api.formatIso8601(epochOf(2026, 10, 12, 21, 40)))
            // 个位月/日/时都要补零，否则 IXR 的 substr 会错位
            assertEquals("20260101T09:05:00", api.formatIso8601(epochOf(2026, 1, 1, 9, 5)))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `buildPostStruct - 有日期才带 dateCreated`() {
        val withDate = api.buildPostStruct(
            Post(title = "标题", content = "正文", dateCreated = epochOf(2026, 3, 4, 8, 9)),
            isPage = false
        )
        val value = withDate["dateCreated"]
        assertTrue(value is XmlRpcValue.DateTimeValue)
        assertEquals("20260304T08:09:00", (value as XmlRpcValue.DateTimeValue).value)

        // created=0 的老日记：宁可不给，让站点自己决定，也别塞个 1970 进去
        val withoutDate = api.buildPostStruct(Post(title = "标题", content = "正文"), isPage = false)
        assertFalse(withoutDate.containsKey("dateCreated"))
    }

    @Test
    fun `buildPostStruct - 状态键随类型换名`() {
        val post = api.buildPostStruct(Post(title = "t", content = "c", status = "publish"), false)
        assertEquals("publish", post["post_status"])
        assertNull(post["page_status"])
        assertNull(post["post_type"])

        val page = api.buildPostStruct(Post(title = "t", content = "c", status = "draft"), true)
        assertEquals("draft", page["page_status"])
        assertNull(page["post_status"])
        assertEquals("page", page["post_type"])
    }

    @Test
    fun `newPost 请求体 - dateTime 用 iso8601 标签`() {
        val struct = api.buildPostStruct(
            Post(title = "标题", content = "正文", dateCreated = epochOf(2026, 10, 12, 21, 40)),
            isPage = false
        )
        val xml = XmlRpcClient().buildRequest(
            "metaWeblog.newPost",
            listOf("1", "admin", "secret", struct, true)
        )
        assertTrue(xml.contains("<dateTime.iso8601>20261012T21:40:00</dateTime.iso8601>"))
        assertTrue(xml.contains("<name>dateCreated</name>"))
    }
}
