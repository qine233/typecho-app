package noline.zhouzhou.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSI 终端屏幕单测：把 `top` / `vim` / `htop` 活着所依赖的那几条控制序列钉死。
 *
 * 钉的都是写歪了不报错、只在真机上表现成「画面糊了」的八件事：
 * 1. 延迟换行——写满最后一列不能立刻折行，否则整屏文本会莫名多出一行空行；
 * 2. 汉字占两列，右半边被占掉但不进字符串（宝塔的中文提示 + `vim` 的绝对定位全靠它）；
 * 3. 滚动只在 `[2;4r` 给的区域内发生，区域外那一行不能被顶掉；
 * 4. 备用屏 `?1049h/l` 进出时，主屏内容和光标都要原样回来；
 * 5. `ESC[;5H` 这种空参数段取默认值 1，不是 0；
 * 6. 真彩 `38;2;r;g;b` 降到 256 色立方，`38;5;N` 后面还能接着一条 `;1`；
 * 7. 一个汉字被拆成两个网络包时不能解成乱码；
 * 8. 回滚只在主屏累积，`ESC[2J` 连回滚一起清（`top` 刷整屏时不该堆几千行废话）。
 */
class TerminalScreenTest {

    private fun term(cols: Int = 40, rows: Int = 10) = TerminalScreen(cols, rows)

    private fun TerminalScreen.line(index: Int): String = rowText(index).trimEnd()

    private fun TerminalScreen.ch(row: Int, col: Int): Char = cell(row, col)?.ch ?: '?'

    // ── 光标与控制字符 ──

    @Test
    fun crLfAndOverwrite() {
        val t = term()
        t.feed("ab\r\ncd")
        assertEquals("ab", t.line(0))
        assertEquals("cd", t.line(1))
        t.feed("\rX")
        assertEquals("Xd", t.line(1))
        assertEquals("ab", t.line(0))
    }

    @Test
    fun backspaceMovesLeftWithoutErasing() {
        val t = term()
        t.feed("abc\b\bX")
        assertEquals("aXc", t.line(0))
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun tabGoesToNextStop() {
        val t = term()
        t.feed("a\tb")
        assertEquals('a', t.ch(0, 0))
        assertEquals('b', t.ch(0, 8))
        t.feed("\t\bc")
        assertEquals('c', t.ch(0, 15))
    }

    @Test
    fun wrapIsDeferredUntilTheNextGlyph() {
        val t = term(cols = 20, rows = 6)
        t.feed("abcdefghijklmnopqrst")
        assertEquals("abcdefghijklmnopqrst", t.line(0))
        assertEquals(0, t.cursorRow)
        assertEquals(19, t.cursorCol)
        // 第 21 个字符还没落，第二行必须仍然是空的
        assertEquals("", t.line(1))
        t.feed("u")
        assertEquals("u", t.line(1))

        val lf = term(cols = 20, rows = 6)
        lf.feed("abcdefghijklmnopqrst\n")
        assertEquals(1, lf.cursorRow)
        assertEquals("", lf.line(1))
    }

    @Test
    fun cursorAddressingAndEmptyParamSegments() {
        val a = term(cols = 20, rows = 6)
        a.feed("\u001b[3;5Hxyz")
        assertEquals("    xyz", a.line(2))
        assertEquals(7, a.cursorCol)

        val b = term(cols = 20, rows = 6)
        b.feed("\u001b[;7Hab")
        assertEquals("      ab", b.line(0))

        val c = term(cols = 20, rows = 6)
        c.feed("abcdef\u001b[3d\u001b[5GX")
        assertEquals("abcdef", c.line(0))
        assertEquals("    X", c.line(2))
    }

    @Test
    fun relativeCursorMovesClamp() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[3;5H\u001b[2A\u001b[C\u001b[D\u001b[3B\u001b[4C")
        assertEquals(3, t.cursorRow)
        assertEquals(8, t.cursorCol)
        t.feed("\u001b[99A\u001b[99C")
        assertEquals(0, t.cursorRow)
        assertEquals(19, t.cursorCol)
    }

    @Test
    fun eraseLineModes() {
        val a = term(cols = 20, rows = 6)
        a.feed("abcdef\u001b[1;4H\u001b[K")
        assertEquals("abc", a.line(0))

        val b = term(cols = 20, rows = 6)
        b.feed("abcdef\u001b[1;4H\u001b[1K")
        assertEquals("    ef", b.line(0))

        val c = term(cols = 20, rows = 6)
        c.feed("abcdef\u001b[2K")
        assertEquals("", c.line(0))
    }

    @Test
    fun eraseDisplayModes() {
        val t = term(cols = 20, rows = 6)
        t.feed("r0\r\nr1\r\nr2\u001b[2;1H\u001b[J")
        assertEquals("r0", t.line(0))
        assertEquals("", t.line(1))
        assertEquals("", t.line(2))
    }

    @Test
    fun insertAndDeleteLines() {
        val t = term(cols = 20, rows = 6)
        t.feed("a\r\nb\r\nc")
        t.feed("\u001b[2;1H\u001b[2L")
        assertEquals("a", t.line(0))
        assertEquals("", t.line(1))
        assertEquals("", t.line(2))
        assertEquals("b", t.line(3))
        assertEquals("c", t.line(4))
        t.feed("\u001b[2;1H\u001b[2M")
        assertEquals("b", t.line(1))
        assertEquals("c", t.line(2))
        assertEquals("", t.line(3))
    }

    @Test
    fun scrollStaysInsideTheRegion() {
        val t = term(cols = 20, rows = 6)
        t.feed("keep\r\nl1\r\nl2\r\nl3")
        t.feed("\u001b[2;4r")
        assertEquals(1, t.cursorRow)
        t.feed("\u001b[4;1H\r\n")
        assertEquals("keep", t.line(0))
        assertEquals("l2", t.line(1))
        assertEquals("l3", t.line(2))
        assertEquals("", t.line(3))
        assertEquals("l1", t.scrollbackRow(0)?.text()?.trimEnd())
    }

    @Test
    fun csiScrollUpDown() {
        val t = term(cols = 20, rows = 5)
        t.feed("a\r\nb\r\nc")
        t.feed("\u001b[1S")
        assertEquals("b", t.line(0))
        assertEquals("c", t.line(1))
        assertEquals("a", t.scrollbackRow(0)?.text()?.trimEnd())
        t.feed("\u001b[1T")
        assertEquals("", t.line(0))
        assertEquals("b", t.line(1))
    }

    @Test
    fun lineAndCharLevelEdits() {
        val il = term(cols = 20, rows = 6)
        il.feed("a\r\nb")
        il.feed("\u001b[2;1H\u001b[1L")
        assertEquals("a", il.line(0))
        assertEquals("", il.line(1))
        assertEquals("b", il.line(2))

        val dl = term(cols = 20, rows = 6)
        dl.feed("a\r\nb\r\nc")
        dl.feed("\u001b[2;1H\u001b[1M")
        assertEquals("a", dl.line(0))
        assertEquals("c", dl.line(1))
        assertEquals("", dl.line(2))
        assertEquals("", dl.line(3))

        val ich = term(cols = 20, rows = 6)
        ich.feed("abcde\u001b[1;3H\u001b[2@")
        assertEquals("ab  cde", ich.line(0))

        val dch = term(cols = 20, rows = 6)
        dch.feed("abcde\u001b[1;3H\u001b[2P")
        assertEquals("abe", dch.line(0))

        val ech = term(cols = 20, rows = 6)
        ech.feed("abcde\u001b[1;3H\u001b[2X")
        assertEquals("ab  e", ech.line(0))
    }

    @Test
    fun scrollUpWithinRegionKeepsTheHead() {
        val t = term(cols = 20, rows = 5)
        t.feed("head")
        t.feed("\u001b[2;4r")
        assertEquals(1, t.cursorRow)
        t.feed("a\r\nb\r\nc\r\nd\r\ne")
        assertEquals("head", t.line(0))
        assertEquals("c", t.line(1))
        assertEquals("d", t.line(2))
        assertEquals("e", t.line(3))
        assertEquals(2, t.scrollbackSize)
        assertEquals("b", t.scrollbackRow(0)?.text()?.trimEnd())
    }

    @Test
    fun scrollbackGrowsOnlyOnTheMainScreen() {
        val alt = term(cols = 20, rows = 4)
        alt.feed("\u001b[?1049h")
        alt.feed("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng")
        assertEquals(0, alt.scrollbackSize)
        assertTrue(alt.onAltScreen)

        val cap = TerminalScreen(20, 4, scrollbackLimit = 2)
        cap.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7")
        assertEquals(2, cap.scrollbackSize)
        assertNull(cap.scrollbackRow(2))
    }

    @Test
    fun sgrBasicColors() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[31mA")
        assertEquals(1.toShort(), t.cell(0, 0)?.fg)
        assertEquals(TermCell.DEFAULT_COLOR, t.cell(0, 0)?.bg)
        t.feed("\u001b[0m\u001b[31;42;1mB")
        assertEquals(1.toShort(), t.cell(0, 1)?.fg)
        assertEquals(2.toShort(), t.cell(0, 1)?.bg)
        assertEquals(TermCell.FlagBold, t.cell(0, 1)?.flags)
        t.feed("\u001b[0m\u001b[95mC")
        assertEquals(13.toShort(), t.cell(0, 2)?.fg)
        t.feed("\u001b[0m\u001b[102mD")
        assertEquals(10.toShort(), t.cell(0, 3)?.bg)
        t.feed("\u001b[0m\u001b[39;49mE")
        assertEquals(TermCell.DEFAULT_COLOR, t.cell(0, 4)?.fg)
        assertEquals(TermCell.DEFAULT_COLOR, t.cell(0, 4)?.bg)
    }

    @Test
    fun sgrFlagsAreIndependent() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[1;4mA")
        val boldUnderline = TermCell.FlagBold.toInt() or TermCell.FlagUnderline.toInt()
        assertEquals(boldUnderline.toShort(), t.cell(0, 0)?.flags)
        t.feed("\u001b[22mB")
        assertEquals(TermCell.FlagUnderline, t.cell(0, 1)?.flags)
        t.feed("\u001b[24mC")
        assertEquals(0.toShort(), t.cell(0, 2)?.flags)
        t.feed("\u001b[7mD")
        assertEquals(TermCell.FlagReverse, t.cell(0, 3)?.flags)
        t.feed("\u001b[27mE")
        assertEquals(0.toShort(), t.cell(0, 4)?.flags)
    }

    @Test
    fun sgrExtendedColors() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[38;5;213mA")
        assertEquals(213.toShort(), t.cell(0, 0)?.fg)
        t.feed("\u001b[0m\u001b[38;5;213;1mB")
        assertEquals(213.toShort(), t.cell(0, 1)?.fg)
        assertEquals(TermCell.FlagBold, t.cell(0, 1)?.flags)
        t.feed("\u001b[0m\u001b[48;5;9mC")
        assertEquals(9.toShort(), t.cell(0, 2)?.bg)
        t.feed("\u001b[0m\u001b[38;2;255;0;0mD")
        assertEquals(TermCell.rgbToCube(255, 0, 0).toShort(), t.cell(0, 3)?.fg)
        t.feed("\u001b[0m\u001b[38;2;255;255;255mE")
        assertEquals(231.toShort(), t.cell(0, 4)?.fg)
    }

    @Test
    fun unknownAndPrivateSequencesAreHarmless() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b(Babc")
        assertEquals("abc", t.line(0))
        t.feed("\u001b[?6n\u001b[?1;2;3\$p\u001b[?2026h")
        assertEquals("abc", t.line(0))
        val colored = term(cols = 20, rows = 6)
        colored.feed("\u001b[>4;2mA")
        assertEquals(0.toShort(), colored.cell(0, 0)?.flags)
    }

    @Test
    fun ansiResetAndBell() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[31mabc\r\n\u001b[?1049h")
        t.feed("\u001bc")
        assertEquals("", t.line(0))
        assertFalse(t.onAltScreen)
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorCol)
        assertEquals(TermCell.DEFAULT_COLOR, t.cell(0, 0)?.fg)

        // `ESC[c` 是设备属性（DECID），只许吃掉，不许把屏幕洗了
        val id = term(cols = 20, rows = 6)
        id.feed("\u001b[31mab\u001b[c")
        assertEquals("ab", id.line(0))
        assertEquals(1.toShort(), id.cell(0, 0)?.fg)
        assertEquals(2, id.cursorCol)

        val bell = term(cols = 20, rows = 6)
        bell.feed("x\u0007y\u0007")
        assertEquals(2, bell.bellCount)
        assertEquals("xy", bell.line(0))
    }

    @Test
    fun wideGlyphRefusesTheLastColumn() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[1;20H中")
        assertEquals(1, t.cursorRow)
        assertEquals(2, t.cursorCol)
        assertEquals("中", t.line(1))
        assertEquals("", t.line(0))
        assertEquals(' ', t.ch(0, 19))
    }

    @Test
    fun cursorSnapsBackToTheWholeGlyph() {
        // 光标被绝对定位送到宽字符的右半边时，要退回整个字的左半边，不然下一个字符把字砍一半
        val t = term(cols = 20, rows = 6)
        t.feed("中")
        t.feed("\u001b[1;2H")
        assertEquals(0, t.cursorCol)

        val cover = term(cols = 20, rows = 6)
        cover.feed("中\u001b[1;2HX")
        assertEquals(1, cover.cursorCol)
        assertEquals("X", cover.line(0))
        assertFalse(cover.cell(0, 1)!!.continuation)
    }

    @Test
    fun resizeKeepsTopLeftAndUncutsWideGlyph() {
        val t = term(cols = 24, rows = 6)
        t.feed("hello\u001b[2;1Hworld")
        t.resize(20, 5)
        assertEquals(20, t.cols)
        assertEquals(5, t.rows)
        assertEquals("hello", t.line(0))
        assertEquals("world", t.line(1))
        val cut = term(cols = 21, rows = 6)
        cut.feed("\u001b[1;20H\u001b[?7l")
        cut.feed("中")
        cut.resize(20, 6)
        // 右半边被裁掉了，这个字得降级成窄的留在原位，不能凭空消失
        assertEquals('中', cut.ch(0, 19))
        assertFalse(cut.cell(0, 19)!!.wide)
    }

    @Test
    fun oscWindowTitles() {
        val t = term()
        t.feed("\u001b]0;root@bt:~\u0007")
        assertEquals("root@bt:~", t.windowTitle)
        t.feed("\u001b]1;icon\u0007")
        assertEquals("icon", t.windowTitle)
        t.feed("\u001b]10;?color\u0007")
        assertEquals("icon", t.windowTitle)
    }

    @Test
    fun utf8SplitAcrossPackets() {
        val t = term(cols = 20, rows = 6)
        val bytes = "中文".toByteArray(Charsets.UTF_8)
        t.feed(bytes.copyOfRange(0, 1))
        assertEquals("", t.line(0))
        t.feed(bytes.copyOfRange(1, 4))
        assertEquals("中", t.line(0))
        t.feed(bytes.copyOfRange(4, bytes.size))
        assertEquals("中文", t.line(0))
        assertEquals(4, t.cursorCol)
    }

    @Test
    fun astralGlyphFedOneByteAtATime() {
        val t = term(cols = 20, rows = 6)
        for (b in "\uD83D\uDE00".toByteArray(Charsets.UTF_8)) t.feed(byteArrayOf(b))
        assertEquals("\uD83D\uDE00", t.line(0))
        assertTrue(t.cell(0, 0)!!.wide)
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun readsAreBoundsSafe() {
        val t = term(cols = 20, rows = 6)
        t.feed("ab")
        assertNull(t.cell(6, 0))
        assertNull(t.cell(0, 20))
        assertNull(t.rowAt(9))
        assertNull(t.scrollbackRow(0))
        assertEquals("", t.rowText(99))
        assertEquals(-1, t.rowText(0).indexOf('c'))

        val before = t.revision
        t.feed("c")
        assertTrue(t.revision > before)
        val after = t.revision
        t.feed(ByteArray(0))
        assertEquals(after, t.revision)
    }

    @Test
    fun eraseWholeScreenClearsScrollback() {
        val t = term(cols = 20, rows = 4)
        t.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertTrue(t.scrollbackSize > 0)
        t.feed("\u001b[2J")
        assertEquals(0, t.scrollbackSize)
        assertEquals("", t.line(0))
    }

    @Test
    fun autoWrapCanBeTurnedOff() {
        val t = term(cols = 20, rows = 6)
        t.feed("\u001b[?7l")
        assertFalse(t.autoWrap)
        t.feed("x".repeat(60))
        assertEquals(0, t.cursorRow)
        assertEquals(19, t.cursorCol)
        assertEquals(20, t.line(0).length)
        t.feed("\u001b[?7h")
        assertTrue(t.autoWrap)
    }
}
