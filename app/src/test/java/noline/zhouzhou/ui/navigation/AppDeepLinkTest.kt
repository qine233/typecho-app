package noline.zhouzhou.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 深链是小组件和通知唯一能把 App 送到某一页的办法，而它得在 JVM 里就能验——
 * Android 的 Uri 在单测里全是假返回，所以这里走的是自己写的那套百分号编码。
 */
class AppDeepLinkTest {

    @Test
    fun `通知的两条老链接照旧落到原来的页`() {
        assertEquals(Screen.Storage.route, parseAppLink("nline://status")?.route)
        assertEquals(Screen.Bills.route, parseAppLink("nline://bills")?.route)
        assertNull(parseAppLink("nline://bills")?.prefill)
    }

    @Test
    fun `更新下完那条通知落回设置页`() {
        // 通知自己不起安装器（后台起 Activity 会被系统拦），所以这条只是把人送回那一页。
        assertEquals(Screen.Settings.route, parseAppLink("nline://update")?.route)
        assertNull(parseAppLink("nline://update/kill-everything"))
    }

    @Test
    fun `体重卡片那两条落在健康页`() {
        assertEquals(Screen.Health.route, parseAppLink("nline://health")?.route)
        assertEquals(false, parseAppLink("nline://health")?.openAdd)
        // 那颗「记体重」要多走一步：进去当场把录入面板拉开
        assertEquals(AppLink(Screen.Health.route, openAdd = true), parseAppLink("nline://health/add"))
        // 带 /add 的旧链接不该被当成记账面板的预填
        assertNull(parseAppLink("nline://health/add")?.prefill)
    }

    @Test
    fun `空串和认不出的链接都不该跳任何页`() {
        assertNull(parseAppLink(null))
        assertNull(parseAppLink(""))
        assertNull(parseAppLink("   "))
        assertNull(parseAppLink("https://www.zhouzhou.work"))
        assertNull(parseAppLink("nline://bills/delete-all"))
        assertNull(parseAppLink("nline://health/add-all"))
    }

    @Test
    fun `add 链接不带参数时是空预填`() {
        assertEquals(AppLink(Screen.Bills.route, BillPrefill()), parseAppLink("nline://bills/add"))
    }

    @Test
    fun `中文品名带出去还能带回来`() {
        val link = AppLinks.billsAdd("五花肉", "meat")
        assertEquals("nline://bills/add?title=%E4%BA%94%E8%8A%B1%E8%82%89&kind=meat", link)
        assertEquals(BillPrefill("五花肉", "meat"), parseAppLink(link)?.prefill)
    }

    @Test
    fun `品名里的分隔符不会被当成查询参数`() {
        val link = AppLinks.billsAdd("盐&糖=3", "seasoning")
        val prefill = parseAppLink(link)?.prefill
        assertEquals("盐&糖=3", prefill?.title)
        assertEquals("seasoning", prefill?.kind)
    }

    @Test
    fun `没填的字段不会留下空参数`() {
        assertEquals("nline://bills/add", AppLinks.billsAdd())
        assertEquals("nline://bills/add?title=%E7%99%BD%E8%8F%9C", AppLinks.billsAdd(title = "白菜"))
        assertEquals("nline://bills/add?kind=veg", AppLinks.billsAdd(kind = "veg"))
    }

    @Test
    fun `参数顺序颠倒也认`() {
        val prefill = parseAppLink("nline://bills/add?kind=fruit&title=苹果")?.prefill
        assertEquals(BillPrefill("苹果", "fruit"), prefill)
    }

    @Test
    fun `分类代码只认那八个`() {
        // 链接可能被别的版本或别的 App 拼错，服务器对认不出的代码一律归 other
        assertEquals("other", parseAppLink("nline://bills/add?kind=drinks")?.prefill?.kind)
        assertEquals("", parseAppLink("nline://bills/add?title=酱油")?.prefill?.kind)
    }

    @Test
    fun `没编码的中文原样读回`() {
        assertEquals(BillPrefill("鸡蛋", "egg"), parseAppLink("nline://bills/add?title=鸡蛋&kind=egg")?.prefill)
    }

    @Test
    fun `残缺的百分号不会被吞成乱码`() {
        assertEquals("100% 黑巧", parseAppLink("nline://bills/add?title=100%25%20黑巧")?.prefill?.title)
        assertEquals("a%z", parseAppLink("nline://bills/add?title=a%z")?.prefill?.title)
    }
}
