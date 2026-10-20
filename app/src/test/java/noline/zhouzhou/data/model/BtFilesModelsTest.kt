package noline.zhouzhou.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件管理器读模型单测。
 *
 * 每份 JSON 都是照正式面板 13.1.0 的 `files` 模块实测回包抄的形状
 * （在 `/tmp/aa-test-files` 里建→复制→改名→软链→删→放回→彻底删，每一步都读回来对过）。
 * 这里单独钉住的是面板不会自己交代的六条：
 * 1. 路径不存在时面板**不报错**，它把 `path` 换成 `/www/wwwroot` 照常列条目，所以只能拿回声比；
 * 2. 目录和文件分在 `dir`/`files` 两个键里，`is_dir` 那一格实测根本不出现；
 * 3. 老的 `GetDir` 是「大写键 + 每行一条分号拼接串」，位置只能信前五个；
 * 4. 回收站的数据在顶层 `list`，文本类计数的键拼错了写成 `ont_text`，`status` 是布尔；
 * 5. `GetFileHistory` 的 `status` 是字符串 `"true"`，套不上信封，整包得当数据读；
 * 6. `file_history_list` 的 `filename` 实测回字面量 `undefined`，名字只能从 `filepath` 取。
 */
class BtFilesModelsTest {

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    // 实测 /tmp/aa-test-files：一个子目录 + 三个文件（其中 sub 在 dir 键里），当前层路径回声一致。
    // `page` 里的总数实测就是 dir + files 两键条数之和（/www/wwwroot 那层回的是「共24条」= 20 目录 + 4 文件）。
    private val dirFixture = """
        {"path":"/tmp/aa-test-files","page":"<span class='P_strip'>共4条</span>",
         "dir":[{"nm":"sub","sz":4096,"mt":1760000000,"acc":"755","user":"www","lnk":"","durl":"","fav":"0","rmk":"","top":0}],
         "files":[{"nm":"a.txt","sz":3,"mt":1760000001,"acc":"644","user":"www","lnk":"","durl":"","fav":"0","rmk":"","top":0},
                  {"nm":"lnk.txt","sz":7,"mt":1760000002,"acc":"777","user":"www","lnk":"->/tmp/aa-test-files/a.txt","durl":"","fav":"0","rmk":"","top":1},
                  {"nm":".fluah_time","sz":11,"mt":1759990000,"acc":"644","user":"root","lnk":"","durl":"","fav":"1","rmk":"备注","top":0}],
         "store":[],"dir_history":[{"val":"/www/wwwroot","time":1759900000}],"search_history":[{"val":"sql","time":1759900100}],
         "file_recycle":true,"is_max":false}
    """.trimIndent()

    @Test
    fun `目录和文件分在两个键里 路径由所在键决定`() {
        val page = btDirPage(parse(dirFixture), "/tmp/aa-test-files")
        assertEquals(4, page.entries.size)
        assertEquals(1, page.dirCount)
        assertEquals(3, page.fileCount)
        val sub = page.entries.first { it.name == "sub" }
        assertTrue(sub.isDir)
        assertTrue(sub.isNavigable)
        assertEquals("/tmp/aa-test-files/sub", sub.path)
        assertEquals(1760000000L, sub.modifiedAt)
        assertEquals("755", sub.perm)
        assertEquals("www", sub.owner)
        // 目录那一格面板给的是 inode 大小，界面上不能当内容占用摆出来。
        assertEquals("", sub.sizeText)
        val link = page.entries.first { it.name == "lnk.txt" }
        assertEquals("/tmp/aa-test-files/a.txt", link.symlink)
        assertTrue(link.pinned)
        // 总数只写在 page 那串 HTML 里，实测它等于 dir + files 两键的条数之和。
        assertEquals(4, page.total)
    }

    @Test
    fun `点号开头的隐藏项和收藏标记都读得出来`() {
        val page = btDirPage(parse(dirFixture), "/tmp/aa-test-files")
        val hidden = page.entries.first { it.name == ".fluah_time" }
        assertFalse(hidden.isDir)
        assertTrue(hidden.favorite)
        assertEquals("备注", hidden.remark)
        // 这台一个收藏目录都没建过，store 实测一直是空数组。
        assertEquals(emptyList<String>(), page.favorites)
        assertEquals(1, page.recents.size)
        assertEquals("/www/wwwroot", page.recents.first().value)
        assertTrue(page.recycleEnabled)
        assertFalse(page.truncated)
    }

    @Test
    fun `路径不存在时面板换了回声 这里必须认出来`() {
        // 实测：发 /tmp/aa-test-files/nope，面板不报错，回包的 path 变成 /www/wwwroot。
        val page = btDirPage(parse(dirFixture.replace("\"path\":\"/tmp/aa-test-files\"", "\"path\":\"/www/wwwroot\"")), "/tmp/aa-test-files/nope")
        assertEquals("/www/wwwroot", page.echoedPath)
        assertFalse(page.pathMatches)
    }

    @Test
    fun `回声只差一个末尾斜杠不算改路`() {
        val page = btDirPage(parse(dirFixture.replace("\"path\":\"/tmp/aa-test-files\"", "\"path\":\"/tmp/aa-test-files/\"")), "/tmp/aa-test-files")
        assertTrue(page.pathMatches)
    }

    @Test
    fun `回包整个不是对象时给空页而不是假数据`() {
        val page = btDirPage(parse("""[]"""), "/tmp/whatever")
        assertTrue(page.entries.isEmpty())
        assertNull(page.total)
        assertEquals("/tmp/whatever", page.echoedPath)
        assertTrue(page.pathMatches)
    }

    @Test
    fun `回包给了对象却没带 path 这一格 不算改路也不假装有回声`() {
        val page = btDirPage(parse("""{"msg":"目录不存在"}"""), "/tmp/whatever")
        assertTrue(page.entries.isEmpty())
        assertEquals("", page.echoedPath)
        assertTrue(page.pathMatches)
    }

    // 实测 GetDir：大写键 + 每行一条 `;` 串。后面七个位置实测和命名键顺序不一致，所以只信前五个。
    private val legacyFixture = """
        {"PATH":"/tmp/aa-test-files","PAGE":"共3条",
         "DIR":["sub;4096;1760000000;755;www;;;0;;0;0;0;0"],
         "FILES":["a.txt;3;1760000001;644;www;;;0;;0;0;0;0",
                  "b.txt;5;1760000002;644;root;;;0;;0;0;0;0"],
         "STORE":[],"DIR_HISTORY":[],"SEARCH_HISTORY":[],"FILE_RECYCLE":true,"IS_MAX":false}
    """.trimIndent()

    @Test
    fun `老路口按分号切前五个位置`() {
        val page = btDirPageLegacy(parse(legacyFixture), "/tmp/aa-test-files")
        assertEquals(3, page.entries.size)
        assertEquals(3, page.total)
        val file = page.entries.first { it.name == "b.txt" }
        assertTrue(file.isDir.not())
        assertEquals(5L, file.sizeBytes)
        assertEquals("644", file.perm)
        assertEquals("root", file.owner)
        // 位置后面那几串没对上号，只能是空，不能瞎猜。
        assertEquals("", file.symlink)
        assertFalse(file.favorite)
        assertEquals("/tmp/aa-test-files/sub", page.entries.first { it.name == "sub" }.path)
    }

    @Test
    fun `老路口也能认出被换掉的回声`() {
        val page = btDirPageLegacy(parse(legacyFixture.replace("\"PATH\":\"/tmp/aa-test-files\"", "\"PATH\":\"/www/wwwroot\"")), "/tmp/none")
        assertFalse(page.pathMatches)
    }

    // 实测 Get_Recycle_bin：数据在顶层 list，all/dir/file/image/ont_text/db 是计数，status 是布尔。
    private val recycleFixture = """
        {"list":[{"rname":".sub_recycle_bin_a1b2","name":"sub","dname":"/tmp/aa-test-files/sub","time":1760000200,"size":4096,"is_dir":true},
                 {"rname":".a.txt_recycle_bin_c3d4","name":"a.txt","dname":"/tmp/aa-test-files/a.txt","time":1760000100,"size":3,"is_dir":false},
                 {"rname":"","name":"脏行","dname":"/x","time":0,"size":0,"is_dir":false}],
         "all":1545,"dir":7,"file":1530,"image":12,"ont_text":900,"db":0,"all_size":983040,
         "status":true,"status_db":false,"page":"共1545条"}
    """.trimIndent()

    @Test
    fun `回收站读顶层list 没有rname的脏行丢掉`() {
        val page = btRecyclePage(parse(recycleFixture))
        assertEquals(2, page.entries.size)
        assertEquals(1545, page.total)
        val dir = page.entries.first { it.isDir }
        assertEquals("sub", dir.name)
        // dname 实测就是原始完整路径（已含名字本身），别再拼一遍。
        assertEquals("/tmp/aa-test-files/sub", dir.originalPath)
        assertEquals("/tmp/aa-test-files", dir.originalDir)
        assertEquals(3L, page.entries.first { !it.isDir }.sizeBytes)
        assertTrue(page.enabled)
        assertFalse(page.dbEnabled)
    }

    @Test
    fun `面板那个拼错的键 ont_text 就是文本类计数`() {
        val counts = btRecyclePage(parse(recycleFixture)).counts
        assertEquals(900, counts.text)
        assertEquals(7, counts.dir)
        assertEquals(0, counts.db)
        assertEquals(983040L, counts.totalSizeBytes)
        assertEquals("目录 7 · 文件 1530 · 图片 12 · 文本 900", counts.breakdown)
    }

    @Test
    fun `回收站空列表也照实报 不假装有`() {
        val page = btRecyclePage(parse("""{"list":[],"all":0,"status":true,"status_db":false}"""))
        assertTrue(page.isEmpty)
        assertEquals(0, page.total)
    }

    // 实测 GetFileHistory：status 是字符串 "true"（套不上信封），page 里写总条数。
    private val historyFixture = """
        {"status":"true","msg":"获取成功","data":[
          {"id":812,"type":"删除文件","log":"/tmp/aa-test-files/b.txt 已移动到回收站!","addtime":"2026-10-20 18:31:07","username":"登录用户"},
          {"id":813,"type":"修改权限","log":"","addtime":"2026-10-20 18:31:09","username":"登录用户"}],
         "page":"<span>共11970条</span>"}
    """.trimIndent()

    @Test
    fun `文件历史没有信封 整包当数据读`() {
        val lines = btFileHistoryLines(parse(historyFixture))
        // log 是空的行不算一条事件（面板偶尔塞占位行）。
        assertEquals(1, lines.size)
        assertEquals("删除文件", lines.first().type)
        assertEquals("2026-10-20 18:31:07", lines.first().time)
        assertEquals("812", lines.first().id)
        assertEquals(11970, btTotal(parse(historyFixture)))
    }

    @Test
    fun `有版本的文件列表从filepath取名字 filename 是脏的`() {
        val files = btFileHistoryFiles(
            parse(
                """{"data":[{"filename":"undefined","filepath":"/www/wwwroot/example.com/index.php","time":"2026-10-20 10:00:00","id":12,
                            "historys":[{"id":1},{"id":2}]},
                           {"filename":"undefined","filepath":"undefined","time":"","id":0,"historys":[]}]}"""
            )
        )
        assertEquals(1, files.size)
        assertEquals("/www/wwwroot/example.com/index.php", files.first().path)
        assertEquals(2, files.first().versions)
        assertEquals("12", files.first().id)
    }

    @Test
    fun `test_path 不存在时面板把path写成空串 只认exists`() {
        assertTrue(btPathProbe(parse("""{"path":"/tmp/aa-test-files","exists":true,"is_dir":true}""")).exists)
        val missing = btPathProbe(parse("""{"path":"","exists":false,"is_dir":false}"""))
        assertFalse(missing.exists)
        assertEquals("", missing.path)
        assertFalse(btPathProbe(parse("""{"msg":"错误"}""")).exists)
    }

    @Test
    fun `占用问的是get_path_size 目录不是inode那个数`() {
        val size = btPathSize(parse("""{"path":"/tmp","size":3772868}"""))
        assertEquals("/tmp", size.path)
        assertEquals(3772868L, size.bytes)
        assertTrue(size.text.isNotBlank())
        assertNull(btPathSize(parse("""{"msg":""}""")).bytes)
    }

    @Test
    fun `目标目录里有没有同名 空数组就是没有`() {
        assertTrue(btExistingFiles(parse("""{"data":[]}""")).isEmpty())
        val hit = btExistingFiles(
            parse("""[{"filename":"a.txt","size":9,"is_dir":false}]""")
        )
        assertEquals(1, hit.size)
        assertEquals("a.txt", hit.first().name)
        assertEquals(9L, hit.first().sizeBytes)
        assertFalse(hit.first().isDir)
    }

    @Test
    fun `正文里data是内容 msg是它自己 两个键都给`() {
        val body = btFileBody(
            parse("""{"data":"<?php echo 1;","encoding":"UTF-8","size":13,"only_read":false,"auto_save":false,"historys":[{"id":1}],"st_mtime":1760000000,"msg":"ok"}"""),
            "/www/wwwroot/example.com/index.php"
        )
        assertEquals("<?php echo 1;", body.content)
        assertEquals("UTF-8", body.encodingLabel)
        assertEquals(13L, body.sizeBytes)
        assertFalse(body.readOnly)
        assertFalse(body.tooLarge)
        assertEquals(1, body.historyCount)
        assertEquals(1760000000L, body.diskModifiedAt)
        assertTrue(body.copy(sizeBytes = MaxEditableBytes + 1).tooLarge)
        assertEquals("面板没给编码", body.copy(encoding = "").encodingLabel)
    }

    @Test
    fun `权限与属主 用户名单原样带出去`() {
        val access = btFileAccess(parse("""{"chmod":"755","chown":"www","users":["www","root"]}"""))
        assertEquals("755", access.perm)
        assertEquals("www", access.owner)
        assertEquals(listOf("www", "root"), access.users)
    }

    @Test
    fun `收藏目录两种形状都接`() {
        val strings = btDirPage(parse(dirFixture.replace("\"store\":[]", "\"store\":[\"/www/wwwroot/example.com\"]")), "/tmp/aa-test-files")
        assertEquals(listOf("/www/wwwroot/example.com"), strings.favorites)
        val objects = btDirPage(
            parse(dirFixture.replace("\"store\":[]", "\"store\":[{\"path\":\"/www/backup\",\"name\":\"备份\"}]")),
            "/tmp/aa-test-files"
        )
        assertEquals(listOf("/www/backup"), objects.favorites)
    }

    @Test
    fun `路径工具 拼路径 上一层 面包屑`() {
        assertEquals("/www/wwwroot/a.zip", btJoinPath("/www/wwwroot/", "a.zip"))
        // 父路径为空时，名字前面那一根斜杠就是新的根；带 `../` 的写法由表单拦，不在这拼。
        assertEquals("/x", btJoinPath("", "/x"))
        assertEquals("/www", btJoinPath("/www", ""))
        assertEquals("/", btJoinPath("", ""))
        assertEquals("/www", btParentPath("/www/backup/"))
        assertEquals("/", btParentPath("/www"))
        assertEquals("/", btParentPath("/"))
        assertEquals(listOf("/" to "/"), btPathCrumbs("/"))
        assertEquals(
            listOf("www" to "/www", "wwwroot" to "/www/wwwroot", "example.com" to "/www/wwwroot/example.com"),
            btPathCrumbs("/www/wwwroot/example.com/")
        )
        assertEquals("index.php", btFileNameOf("/www/wwwroot/example.com/index.php"))
    }

    @Test
    fun `表单校验 路径不许跳上级 名字不许带斜杠`() {
        assertNull(BtFilesForm.pathError("/www/wwwroot"))
        assertEquals("路径要从 / 开头写", BtFilesForm.pathError("www/wwwroot"))
        assertTrue(BtFilesForm.pathError("/www/../../etc").orEmpty().contains(".."))
        assertEquals("路径是空的", BtFilesForm.pathError("   "))
        assertNull(BtFilesForm.nameError("a.txt"))
        assertTrue(BtFilesForm.nameError("a/b").orEmpty().contains("斜杠"))
        assertNotNull(BtFilesForm.nameError(".."))
        assertNotNull(BtFilesForm.nameError("x".repeat(81)))
    }
}
