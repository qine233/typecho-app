package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.LocalDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStoreTest {

    @Test
    fun `draft key separates new post, existing post and page`() {
        assertEquals("post_new", draftKey(null, false))
        assertEquals("post_new", draftKey("", false))
        assertEquals("page_new", draftKey(null, true))
        assertEquals("post_12", draftKey("12", false))
        assertEquals("page_7", draftKey("7", true))
    }

    /** postId 是导航参数，可能什么都能进来，不能让文件名带路径。 */
    @Test
    fun `draft key refuses to escape the drafts directory`() {
        assertEquals("post_etcpasswd", draftKey("../../etc/passwd", false))
        assertEquals("post_new", draftKey("///", false))
        assertEquals("post_ab1", draftKey("a b\t1", false))
    }

    @Test
    fun `only whitespace counts as an empty draft`() {
        assertTrue(LocalDraft(title = "  ", content = "\n\n").isBlank)
        assertFalse(LocalDraft(content = "第一段").isBlank)
        assertFalse(LocalDraft(title = "未命名").isBlank)
    }
}
