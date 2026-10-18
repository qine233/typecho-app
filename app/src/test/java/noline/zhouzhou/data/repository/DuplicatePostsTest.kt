package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun post(
    id: String,
    title: String,
    content: String,
    at: Long = 1000L,
    status: String = "publish"
) = Post(postId = id, title = title, content = content, dateCreated = at, status = status)

class DuplicatePostsTest {

    @Test
    fun `正文一模一样算重复 留最早的那篇`() {
        val scan = scanDuplicatePosts(
            listOf(
                post("30", "买菜记录", "今天买了葱"),
                post("12", "买菜记录", "今天买了葱", at = 500L),
                post("40", "买菜记录", "今天买了葱", at = 900L)
            )
        )
        assertEquals(1, scan.identical.size)
        val group = scan.identical.first()
        assertEquals("12", group.kept.postId)
        assertEquals(listOf("40", "30"), group.extras.map { it.postId })
        assertEquals(3, group.size)
        assertEquals(2, scan.redundant)
        assertEquals(listOf("40", "30"), scan.deletable.map { it.postId })
    }

    @Test
    fun `编号相同靠 cid 兜底 时间一样时留编号小的`() {
        val scan = scanDuplicatePosts(
            listOf(post("9", "同一刻", "同一刻的内容", at = 7L), post("4", "同一刻", "同一刻的内容", at = 7L))
        )
        assertEquals("4", scan.identical.single().kept.postId)
    }

    @Test
    fun `标题里的转义和空白先归一再比`() {
        val scan = scanDuplicatePosts(
            listOf(post("1", "&lt;TEST&gt;", "正文"), post("2", "  <TEST> ", "正文"))
        )
        assertEquals(1, scan.identical.size)
        assertEquals("<TEST>", scan.identical.single().title)
    }

    @Test
    fun `换行写法不同也算同一篇`() {
        val scan = scanDuplicatePosts(listOf(post("1", "行尾", "a\r\nb"), post("2", "行尾", "a\nb\r\n")))
        assertEquals(1, scan.identical.size)
    }

    @Test
    fun `同名不同内容只进同名栏 不给勾着删`() {
        val scan = scanDuplicatePosts(listOf(post("1", "读书笔记", "第一版"), post("2", "读书笔记", "改了一稿")))
        assertTrue(scan.identical.isEmpty())
        assertEquals(1, scan.sameTitle.size)
        assertEquals(0, scan.redundant)
        assertFalse(scan.isEmpty)
    }

    @Test
    fun `空标题的同名文章不吓人`() {
        val scan = scanDuplicatePosts(listOf(post("1", "", "甲"), post("2", "", "乙")))
        assertTrue(scan.sameTitle.isEmpty())
        assertTrue(scan.isEmpty)
    }

    @Test
    fun `占位标题不算同名 日记一天一篇很正常`() {
        val scan = scanDuplicatePosts(listOf(post("1", "日记", "今天吃了面"), post("2", "日记", "今天吃了饭")))
        assertTrue(scan.sameTitle.isEmpty())
        assertTrue(scan.isEmpty)
    }

    @Test
    fun `占位标题下正文一样照旧算重复`() {
        val scan = scanDuplicatePosts(listOf(post("1", "日记", "今天吃了面"), post("2", "日记", "今天吃了面")))
        assertEquals(1, scan.identical.size)
        assertEquals(1, scan.redundant)
        assertTrue(scan.sameTitle.isEmpty())
    }

    @Test
    fun `没有编号的行不算一篇文章`() {
        val scan = scanDuplicatePosts(listOf(post("", "草稿箱脏数据", "同一份"), post("7", "草稿箱脏数据", "同一份")))
        assertTrue(scan.identical.isEmpty())
        assertEquals(1, scan.scanned)
    }

    @Test
    fun `重复多的组排在前面`() {
        val scan = scanDuplicatePosts(
            listOf(
                post("1", "两篇", "A"), post("2", "两篇", "A"),
                post("3", "三篇", "B"), post("4", "三篇", "B"), post("5", "三篇", "B")
            )
        )
        assertEquals(listOf("三篇", "两篇"), scan.identical.map { it.title })
    }

    @Test
    fun `同名的组同时有重复时两边都列出来`() {
        val scan = scanDuplicatePosts(
            listOf(post("1", "标题", "甲"), post("2", "标题", "甲"), post("3", "标题", "乙"))
        )
        assertEquals(1, scan.identical.size)
        assertEquals("1", scan.identical.single().kept.postId)
        assertEquals(listOf("2"), scan.identical.single().extras.map { it.postId })
        assertEquals(1, scan.sameTitle.size)
        assertEquals(3, scan.sameTitle.single().size)
        assertEquals(listOf("2", "3"), scan.sameTitle.single().extras.map { it.postId })
    }
}
