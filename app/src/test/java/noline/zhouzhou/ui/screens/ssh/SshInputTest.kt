package noline.zhouzhou.ui.screens.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端输入翻译层的单测。
 *
 * 钉的都是「敲下去没反应」或者「反应错了」这类在真机上很难一眼看出原因的细节：
 * 1. 输入法上报的是文本差异，得翻成「几个退格 + 新增的那串」，替换和自动纠错也走同一条路；
 * 2. 回车必须是 `\r`，发 `\n` 的话 readline 那边只会另起一行不换命令；
 * 3. Ctrl 组合键走 A=1 那张表，Ctrl-C 是 0x03，不是字母 'c'；
 * 4. 方向键在开了 application cursor keys（`vim`、`less`）时要发 `ESC O A`，否则光标不动；
 * 5. 粘贴带上 bracketed paste 前后缀，多行内容才不会被当成命令直接跑；
 * 6. 256 色表的立方和灰阶按 xterm 的编号算，串一位整屏配色就全错。
 */
class SshInputTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

    // ── normalize ──

    @Test
    fun `回车翻成 CR 并丢掉其余控制字符`() {
        val out = SshInput.normalize("ls -l\n\t\u0000\u0007done")
        assertEquals("ls -l\r\tdone", String(out, Charsets.UTF_8))
    }

    @Test
    fun `DEL 也算控制字符要丢掉`() {
        assertEquals("ab", String(SshInput.normalize("a\u007Fb"), Charsets.UTF_8))
    }

    @Test
    fun `中文按 UTF-8 发出去`() {
        val out = SshInput.normalize("宝塔")
        assertArrayEquals("宝塔".toByteArray(Charsets.UTF_8), out)
    }

    // ── deltaBytes ──

    @Test
    fun `纯追加只发新增的那几个字`() {
        val out = SshInput.deltaBytes("cd /", "cd /etc")
        assertEquals("etc", String(out, Charsets.UTF_8))
    }

    @Test
    fun `退格发等量的 DEL 而不是负数的追加`() {
        val out = SshInput.deltaBytes("abcd", "ab")
        assertArrayEquals(bytes(0x7F, 0x7F), out)
    }

    @Test
    fun `输入法替换是先退掉差异再补新的`() {
        val out = SshInput.deltaBytes("cat foo", "cat bar")
        assertArrayEquals(bytes(0x7F, 0x7F, 0x7F) + "bar".toByteArray(Charsets.UTF_8), out)
    }

    @Test
    fun `尾部一样的部分不重复发`() {
        val out = SshInput.deltaBytes("abc123", "xyz123")
        assertArrayEquals(bytes(0x7F, 0x7F, 0x7F) + "xyz".toByteArray(Charsets.UTF_8), out)
    }

    @Test
    fun `整行换成别的字要先退掉原来那一整行`() {
        val out = SshInput.deltaBytes("abc", "xyz")
        assertArrayEquals(bytes(0x7F, 0x7F, 0x7F) + "xyz".toByteArray(Charsets.UTF_8), out)
    }

    @Test
    fun `回车那一下发的是 CR`() {
        val out = SshInput.deltaBytes("ls", "ls\n")
        assertArrayEquals(bytes(0x0D), out)
    }

    @Test
    fun `文本没变就一个字节都不发`() {
        assertEquals(0, SshInput.deltaBytes("same", "same").size)
    }

    @Test
    fun `清空输入框发一串退格`() {
        val out = SshInput.deltaBytes("宝塔", "")
        assertEquals(2, out.size)
        assertTrue(out.all { it == 0x7F.toByte() })
    }

    // ── Ctrl 组合键 ──

    @Test
    fun `Ctrl 组合走 A 等于 1 那张表`() {
        assertArrayEquals(bytes(0x03), SshInput.ctrl('c'))
        assertArrayEquals(bytes(0x03), SshInput.ctrl('C'))
        assertArrayEquals(bytes(0x01), SshInput.ctrl('a'))
        assertArrayEquals(bytes(0x1A), SshInput.ctrl('z'))
    }

    @Test
    fun `不是字母的 Ctrl 组合什么都不发`() {
        assertEquals(0, SshInput.ctrl('1').size)
        assertEquals(0, SshInput.ctrl(' ').size)
    }

    // ── 方向键与功能键 ──

    @Test
    fun `普通模式下方向键走 CSI`() {
        assertArrayEquals(bytes(0x1B) + "[A".toByteArray(), SshInput.arrow(TermDirection.Up, false))
        assertArrayEquals(bytes(0x1B) + "[B".toByteArray(), SshInput.arrow(TermDirection.Down, false))
        assertArrayEquals(bytes(0x1B) + "[C".toByteArray(), SshInput.arrow(TermDirection.Right, false))
        assertArrayEquals(bytes(0x1B) + "[D".toByteArray(), SshInput.arrow(TermDirection.Left, false))
    }

    @Test
    fun `application cursor keys 打开时换成 SS3`() {
        assertArrayEquals(bytes(0x1B) + "OA".toByteArray(), SshInput.arrow(TermDirection.Up, true))
        assertArrayEquals(bytes(0x1B) + "OD".toByteArray(), SshInput.arrow(TermDirection.Left, true))
    }

    @Test
    fun `功能键各自发自己那一串`() {
        assertArrayEquals(bytes(0x1B), SshInput.functionKey("Esc"))
        assertArrayEquals(bytes(0x0D), SshInput.functionKey("Enter"))
        assertArrayEquals(bytes(0x09), SshInput.functionKey("Tab"))
        assertArrayEquals(bytes(0x7F), SshInput.functionKey("Backspace"))
        assertArrayEquals(bytes(0x1B) + "[3~".toByteArray(), SshInput.functionKey("Delete"))
        assertArrayEquals(bytes(0x1B) + "[H".toByteArray(), SshInput.functionKey("Home"))
        assertArrayEquals(bytes(0x1B) + "[F".toByteArray(), SshInput.functionKey("End"))
        assertArrayEquals(bytes(0x1B) + "[5~".toByteArray(), SshInput.functionKey("PgUp"))
        assertArrayEquals(bytes(0x1B) + "[6~".toByteArray(), SshInput.functionKey("PgDn"))
    }

    @Test
    fun `认不出的键名不发字节`() {
        assertEquals(0, SshInput.functionKey("Super").size)
    }

    // ── 粘贴 ──

    @Test
    fun `bracketed paste 要包前后缀`() {
        val out = SshInput.paste("echo hi\n", bracketed = true)
        assertEquals(
            "\u001B[200~echo hi\r\u001B[201~",
            String(out, Charsets.UTF_8)
        )
    }

    @Test
    fun `没开 bracketed 就原样发`() {
        assertArrayEquals(SshInput.normalize("echo hi"), SshInput.paste("echo hi", bracketed = false))
    }

    @Test
    fun `断开那一下发的是 exit 加回车`() {
        assertEquals("exit\r", String(SshInput.exitCommand(), Charsets.UTF_8))
    }

    // ── 256 色表 ──

    @Test
    fun `色表刚好 256 格且都不透明`() {
        assertEquals(256, TerminalPalette.Colors.size)
        assertTrue(TerminalPalette.Colors.all { (it ushr 24) and 0xFF == 0xFF })
    }

    @Test
    fun `立方按 16 加 36r 加 6g 加 b 编号`() {
        // 196 = 16 + 36*5 → 纯红；21 = 16 + 5 → 纯蓝；46 = 16 + 30 → 纯绿
        assertEquals(0xFFFF0000.toInt(), TerminalPalette.Colors[196])
        assertEquals(0xFF0000FF.toInt(), TerminalPalette.Colors[21])
        assertEquals(0xFF00FF00.toInt(), TerminalPalette.Colors[46])
        assertEquals(0xFF000000.toInt(), TerminalPalette.Colors[16])
        // 阶梯是 0/95/135/175/215/255，中间那格取 135
        assertEquals(0xFF878787.toInt(), TerminalPalette.Colors[16 + 36 * 2 + 6 * 2 + 2])
    }

    @Test
    fun `末 24 阶灰从 8 起步每阶加 10`() {
        assertEquals(0xFF080808.toInt(), TerminalPalette.Colors[232])
        assertEquals(0xFFEEEEEE.toInt(), TerminalPalette.Colors[255])
    }

    @Test
    fun `前 16 色是自定义的暖色不是全零`() {
        assertTrue((0 until 16).all { TerminalPalette.Colors[it] != 0 })
        assertEquals(0xFF23252D.toInt(), TerminalPalette.Colors[0])
    }

    @Test
    fun `调试用十六进制能读出转义串`() {
        assertEquals("1B 5B 41", SshInput.arrow(TermDirection.Up, false).hex())
    }
}
