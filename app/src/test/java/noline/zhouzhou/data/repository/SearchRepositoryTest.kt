package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 站点只回 10 条，本机这一路补上的那几篇正是这个功能存在的理由，所以合并规则必须有测试。 */
class SearchRepositoryTest {

    private fun post(id: String, title: String = "", content: String = "", excerpt: String = "") =
        Post(postId = id, title = title, content = content, excerpt = excerpt)

    @Test
    fun `local hits skip whatever the server already returned`() {
        val server = listOf(post("900", "Typecho 备忘"))
        val loaded = listOf(post("900", "Typecho 备忘"), post("901", "Typecho 升级踩坑"), post("902", "买菜"))

        val merged = SearchRepository.combine(server, loaded, "Typecho")

        assertEquals(listOf("901"), merged.map { it.postId })
    }

    @Test
    fun `matches look in the body too, not only the title`() {
        assertTrue(SearchRepository.matches(post("1", "", "<p>今天去买了番茄</p>"), "番茄"))
        assertTrue(SearchRepository.matches(post("1", "番茄炒蛋"), "番茄"))
        assertTrue(SearchRepository.matches(post("1", "", "", "摘要里有番茄"), "番茄"))
    }

    @Test
    fun `matching ignores case because titles are a mix of scripts`() {
        assertTrue(SearchRepository.matches(post("1", "TYPECHO 转 Vuepress"), "typecho"))
        assertTrue(SearchRepository.matches(post("1", "TYPECHO 转 Vuepress"), "vuePRESS"))
    }

    @Test
    fun `an empty term never matches everything`() {
        assertFalse(SearchRepository.matches(post("1", "任意"), "  "))
        assertTrue(SearchRepository.combine(listOf(post("1", "任意")), emptyList(), "").isEmpty())
    }

    @Test
    fun `the cap is reported only when the site actually filled its quota`() {
        val ten = (1..10).map { post(it.toString(), "文章$it") }

        assertTrue(SearchResults(server = ten).probablyCapped)
        assertFalse(SearchResults(server = ten.take(9)).probablyCapped)
        assertFalse(SearchResults().probablyCapped)
    }
}
