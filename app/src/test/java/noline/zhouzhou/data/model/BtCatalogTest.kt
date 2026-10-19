package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接口目录与回包视图的单测。
 *
 * 目录是随包打的一份数据，所以「那份文件本身能不能解析」也必须测——
 * 它一旦坏了，调用台整页是空的，而界面只会安静地显示「0 条」。
 */
class BtCatalogTest {

    private val fixture = """
        [
          {"m":"site","mc":"网站管理","k":"a","v":"POST","p":"/site","a":"GetSiteNews","ti":"站点用量","g":"read",
           "ps":[{"n":"site_id","r":1,"t":"int","d":"站点 id"}]},
          {"m":"site","mc":"网站管理","k":"a","v":"POST","p":"/site","a":"StopSite","ti":"停止站点","g":"write"},
          {"m":"site","mc":"网站管理","k":"a","v":"POST","p":"/site","a":"DeleteSite","ti":"删除站点","g":"destroy",
           "ps":[{"n":"id","r":1,"t":"int","d":""},{"n":"path","r":0,"t":"string","d":"顺带删根目录"}]},
          {"m":"database","mc":"数据库管理","k":"a","v":"POST","p":"/database","a":"GetDatabase","ti":"数据库列表","g":"read"},
          {"m":"docker","mc":"Docker","k":"p","v":"GET","p":"/btdocker/container/get_list","a":"get_list","ti":"容器列表","g":"read"}
        ]
    """.trimIndent()

    @Test
    fun `modules group by chinese title and put reads first`() {
        val catalog = BtCatalog.parse(fixture)
        assertEquals(5, catalog.endpoints.size)
        val site = catalog.modules.first { it.title == "网站管理" }
        assertEquals(listOf("GetSiteNews", "StopSite", "DeleteSite"), site.endpoints.map { it.name })
        assertEquals(listOf("Docker", "数据库管理", "网站管理"), catalog.modules.map { it.title })
    }

    @Test
    fun `kind and danger decide how the request is shaped`() {
        val catalog = BtCatalog.parse(fixture)
        val actionStyle = catalog.endpoints.first { it.name == "GetSiteNews" }
        assertTrue(actionStyle.sendsActionParam)
        assertEquals(BtDanger.Read, actionStyle.danger)

        val pathStyle = catalog.endpoints.first { it.moduleTitle == "Docker" }
        assertFalse(pathStyle.sendsActionParam)
        assertTrue(pathStyle.isGet)
        assertEquals("/btdocker/container/get_list", pathStyle.path)

        assertEquals(BtDanger.Destroy, catalog.endpoints.first { it.name == "DeleteSite" }.danger)
        assertEquals(BtDanger.Write, catalog.endpoints.first { it.name == "StopSite" }.danger)
    }

    @Test
    fun `only the first missing required param is reported`() {
        val destroy = BtCatalog.parse(fixture).endpoints.first { it.name == "DeleteSite" }
        assertEquals("id", destroy.missingParam(emptyMap())?.n)
        assertNull(destroy.missingParam(mapOf("id" to "3")))
        // 空白串等于没填：面板收得到空表单体，但只会回一句参数错误。
        assertEquals("id", destroy.missingParam(mapOf("id" to "  "))?.n)
    }

    @Test
    fun `filter matches title module path and param names`() {
        val catalog = BtCatalog.parse(fixture)
        assertEquals(listOf("GetDatabase"), catalog.filter(keyword = "数据库").map { it.name })
        assertEquals(listOf("GetSiteNews"), catalog.filter(keyword = "site_id").map { it.name })
        assertEquals(listOf("get_list"), catalog.filter(keyword = "btdocker").map { it.name })
        assertEquals(5, catalog.filter().size)
        assertEquals(5, catalog.filter(keyword = "   ").size)
        // 模块和关键词是叠加的：选了 Docker 再搜「站点」就该什么都不剩。
        assertEquals(emptyList<String>(), catalog.filter("Docker", "站点").map { it.name })
        assertEquals(listOf("GetSiteNews", "StopSite", "DeleteSite"), catalog.filter("网站管理").map { it.name })
    }

    @Test
    fun `a broken catalog degrades to empty instead of throwing`() {
        assertTrue(BtCatalog.parse("{ not json").endpoints.isEmpty())
        assertTrue(BtCatalog.parse("").endpoints.isEmpty())
    }

    @Test
    fun `secret keys are masked but ordinary keys survive`() {
        val text = """
            {"data":[{"name":"ttypecho","password":"hunter2","keyword":"blog","db_password":"p","accept_key":"k"}],
             "private_key":"-----BEGIN","page":1}
        """.trimIndent()
        val redacted = redactBtSecrets(text)
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("-----BEGIN"))
        assertFalse(redacted.contains("\"p\""))
        // keyword 里含 "key"，按子串匹配会被一起涂掉——那种过滤法最后什么都读不出来。
        assertTrue(redacted.contains("blog"))
        assertTrue(redacted.contains("ttypecho"))
        assertTrue(redacted.contains("\"page\""))
    }

    @Test
    fun `array of objects becomes a table and keeps the whole payload as text`() {
        val view = btResultView(element("""{"status":true,"data":[{"id":1,"name":"a"},{"id":2,"name":"b"}]}"""))
        assertEquals(listOf("id", "name"), view.columns)
        assertEquals(listOf(listOf("1", "a"), listOf("2", "b")), view.rows)
        assertEquals(2, view.rows.size)
        // 表只是省得人数，整包始终在：没摆进前五列的字段不能凭空消失。
        assertTrue(view.text.contains("\"status\""))
    }

    @Test
    fun `single object row still tables but nested values fall back to text`() {
        val single = btResultView(element("""[{"id":7,"note":"only one"}]"""))
        assertTrue(single.isTable)
        assertEquals(listOf("7", "only one"), single.rows.single())

        val nested = btResultView(element("""{"a":{"b":1},"c":2}"""))
        assertFalse(nested.isTable)
        assertTrue(nested.text.contains("\"b\""))
    }

    @Test
    fun `flat object reads as a field and value column`() {
        val view = btResultView(element("""{"status":true,"data":{"cpuStatus":"0.5","memUsed":"1.2"}}"""))
        assertEquals(listOf("字段", "值"), view.columns)
        assertEquals(
            listOf("cpuStatus" to "0.5", "memUsed" to "1.2"),
            view.rows.map { it[0] to it[1] }
        )
    }

    @Test
    fun `table cells are redacted too`() {
        val view = btResultView(element("""[{"name":"db1","password":"hunter2"},{"name":"db2","password":"x"}]"""))
        assertEquals(listOf("name", "password"), view.columns)
        assertEquals("***", view.rows.first()[1])
        assertFalse(view.text.contains("hunter2"))
    }

    @Test
    fun `overlong payloads are capped and say so`() {
        val huge = element("""{"data":[{"blob":"${"x".repeat(60_000)}"}]}""")
        val view = btResultView(huge)
        assertTrue(view.truncated)
        assertEquals(40_000, view.text.length)
        // 截断只砍文本：表格那一份还是照着回包摆的，不该跟着一起丢。
        assertTrue(view.isTable)
    }

    @Test
    fun `the shipped catalog file parses and covers the documented modules`() {
        val file = generateSequence(File(".")) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/assets/bt_catalog.json"), File(it, "app/src/main/assets/bt_catalog.json")) }
            .firstOrNull(File::exists)
        org.junit.Assume.assumeTrue("找不到随包的 assets/bt_catalog.json", file != null)
        val catalog = BtCatalog.parse(file!!.readText())
        assertTrue("目录只剩 ${catalog.endpoints.size} 条", catalog.endpoints.size >= 300)
        assertTrue("模块只剩 ${catalog.modules.size} 个", catalog.modules.size >= 18)
        assertTrue(catalog.endpoints.all { it.path.startsWith("/") })
        assertTrue(catalog.endpoints.all { it.name.isNotBlank() })
        assertTrue(catalog.endpoints.count { it.danger == BtDanger.Read } >= 100)
        assertTrue(catalog.filter(keyword = "GetSystemTotal").isNotEmpty())
        assertTrue(catalog.filter(keyword = "getData").isNotEmpty())
    }

    private fun element(text: String) = Json.parseToJsonElement(text)
}
