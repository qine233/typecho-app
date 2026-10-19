package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板各版本给的数据形状不一样，这一层的全部责任就是：
 * 认得出的转成数，认不出的原样带出来，两者都不成就说「没给」——绝不能把没给写成 0。
 *
 * 带 `real panel` 字样的用例照 13.1.0 正式环境的实测原文写（域名、账号、密码换成假的，键名和数值形状不动）。
 */
class BtPanelModelsTest {

    private fun element(text: String) = Json.parseToJsonElement(text)

    // ── 抠数 ──

    @Test
    fun `percent reads both string and number`() {
        assertEquals(25.45, parseBtPercent("25.45%")!!, 0.001)
        assertEquals(3.0, parseBtPercent("3")!!, 0.001)
        assertEquals(12.0, parseBtPercent("已使用 12 %")!!, 0.001)
        assertNull(parseBtPercent("101"))
        assertNull(parseBtPercent("正常"))
        assertNull(parseBtPercent(""))
    }

    @Test
    fun `size needs a unit before it becomes gigabytes`() {
        assertEquals(1.01, parseBtSizeGb("1.01GB")!!, 0.001)
        assertEquals(36.0, parseBtSizeGb("36G")!!, 0.001)
        assertEquals(0.5, parseBtSizeGb("512MB")!!, 0.001)
        assertEquals(2048.0, parseBtSizeGb("2TB")!!, 0.001)
        // 光秃秃的数字可能是端口、条数、也可能是 GB，不敢猜。
        assertNull(parseBtSizeGb("8888"))
        assertNull(parseBtSizeGb(""))
    }

    @Test
    fun `megabytes only apply where the panel means megabytes`() {
        // 实测内存字段是 MB 裸数字：memTotal 3655 就是 3.56 GB。这个函数只被内存键调用。
        assertEquals(3.56, parseBtMegabytes("3655")!!, 0.01)
        assertEquals(0.0, parseBtMegabytes("0")!!, 0.001)
        assertNull(parseBtMegabytes("41天"))
        assertNull(parseBtMegabytes("3.6GB"))
    }

    @Test
    fun `bytes become the largest readable unit`() {
        assertEquals("26.6 GB", formatBtBytes(28615045993.0))
        assertEquals("59.9 GB", formatBtBytes(64355282944.0))
        assertEquals("3.2 MB", formatBtBytes(3309568.0))
        assertEquals("900 B", formatBtBytes(900.0))
        assertEquals("", formatBtBytes(null))
    }

    @Test
    fun `uptime is either a second count or the panel's own sentence`() {
        assertEquals("1 天 1 小时 1 分钟", formatBtUptime(JsonPrimitive("90061")))
        assertEquals("3 小时 25 分钟", formatBtUptime(JsonPrimitive("12345")))
        assertEquals("42 秒", formatBtUptime(JsonPrimitive("42")))
        // 实测 13.1.0 直接给「41天」这种现成文案，原样带出来才对。
        assertEquals("41天", formatBtUptime(JsonPrimitive("41天")))
        assertEquals("", formatBtUptime(null))
    }

    // ── 看板 ──

    @Test
    fun `system snapshot reads nested memory and load objects`() {
        val snapshot = btSystemSnapshot(
            element(
                """{"cpuStatus":"37.5",
                    |"memInfo":{"realUsed":"2.5GB","total":"3.84GB","used":"65.1%"},
                    |"load":{"one":"0.12","five":"0.08","fifteen":"0.03","status":"正常"},
                    |"online":true,
                    |"sTime":"12345",
                    |"warnings":{"webserver":{"status":"stopped","msg":"nginx 未运行"}}}""".trimMargin()
            )
        )
        assertEquals(37.5, snapshot.cpuPercent!!, 0.001)
        assertEquals(2.5, snapshot.memUsedGb!!, 0.001)
        assertEquals(3.84, snapshot.memTotalGb!!, 0.001)
        assertEquals("2.5 / 3.8 GB", snapshot.memText)
        assertEquals(listOf("0.12", "0.08", "0.03"), listOf(snapshot.loadOne, snapshot.loadFive, snapshot.loadFifteen))
        assertEquals("正常", snapshot.loadLabel)
        assertEquals("3 小时 25 分钟", snapshot.uptimeText)
        assertEquals(listOf("webserver · stopped · nginx 未运行"), snapshot.warnings)
        // 没专门认的键不能凭空消失，原样列出来才查得出版本差异。
        assertEquals(listOf("online" to "是"), snapshot.extras.map { it.label to it.value })
    }

    @Test
    fun `real panel 13 1 0 dashboard reads flat keys and merges load from GetNetWork`() {
        // 实测最大的坑：GetSystemTotal 全是扁字段且不给负载，负载只在 GetNetWork 的 load 里。
        // 只读第一个接口的版本会整块看板空着，却一个字都不报错。
        val total = element(
            """{"memTotal": 3655, "memFree": 375, "memBuffers": 0, "memCached": 1250,
                |"memAvailable": 1324, "memShared": 20, "memRealUsed": 2030,
                |"memNewTotalList": ["3.6", "GB"], "memNewRealUsedList": ["2.0", "GB"],
                |"memNewRealUsed": "2", "memNewTotal": "3.6GB", "cpuNum": 4, "cpuRealUsed": 0.5,
                |"time": "41天", "system": "Rocky  9.6 (Blue Onyx) x86_64(Py3.7.16)",
                |"isuser": 0, "isport": false, "version": "13.1.0"}""".trimMargin()
        )
        val network = element(
            """{"network": {"eth0": {"upTotal": 21686038789, "up": 48.2, "down": 14.1}},
                |"upTotal": 28615045993, "downTotal": 31728497835, "up": 32.73, "down": 0.98,
                |"upPackets": 43824160, "downPackets": 37094136,
                |"cpu": [0.7, 4, [0.0], "AMD EPYC 7K62 48-Core Processor * 1", 4, 1],
                |"cpu_times": {"user": 0.0, "总进程数": 158, "活动进程数": 1},
                |"load": {"one": 0.03, "five": 0.05, "fifteen": 0.01, "max": 8, "limit": 8, "safe": 6.0},
                |"mem": {"memTotal": 3655, "memRealUsed": 2005, "memNewTotal": "3.6GB"},
                |"disk": [{"path": "/", "size": ["59.9 GB", "46.2 GB", "13.7 GB", "77.13%"]}],
                |"version": "13.1.0", "title": "宝塔Linux面板", "time": "41天",
                |"site_total": 9, "ftp_total": 1, "database_total": 4,
                |"system": "Rocky  9.6 x86_64", "simple_system": "Rocky 9",
                |"installed": false, "docker_run": false, "iostat": {"ALL": {"read_count": 0}}}""".trimMargin()
        )
        val snapshot = btSystemSnapshot(total, network)

        assertEquals(0.5, snapshot.cpuPercent!!, 0.001)
        assertEquals("4", snapshot.cpuCores)
        assertEquals(1.98, snapshot.memUsedGb!!, 0.02)
        assertEquals(3.56, snapshot.memTotalGb!!, 0.01)
        assertEquals("2 / 3.6 GB", snapshot.memText)
        assertEquals(listOf("0.03", "0.05", "0.01"), listOf(snapshot.loadOne, snapshot.loadFive, snapshot.loadFifteen))
        assertEquals("上限 8", snapshot.loadLabel)
        assertEquals("41天", snapshot.uptimeText)
        assertEquals("Rocky 9 · 面板 13.1.0 · 4 核", snapshot.overview)
        assertTrue(snapshot.warnings.isEmpty())
        // 两个回包的每个键都该被专门处理过，「面板还给了这些」不该再塞满内部标志。
        assertTrue(snapshot.extras.joinToString(), snapshot.extras.isEmpty())
    }

    @Test
    fun `real panel counts come free with GetNetWork`() {
        val counts = btCounts(
            element("""{"cpuNum": 4}"""),
            element("""{"site_total": 9, "database_total": 4, "ftp_total": 1}""")
        )
        assertEquals(listOf("站点" to "9", "数据库" to "4", "FTP" to "1"), counts.map { it.label to it.value })
        // 面板没给就是没有，不能算成 0 个。
        assertTrue(btCounts(element("""{"memTotal": 3655}"""), null).isEmpty())
    }

    @Test
    fun `absent metrics stay absent`() {
        val snapshot = btSystemSnapshot(element("""{"cpuStatus":"","memInfo":{},"load":{}}"""))
        assertNull(snapshot.cpuPercent)
        assertNull(snapshot.memUsedGb)
        assertEquals("", snapshot.memText)
        assertEquals("", snapshot.uptimeText)
        assertEquals("", snapshot.overview)
        assertTrue(snapshot.warnings.isEmpty())
        assertTrue(snapshot.extras.isEmpty())
    }

    @Test
    fun `garbage root degrades to an empty snapshot instead of throwing`() {
        val snapshot = btSystemSnapshot(element("""[1,2,3]"""))
        assertNull(snapshot.cpuPercent)
        assertTrue(snapshot.extras.isEmpty())
        // 第二个来源缺失/是垃圾都不能影响第一个。
        assertEquals(snapshot, btSystemSnapshot(element("""[1,2,3]"""), element("""{"nope":1}""")))
    }

    @Test
    fun `disks come bare or wrapped`() {
        val one = """{"disk":"/","size":"40GB","used":"12%","free":"35GB","percentage":12.5}"""
        val fromArray = btDisks(element("[$one]"))
        val fromEnvelope = btDisks(element("""{"status":true,"msg":"","data":[$one]}"""))
        assertEquals(fromArray, fromEnvelope)
        assertEquals("/", fromArray.single().name)
        assertEquals("40GB", fromArray.single().totalText)
        assertEquals(12.5, fromArray.single().usedPercent!!, 0.001)
        // 没有盘名的条目画出来只是一条没头没尾的进度条。
        assertTrue(btDisks(element("""[{"size":"40GB"}]""")).isEmpty())
    }

    @Test
    fun `real panel disk info is a positional array`() {
        // 实测：容量、已用、可用、使用率全挤在 size 数组里按位置给，byte_size 是精确字节数。
        val disks = btDisks(
            element(
                """[{"byte_size": [64355282944, 49638461440, 14716821504], "path": "/",
                    |"size": ["59.9 GB", "46.2 GB", "13.7 GB", "77.13%", "0.00 b", "46.2"],
                    |"filesystem": "/dev/vda2", "type": "xfs",
                    |"inodes": [29545360, 796954, 28748406, "2.70 %"], "d_size": "None"}]""".trimMargin()
            )
        )
        val disk = disks.single()
        assertEquals("/", disk.name)
        assertEquals("59.9 GB", disk.totalText)
        assertEquals("46.2 GB", disk.usedText)
        assertEquals("13.7 GB", disk.freeText)
        assertEquals(77.13, disk.usedPercent!!, 0.001)
        assertEquals("/dev/vda2 xfs", disk.detail)
    }

    @Test
    fun `disk percent falls back to byte ratio when the panel gives no percent text`() {
        val disk = btDisks(element("""[{"path":"/","byte_size":[1000,250,750]}]""")).single()
        assertEquals(25.0, disk.usedPercent!!, 0.001)
        assertEquals("1000 B", disk.totalText)
    }

    @Test
    fun `network keeps unknown counters as extra fields`() {
        val network = btNetwork(element("""{"up":"12.5","down":"1.2","recv_total":"3GB"}"""))
        assertEquals("12.5 KB/s", network.upText)
        assertEquals(listOf("累计接收" to "3GB"), network.extras.map { it.label to it.value })
    }

    @Test
    fun `real panel network reports speed and cumulative bytes`() {
        val network = btNetwork(
            element("""{"up": 32.73, "down": 0.98, "upTotal": 28615045993, "downTotal": 31728497835,
                |"network": {"eth0": {"up": 48.2}}, "iostat": {"ALL": {"read_count": 0}}}""".trimMargin())
        )
        assertEquals("32.7 KB/s", network.upText)
        assertEquals("1.0 KB/s", network.downText)
        assertEquals("26.6 GB", network.upTotalText)
        assertEquals("29.5 GB", network.downTotalText)
        // 每个网卡的累计字节是几十行噪音，看板上没人看它。
        assertTrue(network.extras.isEmpty())
    }

    // ── 表 ──

    @Test
    fun `site rows fall back through several key names`() {
        val rows = btRows(
            BtTable.Sites,
            element("""{"data":{"rows":[{"id":1,"domain":"www.a.com",
                |"status":"运行中","addtime":"2026-01-02","ssl":"on"}]}}""".trimMargin())
        )
        val row = rows.single()
        assertEquals("1", row.id)
        // 这一条没给 name，标题只能顶上；但域名数那种裸数字不该被当成站点名。
        assertEquals("未命名", row.title)
        assertEquals("2026-01-02", row.subtitle)
        assertEquals("运行中", row.badge)
        assertEquals(listOf("证书" to "on", "域名" to "www.a.com"), row.fields.map { it.label to it.value })
    }

    @Test
    fun `real panel site row shows type and certificate window`() {
        val row = btRows(
            BtTable.Sites,
            element(
                """{"status": true, "msg": "", "page": "<span class='Pcount'>共9条</span>",
                    |"data": [{"id": 20, "name": "shop.example.com",
                    |"path": "/www/wwwroot/shop.example.com", "status": "1", "index": null,
                    |"ps": "shop", "addtime": "2026-01-20 22:58:31", "type_id": 0,
                    |"edate": "0000-00-00", "project_type": "PHP", "project_config": "{}",
                    |"rname": "shop.example.com", "stop": "", "backup_count": 6,
                    |"quota": {"used": 0, "size": 0}, "domain": 2,
                    |"ssl": {"issuer_O": "Let's Encrypt", "notAfter": "2026-12-07",
                    |"notBefore": "2026-09-08", "subject": "www.example.com", "endtime": 47},
                    |"php_version": "8.4", "net": {}, "waf": {}, "proxy": false, "redirect": false}]}""".trimMargin()
            )
        ).single()
        assertEquals("shop.example.com", row.title)
        assertEquals("/www/wwwroot/shop.example.com", row.subtitle)
        // 实测 status 只有 "1"，给人看毫无意义；类型 + PHP 版本才是这一行该有的角标。
        assertEquals("PHP 8.4", row.badge)
        assertEquals(
            listOf("证书" to "剩 47 天 至 2026-12-07", "证书域名" to "www.example.com", "备注" to "shop"),
            row.fields.take(3).map { it.label to it.value }
        )
    }

    @Test
    fun `static site has no php version and no certificate`() {
        val row = btRows(
            BtTable.Sites,
            element("""[{"id":19,"name":"dns.example.com","project_type":"html","php_version":"静态","ssl":-1}]""")
        ).single()
        assertEquals("静态", row.badge)
        assertEquals(emptyList<String>(), row.fields.map { it.label })
    }

    @Test
    fun `expired certificate says so instead of a negative day count`() {
        val row = btRows(
            BtTable.Sites,
            element("""[{"id":4,"name":"api.example.com","ssl":{"notAfter":"2025-08-01","endtime":-433}}]""")
        ).single()
        assertEquals("证书 已过期待续签 至 2025-08-01", row.fields.first().let { "${it.label} ${it.value}" })
    }

    @Test
    fun `real panel database row never puts the panel's plaintext password on screen`() {
        // 实测：table=databases 的回包直接带 password 明文。手机屏幕会被人看见，这条必须挡住。
        val row = btRows(
            BtTable.Databases,
            element(
                """[{"id": 8, "pid": 0, "name": "blogdb", "username": "bloguser",
                    |"password": "leak-check-please", "accept": "127.0.0.1", "ps": "blog",
                    |"addtime": "2025-05-16 14:11:16", "db_type": 0, "conn_config": {}, "sid": 0,
                    |"type": "MySQL", "type_id": 0, "backup_count": 3,
                    |"quota": {"used": 3309568, "size": 0}}]""".trimMargin()
            )
        ).single()
        assertEquals("blogdb", row.title)
        assertEquals("bloguser", row.subtitle)
        assertEquals("MySQL", row.badge)
        assertFalse(row.fields.joinToString { it.value + it.label }.contains("leak-check-please"))
        assertEquals(listOf("备注" to "blog", "时间" to "2025-05-16 14:11:16", "备份份数" to "3"),
            row.fields.map { it.label to it.value })
    }

    @Test
    fun `crontab rows read the alternate key names older panels use`() {
        val rows = btRows(
            BtTable.Crontab,
            element("""[{"id":7,"sName":"清理日志","job":"toShell","type":"minuteTask","where1":"/bin/sh"}]""")
        )
        val row = rows.single()
        assertEquals("清理日志", row.title)
        assertEquals("/bin/sh", row.subtitle)
        assertEquals("minuteTask", row.badge)
        assertEquals(listOf("任务类型" to "toShell"), row.fields.map { it.label to it.value })
    }

    @Test
    fun `real panel crontab row shows the schedule in words and the enabled state`() {
        val rows = btRows(
            BtTable.Crontab,
            element(
                """[{"id": 8, "name": "备份目录[/root/backups]", "type": "day", "where1": "1",
                    |"where_hour": 1, "where_minute": 30, "echo": "eb38c35c", "addtime": "2026-10-19 01:30:23",
                    |"status": 1, "save": 3, "backupTo": "onedrive", "sName": "/root/backups",
                    |"sBody": "#!/bin/bash\\nrm -rf /tmp/x", "sType": "path", "urladdress": "",
                    |"save_local": 1, "notice": 0, "split_type": "0", "split_value": 5, "type_id": 0,
                    |"rname": "备份目录[/root/backups]", "flock": 1, "backup_mode": 0, "result": 0,
                    |"params": "{}", "type_zh": "每天", "cycle": "每天的01:30执行一次", "sort": 0,
                    |"user": "root", "type_name": "默认分类"},
                    |{"id": 5, "name": "[TEST]生成页面", "type": "minute-n", "status": 0,
                    |"type_zh": "每25分钟", "cycle": "每小时的第0分钟开始，每隔25分钟执行一次"}]""".trimMargin()
            )
        )
        assertEquals("备份目录[/root/backups]", rows.first().title)
        assertEquals("每天的01:30执行一次", rows.first().subtitle)
        assertEquals("启用中 · 每天", rows.first().badge)
        // 几十行的脚本不该排在列表那一格里，要看得去任务详情。
        val labels = rows.first().fields.map { it.label }
        assertEquals(listOf("时间", "保留份数", "备份到", "执行用户", "任务分类"), labels)
        assertFalse(rows.first().fields.joinToString { it.value }.contains("rm -rf"))
        // 实测停用那条给的是 status=0。
        assertEquals("已停用 · 每25分钟", rows[1].badge)
    }

    @Test
    fun `log rows lead with what happened`() {
        val rows = btRows(
            BtTable.Logs,
            element("""[{"addtime":"2026-10-19 07:11","username":"admin","ip":"8.8.8.8",
                |"domain":"登录成功","agent":"Chrome"}]""".trimMargin())
        )
        val row = rows.single()
        assertEquals("登录成功", row.title)
        assertEquals("2026-10-19 07:11", row.subtitle)
        assertEquals("admin", row.badge)
    }

    @Test
    fun `real panel log row leads with the log sentence`() {
        val row = btRows(
            BtTable.Logs,
            element("""{"data":[{"id":11970,"uid":1,"username":"zcadmin","type":"API配置",
                |"log":"1.2.3.4(电信 成都) 变更IP限制为[*]","addtime":"2026-10-20 01:02:26"}]}""".trimMargin())
        ).single()
        assertEquals("1.2.3.4(电信 成都) 变更IP限制为[*]", row.title)
        assertEquals("2026-10-20 01:02:26", row.subtitle)
        assertEquals("API配置", row.badge)
        // 操作者进不了字段是因为它已经被当成角标的备胎，重复列一次只会挤掉别的东西。
        assertTrue(row.fields.isEmpty())
    }

    @Test
    fun `a long value is capped instead of filling the screen`() {
        val row = btRows(
            BtTable.Logs,
            element("""[{"log":"${"很长的日志".repeat(40)}","addtime":"2026-10-20"}]""")
        ).single()
        assertTrue(row.title.length <= 91)
        assertTrue(row.title.endsWith("…"))
    }

    @Test
    fun `a row with no recognisable name still shows up`() {
        val rows = btRows(BtTable.Databases, element("""[{"id":3,"charset":"utf8"}]"""))
        assertEquals("未命名", rows.single().title)
    }

    // ── 壳层与计数 ──

    @Test
    fun `total is only null when the panel really did not report it`() {
        assertEquals(7, btTotal(element("""{"records":7,"rows":[]}""")))
        // 0 条是「面板说这张表是空的」，和「没读到」是两件不同的事。
        assertEquals(0, btTotal(element("""{"data":{"records":0}}""")))
        assertNull(btTotal(element("""{"data":[]}""")))
    }

    @Test
    fun `real panel total count hides inside the paging html`() {
        assertEquals(
            11970,
            btTotal(
                element("""{"auto_list":false,"where":"",
                    |"page":"<span class='Pcurrent'>1</span><span class='Pcount'>共11970条</span>",
                    |"data":[{"id":1}]}""".trimMargin())
            )
        )
    }

    @Test
    fun `items search two levels of shells`() {
        assertEquals(1, btItems(element("""{"data":{"rows":[{}]}}""")).size)
        assertEquals(1, btItems(element("""{"list":[{}]}""")).size)
        assertEquals(1, btItems(element("""[{}]""")).size)
        assertTrue(btItems(element("""{"status":true,"msg":"没有数据"}""")).isEmpty())
    }

    @Test
    fun `credential shaped keys are recognised whatever version spells them`() {
        assertTrue(isBtSecretKey("password"))
        assertTrue(isBtSecretKey("Password"))
        assertTrue(isBtSecretKey("db_password"))
        assertTrue(isBtSecretKey("access_key"))
        assertTrue(isBtSecretKey("request_token"))
        assertFalse(isBtSecretKey("passwd_note"))
        assertFalse(isBtSecretKey("name"))
    }
}
