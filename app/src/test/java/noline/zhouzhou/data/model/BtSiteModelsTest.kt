package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网站管理这一层的解析单测。原文一律照正式面板 13.1.0 的实测回包抄，
 * 键名一个都不改——这一层的价值就在于「面板实际给成什么样，代码就按什么样读」。
 */
class BtSiteModelsTest {

    private fun element(text: String) = Json.parseToJsonElement(text)

    private val siteRow = """
        {"id":"7","name":"aa-test.example.com","path":"/www/wwwroot/aa-test.example.com",
         "ps":"一次性测试站","addtime":"2026-10-20 09:00:00","edate":"0000-00-00",
         "project_type":"PHP","php_version":"84","status":"1","stop":"",
         "domain":1,"backup_count":2,"proxy":false,"redirect":false,"ssl":-1}
    """.trimIndent()

    @Test
    fun `site row reads the list columns the panel actually sends`() {
        val site = btSites(element("""[$siteRow]""")).single()
        assertEquals("7", site.id)
        assertEquals("aa-test.example.com", site.name)
        assertEquals("/www/wwwroot/aa-test.example.com", site.path)
        assertEquals("一次性测试站", site.remark)
        assertTrue(site.running)
        // `domain` 是个数，不是域名列表；当成列表读会一个都读不出来。
        assertEquals(1, site.domainCount)
        assertEquals(2, site.backupCount)
        assertFalse(site.hasProxy)
        assertFalse(site.hasRedirect)
        assertEquals("1 个域名 · /www/wwwroot/aa-test.example.com", site.subtitle)
        assertEquals("PHP 84", site.badge)
    }

    @Test
    fun `ssl minus one means no certificate rather than a broken one`() {
        val site = btSites(element("""[$siteRow]""")).single()
        assertFalse(site.ssl.deployed)
        assertEquals("未部署证书", site.ssl.summary)
    }

    @Test
    fun `ssl object carries days left and expiry date`() {
        val site = btSites(element(
            """[{"id":"1","name":"x.work","ssl":{"issuer_O":"Let's Encrypt","notAfter":"2026-12-01","endtime":15,"dns":["x.work","www.x.work"]}}]"""
        )).single()
        assertTrue(site.ssl.deployed)
        assertEquals("剩 15 天 · 至 2026-12-01", site.ssl.summary)
        assertEquals(listOf("x.work", "www.x.work"), site.ssl.domains)
        // 15 天以内算「快到期」，列表上要染红。
        assertTrue(site.ssl.isExpiring)
    }

    @Test
    fun `expired certificate says so instead of a negative day count`() {
        val site = btSites(element("""[{"id":"1","name":"x.work","ssl":{"endtime":-3,"notAfter":"2026-09-01"}}]""")).single()
        assertEquals("已过期，待续签", site.ssl.summary)
    }

    @Test
    fun `stopped site shows the reason and loses its php badge`() {
        val site = btSites(element(
            """[{"id":"1","name":"x.work","status":"0","stop":"证书续期失败","project_type":"PHP","php_version":"84"}]"""
        )).single()
        assertFalse(site.running)
        assertEquals("已停用", site.badge)
        assertEquals("证书续期失败", site.stopReason)
    }

    @Test
    fun `static site is labelled 纯静态 not PHP`() {
        val site = btSites(element("""[{"id":"1","name":"x.work","project_type":"html","php_version":"静态"}]""")).single()
        assertEquals("纯静态", site.typeLabel)
        // 「静态 PHP」这种拼法面板上不存在，徽标里也不能出现。
        assertEquals("纯静态", site.badge)
    }

    @Test
    fun `unknown project type passes through untouched`() {
        val site = btSites(element("""[{"id":"1","name":"x.work","project_type":"node"}]""")).single()
        assertEquals("node", site.typeLabel)
    }

    @Test
    fun `rows without id and name are dropped`() {
        assertEquals(1, btSites(element("""[$siteRow,{"foo":"bar"}]""")).size)
    }

    @Test
    fun `list is read from the data shell as well as a bare array`() {
        val wrapped = btSites(element("""{"status":true,"msg":"","data":[$siteRow]}"""))
        assertEquals(1, wrapped.size)
        assertEquals("aa-test.example.com", wrapped.single().name)
    }

    @Test
    fun `zero expire date is 永久 not junk`() {
        assertEquals("永久", btExpireText("0000-00-00"))
        assertEquals("永久", btExpireText(""))
        assertEquals("2027-01-01", btExpireText("2027-01-01"))
    }

    @Test
    fun `domains come out of the domains shell and the email key is ignored`() {
        val domains = btSiteDomains(element(
            """{"domains":[{"name":"x.work","root_domain_id":"x.work","binding":false,"dns_status":1,"apply_ssl":0},
                          {"name":"www.x.work","binding":true,"dns_status":0,"apply_ssl":1}],"email":"a@b.c"}"""
        ))
        assertEquals(2, domains.size)
        assertEquals("x.work", domains[0].rootDomain)
        assertFalse(domains[0].isSubDirectoryBinding)
        assertEquals(true, domains[0].dnsOk)
        assertFalse(domains[0].sslApplied)
        assertTrue(domains[1].isSubDirectoryBinding)
        assertEquals(false, domains[1].dnsOk)
        assertTrue(domains[1].sslApplied)
        // 有的版本把这两个位直接给成布尔，读成「和 0.0 比大小」就永远是反的。
        val flags = btSiteDomains(element("""{"domains":[{"name":"x.work","dns_status":true,"apply_ssl":true}]}""")).single()
        assertEquals(true, flags.dnsOk)
        assertTrue(flags.sslApplied)
    }

    @Test
    fun `run path defaults to root and dirs become the options`() {
        val runPath = btRunPath(element("""{"runPath":"/api","dirs":["/","/api","/admin"]}"""))
        assertEquals("/api", runPath.runPath)
        assertEquals(listOf("/", "/api", "/admin"), runPath.dirs)
        assertEquals("/", btRunPath(element("""{}""")).runPath)
    }

    @Test
    fun `dir binding folds its own list into the options`() {
        val binding = btRunPath(element("""{"run_path":"/","dirs":["/"],"binding":[{"dir":"/mobile","domain":"m.x.work"}]}"""))
        assertEquals("/", binding.runPath)
        assertTrue(binding.dirs.contains("/mobile"))
    }

    @Test
    fun `index list arrives as one bare comma string`() {
        val index = btSiteIndex(element(""""index.php,index.html,index.htm""""))
        assertEquals(listOf("index.php", "index.html", "index.htm"), index.files)
        assertEquals(listOf("index.php"), btSiteIndex(element("""{"index":"index.php"}""")).files)
        assertEquals(listOf("index.wsgi"), btSiteIndex(element("""["index.wsgi"]""")).files)
        assertTrue(btSiteIndex(element("""{}""")).files.isEmpty())
    }

    @Test
    fun `rewrite template list drops the 当前 placeholder and dedupes the two copies`() {
        val rewrite = btRewrite(element(
            """{"rewrite":["0.当前","layui","thinkphp","laravel"],"default_list":["0.当前","layui","thinkphp","laravel"]}"""
        ))
        assertEquals(listOf("layui", "thinkphp", "laravel"), rewrite.templates)
    }

    @Test
    fun `proxy rules read the lowercase field names the panel sends`() {
        val rule = btProxyRules(element(
            """[{"proxyname":"api","proxydir":"/api","proxysite":"http://127.0.0.1:8080","todomain":"x.work","type":"1","cache":"0","pass":"secret"}]"""
        )).single()
        assertEquals("api", rule.name)
        assertEquals("/api", rule.directory)
        assertEquals("http://127.0.0.1:8080", rule.target)
        assertEquals("x.work", rule.domain)
    }

    @Test
    fun `redirect rules take the domain array and the numeric code`() {
        val rule = btRedirectRules(element(
            """[{"redirectname":"old","tourl":"https://new.work/","redirectdomain":["x.work","m.x.work"],
                 "redirectpath":"/old","redirecttype":"path","type":"1","password":"secret"}]"""
        )).single()
        assertEquals("old", rule.name)
        assertEquals("https://new.work/", rule.target)
        assertEquals(listOf("x.work", "m.x.work"), rule.domains)
        assertEquals("/old", rule.path)
        assertEquals("path", rule.kind)
        assertEquals("1", rule.code)
    }

    @Test
    fun `limit net with three zeros is off, any positive number is on`() {
        val off = btLimitNet(element("""{"perserver":0,"perip":0,"limit_rate":0,"value":false}"""))
        assertFalse(off.enabled)
        val on = btLimitNet(element("""{"perserver":50,"perip":10,"limit_rate":512,"value":true}"""))
        assertTrue(on.enabled)
        assertEquals(50, on.perServer)
        assertEquals(512, on.rateKb)
        assertFalse(btLimitNet(element("""{}""")).enabled)
        // 面板这里给的是数字，也可能给布尔；布尔那位曾经因为 ?: 的优先级被读成「一直开着」。
        assertTrue(btLimitNet(element("""{"perserver":0,"perip":0,"limit_rate":0,"value":true}""")).enabled)
        assertFalse(btLimitNet(element("""{"perserver":"0","perip":"0","limit_rate":"0","value":"0"}""")).enabled)
    }

    @Test
    fun `password protection replies with a bare boolean and no username`() {
        val on = btPwdProtection(element("true"))
        assertTrue(on.enabled)
        // 用户名读不回来，所以界面上改密码必须账号密码一起重填。
        assertEquals("", on.username)
        assertFalse(btPwdProtection(element("false")).enabled)
        val legacy = btPwdProtection(element("""{"username":"admin","pwd":true}"""))
        assertEquals("admin", legacy.username)
        assertTrue(legacy.enabled)
    }

    @Test
    fun `access log switch distinguishes 面板没给 from 关着`() {
        assertEquals(true, btAccessLogEnabled(element("""{"logs":true}""")))
        assertEquals(false, btAccessLogEnabled(element("""{"logs":false}""")))
        assertNull(btAccessLogEnabled(element("""{"other":1}""")))
        assertNull(btAccessLogEnabled(element(""""plain string"""")))
    }

    @Test
    fun `ssl detail never surfaces the private key that sits in the same reply`() {
        val detail = btSslDetail(element(
            """{"status":true,"domain":[{"name":"x.work"},{"name":"www.x.work"}],"auth_type":"letsencrypt",
               "httpTohttps":true,"tls_versions":{"TLSv1.1":false,"TLSv1.2":true,"TLSv1.3":true},
               "endtime":"2026-12-01","key":"-----BEGIN PRIVATE KEY-----abc","cert":"pem text"}"""
        ))
        assertTrue(detail.deployed)
        assertEquals(listOf("x.work", "www.x.work"), detail.domains)
        assertEquals("letsencrypt", detail.authType)
        assertTrue(detail.httpToHttps)
        assertEquals(listOf("TLSv1.2", "TLSv1.3"), detail.tlsVersions)
        assertEquals("2026-12-01", detail.expiresAt)
        // 私钥藏得再深也不行：整份模型 toString 里不能出现那串原文。
        assertFalse(detail.toString().contains("BEGIN PRIVATE KEY"))
        assertFalse(detail.toString().contains("pem text"))
    }

    @Test
    fun `default site flag is a boolean so the answer is 不知道`() {
        // 面板给的是 {sites:[候选], defaultSite:false}，拿候选第一条冒充会说出错话。
        assertEquals("", btDefaultSite(element("""{"sites":[{"name":"a.work"},{"name":"b.work"}],"defaultSite":false}""")))
        assertEquals("a.work", btDefaultSite(element("""{"defaultSite":"a.work"}""")))
        assertEquals("", btDefaultSite(element("""{"defaultSite":true}""")))
        // 面板这里偶尔塞一句提示语，那不是站点名。
        assertEquals("", btDefaultSite(element("""{"defaultSite":"设置成功"}""")))
    }

    @Test
    fun `site php version reads the flat phpversion key`() {
        assertEquals("84", btSitePhpVersion(element("""{"phpversion":"84","tomcat":-1,"nginx":-1}""")))
        assertEquals("", btSitePhpVersion(element("""{"tomcat":-1}""")))
        assertEquals("82", btSitePhpVersion(element(""""82"""")))
    }

    @Test
    fun `log path only accepts values that look like paths`() {
        assertEquals("/www/wwwlogs", btLogPath(element("""{"path":"/www/wwwlogs"}""")))
        assertEquals("/www/wwwlogs", btLogPath(element("\"/www/wwwlogs \"")))
        assertEquals("", btLogPath(element("""{"path":"未找到"}""")))
        assertEquals("", btLogPath(element("""{"data":false}""")))
    }

    @Test
    fun `log text reads msg but does not read a boolean as content`() {
        assertEquals("127.0.0.1 - GET /", btSiteLogText(element("""{"status":true,"msg":"127.0.0.1 - GET /"}""")).trim())
        assertEquals("tail", btSiteLogText(element("""{"logs":"tail"}""")))
        assertEquals("", btSiteLogText(element("""{"status":true,"msg":true}""")))
        assertEquals("whole body", btSiteLogText(element(""""whole body"""")))
    }

    @Test
    fun `site creation reports which halves got built`() {
        val creation = btSiteCreation(element(
            """{"siteStatus":true,"siteId":12,"databaseStatus":false,"ftpStatus":true,"gitStatus":false}"""
        ))
        assertEquals("12", creation.siteId)
        assertEquals("站点编号 12 · 站点 已建 · 数据库 没建 · FTP 已建 · Git 没建", creation.summary)
        assertTrue(btSiteCreation(element("""{"siteStatus":true}""")).siteId.isBlank())
    }

    @Test
    fun `php version list keeps the 纯静态 entry and its install state`() {
        val versions = btPhpVersions(element(
            """[{"version":"00","name":"纯静态","status":true},{"version":"84","name":"PHP-84","status":true},
               {"version":"56","name":"PHP-56","status":false},{"name":""}]"""
        ))
        assertEquals(3, versions.size)
        assertEquals("00", versions[0].version)
        assertTrue(versions[0].installed)
        assertFalse(versions[2].installed)
        assertEquals(2, btSiteTypes(element("""[{"id":"0","name":"默认站点"},{"id":"3","name":"项目"}]""")).size)
    }

    @Test
    fun `ftp binding is read without the password field`() {
        assertEquals("aa_test_ftp", btSiteFtp(element("""{"ftp_name":"aa_test_ftp","ftp_password":"secret"}""")))
        assertEquals("aa_test_ftp", btSiteFtp(element("""[{"name":"aa_test_ftp","password":"secret"}]""")))
        assertEquals("", btSiteFtp(element("""{"password":"secret"}""")))
        assertEquals("", btSiteFtp(element("""[]""")))
    }

    @Test
    fun `security headers treat a missing key as off`() {
        val headers = btSecurityHeaders(element(
            """{"x_xss_protection":true,"referrer_policy":true,"content_security_policy":false}"""
        ))
        assertTrue(headers.xssProtection)
        assertTrue(headers.referrerPolicy)
        assertFalse(headers.contentSecurityPolicy)
        assertFalse(headers.contentTypeOptions)
        assertFalse(headers.permissionsPolicy)
    }

    @Test
    fun `301 status reads src and url and stays off when the panel says false`() {
        val on = bt301Status(element("""{"status":true,"src":"http://x.work","url":"http://new.work"}"""))
        assertTrue(on.enabled)
        assertEquals("http://x.work", on.from)
        assertEquals("http://new.work", on.to)
        assertFalse(bt301Status(element("""{"status":false,"domain":"","url":"","src":""}""")).enabled)
    }
}
