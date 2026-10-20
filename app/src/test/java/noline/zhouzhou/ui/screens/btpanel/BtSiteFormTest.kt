package noline.zhouzhou.ui.screens.btpanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建站表单和规则表单的校验。这些门槛全是正式面板实测出来的：
 * 域名带前缀面板会照着建出一个错的站，规则名撞车会盖掉已有那条，所以只能在这儿挡。
 */
class BtSiteFormTest {

    @Test
    fun `a bare hostname is the only thing the domain box takes`() {
        assertNull(BtNewSiteForm.domainError("aa-test.work"))
        assertNotNull(BtNewSiteForm.domainError("https://aa-test.work"))
        assertNotNull(BtNewSiteForm.domainError("http://aa-test.work/"))
        assertNotNull(BtNewSiteForm.domainError("aa-test.work/path"))
        // 端口单独一格，混进来会被当成域名的一部分建进配置里。
        assertNotNull(BtNewSiteForm.domainError("aa-test.work:8080"))
        assertNotNull(BtNewSiteForm.domainError("aa test.work"))
        assertNotNull(BtNewSiteForm.domainError(""))
        assertNotNull(BtNewSiteForm.domainError("站点 一"))
        // 泛解析的那颗星面板不收，界面上先挡住。
        assertNotNull(BtNewSiteForm.domainError("*.aa-test.work"))
    }

    @Test
    fun `domain is trimmed and lowercased before it goes out`() {
        assertEquals("aa-test.work", BtNewSiteForm.cleanDomain("  AA-Test.work "))
    }

    @Test
    fun `an ip is a legal site target`() {
        assertNull(BtNewSiteForm.domainError("182.254.182.165"))
    }

    @Test
    fun `rule name has the panel length window`() {
        assertNull(BtSiteRuleForm.ruleNameError("aa-test-rule"))
        assertNotNull(BtSiteRuleForm.ruleNameError("ab"))
        assertNotNull(BtSiteRuleForm.ruleNameError(""))
        assertNotNull(BtSiteRuleForm.ruleNameError("x".repeat(41)))
        assertNull(BtSiteRuleForm.ruleNameError("x".repeat(40)))
    }

    @Test
    fun `targets must carry the scheme`() {
        assertNotNull(BtSiteRuleForm.urlError("new.work"))
        assertNotNull(BtSiteRuleForm.urlError(""))
        assertNull(BtSiteRuleForm.urlError("https://new.work/"))
        assertNull(BtSiteRuleForm.urlError("http://127.0.0.1:8080"))
    }

    @Test
    fun `path box may stay empty but must start with a slash`() {
        assertNull(BtSiteRuleForm.pathError(""))
        assertNull(BtSiteRuleForm.pathError("/api"))
        assertNotNull(BtSiteRuleForm.pathError("api"))
    }

    @Test
    fun `tabs are in the order the panel settings row uses`() {
        assertEquals(
            listOf("域名", "网站设置", "伪静态", "SSL", "重定向", "反向代理", "日志", "安全", "备份"),
            BtSiteTab.entries.map { it.label }
        )
    }

    @Test
    fun `a fresh detail state says 还没读到 rather than 空`() {
        val site = noline.zhouzhou.data.model.BtSite(
            id = "7", name = "aa-test.work", path = "/www/wwwroot/aa-test.work",
            remark = "", addTime = "", expireDate = "", typeLabel = "PHP", phpVersion = "84",
            running = true, stopReason = "", domainCount = 1, backupCount = null,
            hasProxy = false, hasRedirect = false,
            ssl = noline.zhouzhou.data.model.BtSiteSsl(false, "", "", null),
            raw = kotlinx.serialization.json.JsonObject(emptyMap())
        )
        val state = BtSiteDetailUiState(site = site)
        // null 是「这一格还没读」，界面上写「正在读」；空列表才是「面板说没有」。
        assertNull(state.domains)
        assertNull(state.proxies)
        assertTrue(state.tab == BtSiteTab.Domains)
        assertFalse(state.busy)
    }

    @Test
    fun `list paging knows when there is more to fetch`() {
        val base = BtSitesUiState()
        // 面板没报总数时，只能拿「这一页取满没满」当有没有下一页的唯一线索。
        assertFalse(base.hasMore)
        assertTrue(base.copy(sites = List(30) { i -> baseSite(i.toString()) }, total = null).hasMore)
        assertFalse(base.copy(sites = List(30) { i -> baseSite(i.toString()) }, total = 30).hasMore)
        assertTrue(base.copy(sites = listOf(baseSite("1")), total = 9).hasMore)
    }

    private fun baseSite(id: String) = noline.zhouzhou.data.model.BtSite(
        id = id, name = "s$id.work", path = "", remark = "", addTime = "", expireDate = "",
        typeLabel = "", phpVersion = "", running = true, stopReason = "", domainCount = null,
        backupCount = null, hasProxy = false, hasRedirect = false,
        ssl = noline.zhouzhou.data.model.BtSiteSsl(false, "", "", null),
        raw = kotlinx.serialization.json.JsonObject(emptyMap())
    )
}
