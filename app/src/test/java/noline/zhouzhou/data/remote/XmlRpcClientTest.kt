package noline.zhouzhou.data.remote

import org.junit.Assert.*
import org.junit.Test

class XmlRpcClientTest {

    private val client = XmlRpcClient()

    @Test
    fun `buildRequest - no params`() {
        val xml = client.buildRequest("demo.sayHello", emptyList())
        assertTrue(xml.contains("<methodName>demo.sayHello</methodName>"))
        assertFalse(xml.contains("<params>"))
    }

    @Test
    fun `buildRequest - typed params`() {
        val xml = client.buildRequest(
            "metaWeblog.getRecentPosts",
            listOf("1", "admin", "secret", 10)
        )
        assertTrue(xml.contains("<string>1</string>"))
        assertTrue(xml.contains("<string>admin</string>"))
        assertTrue(xml.contains("<string>secret</string>"))
        assertTrue(xml.contains("<int>10</int>"))
    }

    @Test
    fun `buildRequest - escapes special characters`() {
        val xml = client.buildRequest("demo", listOf("a<b>&c\"d'e"))
        assertTrue(xml.contains("a&lt;b&gt;&amp;c&quot;d&apos;e"))
    }

    @Test
    fun `parseResponse - simple string`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <methodResponse>
              <params><param><value><string>Hello!</string></value></param></params>
            </methodResponse>"""
        val result = client.parseResponse(xml)
        assertEquals("Hello!", result.asString())
    }

    @Test
    fun `parseResponse - int`() {
        val xml = """<?xml version="1.0"?>
            <methodResponse><params><param><value><int>42</int></value></param></params></methodResponse>"""
        assertEquals(42L, client.parseResponse(xml).asLong())
    }

    @Test
    fun `parseResponse - boolean`() {
        val xml = """<?xml version="1.0"?>
            <methodResponse><params><param><value><boolean>1</boolean></value></param></params></methodResponse>"""
        assertTrue(client.parseResponse(xml).asBoolean())
    }

    @Test
    fun `parseResponse - struct (blog info)`() {
        val xml = """<?xml version="1.0"?>
            <methodResponse><params><param><value><array><data>
              <value><struct>
                <member><name>blogid</name><value><string>1</string></value></member>
                <member><name>blogName</name><value><string>My Blog</string></value></member>
                <member><name>url</name><value><string>https://blog.com/</string></value></member>
                <member><name>isAdmin</name><value><boolean>1</boolean></value></member>
              </struct></value>
            </data></array></value></param></params></methodResponse>"""
        val arr = client.parseResponse(xml).asArray()
        assertEquals(1, arr.size)
        val struct = arr[0].asStruct()
        assertEquals("1", struct["blogid"]?.asString())
        assertEquals("My Blog", struct["blogName"]?.asString())
        assertTrue(struct["isAdmin"]?.asBoolean() == true)
    }

    @Test
    fun `parseResponse - array of strings (categories)`() {
        val xml = """<?xml version="1.0"?>
            <methodResponse><params><param><value><array><data>
              <value><string>cat1</string></value>
              <value><string>cat2</string></value>
            </data></array></value></param></params></methodResponse>"""
        val arr = client.parseResponse(xml).asArray()
        assertEquals(2, arr.size)
        assertEquals("cat1", arr[0].asString())
        assertEquals("cat2", arr[1].asString())
    }

    @Test
    fun `parseResponse - fault`() {
        val xml = """<?xml version="1.0"?>
            <methodResponse><fault><value><struct>
              <member><name>faultCode</name><value><int>403</int></value></member>
              <member><name>faultString</name><value><string>Bad login</string></value></member>
            </struct></value></fault></methodResponse>"""
        try {
            client.parseResponse(xml)
            fail("Expected XmlRpcFault")
        } catch (e: XmlRpcFault) {
            assertEquals(403, e.code)
            assertEquals("Bad login", e.message)
        }
    }

    @Test
    fun `parseResponse - nested struct (post)`() {
        val xml = """<?xml version="1.0"?>
            <methodResponse><params><param><value><struct>
              <member><name>title</name><value><string>Hello World</string></value></member>
              <member><name>postid</name><value><string>123</string></value></member>
              <member><name>dateCreated</name><value><dateTime.iso8601>20240101120000</dateTime.iso8601></value></member>
              <member><name>categories</name><value><array><data>
                <value><string>默认分类</string></value>
              </data></array></value></member>
            </struct></value></param></params></methodResponse>"""
        val struct = client.parseResponse(xml).asStruct()
        assertEquals("Hello World", struct["title"]?.asString())
        assertEquals("123", struct["postid"]?.asString())
        assertEquals("20240101120000", struct["dateCreated"]?.asString())
        assertEquals("默认分类", struct["categories"]?.asArray()?.get(0)?.asString())
    }

    @Test
    fun `parseResponse - strips leading PHP deprecation warning`() {
        val xml = """<br />
            <b>Deprecated</b>: Function xml_set_object() is deprecated since 8.4 in /www/var/Typecho/Common.php on line 143

            <?xml version="1.0"?>
            <methodResponse><params><param><value><string>Hello!</string></value></param></params></methodResponse>"""
        assertEquals("Hello!", client.parseResponse(xml).asString())
    }

    @Test
    fun `parseResponse - recovers fields from truncated response`() {
        // Simulates a PHP 8.4 server that emits warnings which shift Content-Length,
        // cutting off the XML tail (no closing tags). Fields before the cut survive.
        val xml = """<?xml version="1.0"?>
            <methodResponse><params><param><value><array><data>
              <value><struct>
                <member><name>isAdmin</name><value><boolean>1</boolean></value></member>
                <member><name>url</name><value><string>https://blog.com/</string></value></member>
                <member><name>blogid</name><value><int>1</int></value></member>
                <member><name>blogName</name><value><string>团子笔记</string></"""
        val struct = client.parseResponse(xml).asArray()[0].asStruct()
        assertEquals("1", struct["blogid"]?.asString())
        assertEquals("团子笔记", struct["blogName"]?.asString())
        assertTrue(struct["isAdmin"]?.asBoolean() == true)
    }

    @Test
    fun `round trip - struct request encodes then structure is intact`() {
        val struct = mapOf(
            "title" to "测试标题",
            "description" to "<p>内容 &amp; 更多</p>",
            "categories" to listOf("默认分类"),
            "post_status" to "publish"
        )
        val xml = client.buildRequest("metaWeblog.newPost", listOf("1", "u", "p", struct, true))
        assertTrue(xml.contains("<name>title</name>"))
        assertTrue(xml.contains("<string>测试标题</string>"))
        assertTrue(xml.contains("<name>post_status</name>"))
        assertTrue(xml.contains("<string>publish</string>"))
        assertTrue(xml.contains("<boolean>1</boolean>"))
    }
}
