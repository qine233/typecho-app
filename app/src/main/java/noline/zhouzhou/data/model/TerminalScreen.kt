package noline.zhouzhou.data.model

import kotlin.math.max
import kotlin.math.min

/**
 * 终端里的一格：一个字符加一套 SGR 属性。颜色是 xterm 的 0..255 号，`-1` 表示「面板/程序没指定」。
 */
data class TermCell(
    var ch: Char = ' ',
    /** emoji / 表意文字扩展这种代理对的第二个码元；BMP 字符这里是 0。 */
    var ch2: Char = 0.toChar(),
    var fg: Short = DEFAULT_COLOR,
    var bg: Short = DEFAULT_COLOR,
    var flags: Short = 0,
    /** 这一格是宽字符（汉字、全角标点、emoji）的左半边，占两列。 */
    var wide: Boolean = false,
    /** 这一格被左边那个宽字符占着，不画东西。 */
    var continuation: Boolean = false
) {
    fun sameStyle(other: TermCell): Boolean = fg == other.fg && bg == other.bg && flags == other.flags

    fun erase(background: Short) {
        ch = ' '
        ch2 = 0.toChar()
        fg = DEFAULT_COLOR
        bg = background
        flags = 0
        wide = false
        continuation = false
    }

    fun take(other: TermCell) {
        ch = other.ch
        ch2 = other.ch2
        fg = other.fg
        bg = other.bg
        flags = other.flags
        wide = other.wide
        continuation = other.continuation
    }

    companion object {
        const val DEFAULT_COLOR: Short = -1

        const val FlagBold = 1.toShort()
        const val FlagFaint = 2.toShort()
        const val FlagItalic = 4.toShort()
        const val FlagUnderline = 8.toShort()
        const val FlagBlink = 16.toShort()
        const val FlagReverse = 32.toShort()
        const val FlagInvisible = 64.toShort()
        const val FlagStrike = 128.toShort()

        fun flagOf(code: Int): Short = when (code) {
            1 -> FlagBold
            2 -> FlagFaint
            3 -> FlagItalic
            4 -> FlagUnderline
            5, 6 -> FlagBlink
            7 -> FlagReverse
            8 -> FlagInvisible
            9 -> FlagStrike
            else -> 0
        }

        /** `ESC[22m` 同时关掉粗体和暗显，`ESC[24m` 只关掉下划线。 */
        fun offFlagsOf(code: Int): Short = when (code) {
            21 -> FlagBold
            22 -> (FlagBold.toInt() or FlagFaint.toInt()).toShort()
            23 -> FlagItalic
            24 -> FlagUnderline
            25 -> FlagBlink
            27 -> FlagReverse
            28 -> FlagInvisible
            29 -> FlagStrike
            else -> 0
        }

        /** 24 位真彩降到 xterm 的 6×6×6 立方：界面只要一张 256 色表就够。 */
        fun rgbToCube(r: Int, g: Int, b: Int): Int {
            fun channel(value: Int) = when {
                value < 48 -> 0
                value < 115 -> 1
                value < 155 -> 2
                value < 195 -> 3
                value < 235 -> 4
                else -> 5
            }
            return 16 + 36 * channel(r) + 6 * channel(g) + channel(b)
        }
    }
}

/**
 * 字宽表：哪些码点占两列。
 *
 * 宝塔的命令行、`yum` / `apt` 的提示、日志里的中文都是全角，把这些当一格宽排下去，
 * `vim`、`htop` 的绝对定位就会整体错位。这里是 Unicode East Asian Width 的 W/F 两类，
 * 加了常见 emoji 段，省略了生僻的表意文字扩展（够用且不影响面板场景）。
 */
object TermWidth {
    private val WIDE_RANGES = intArrayOf(
        0x1100, 0x115F,
        0x2E80, 0x303E,
        0x3041, 0x33FF,
        0x3400, 0x4DBF,
        0x4E00, 0x9FFF,
        0xA000, 0xA4CF,
        0xAC00, 0xD7A3,
        0xF900, 0xFAFF,
        0xFE10, 0xFE19,
        0xFE30, 0xFE6F,
        0xFF00, 0xFF60,
        0xFFE0, 0xFFE6,
        0x1F000, 0x1FAFF,
        0x20000, 0x2FFFD,
        0x30000, 0x3FFFD
    )

    fun isWide(code: Int): Boolean {
        if (code < 0x1100) return false
        var low = 0
        var high = WIDE_RANGES.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val start = WIDE_RANGES[mid * 2]
            if (code < start) {
                high = mid - 1
            } else if (code > WIDE_RANGES[mid * 2 + 1]) {
                low = mid + 1
            } else {
                return true
            }
        }
        return false
    }

    /** 附加符号（组合重音、emoji 变体选择符）不占列。 */
    fun isCombining(code: Int): Boolean {
        val type = Character.getType(code)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
    }
}

/** 一行格子。`touched` 只用来让 UI 跳过整片空白。 */
class TermRow(val cells: Array<TermCell>) {
    var touched: Boolean = false

    val size: Int get() = cells.size

    /**
     * 写一个码点到 [col]。[wide] 为真时右边那一列跟着被占掉（`vim` / `htop` 就靠这个对列）。
     * 代理对存在同一格的 `ch2` 里，所以列数只由宽度决定，不由码元个数决定。
     */
    fun put(col: Int, code: Int, style: TermCell, wide: Boolean) {
        val cell = cells.getOrNull(col) ?: return
        if (code <= 0xFFFF) {
            cell.ch = code.toChar()
            cell.ch2 = 0.toChar()
        } else {
            val pair = Character.toChars(code)
            cell.ch = pair[0]
            cell.ch2 = pair[1]
        }
        cell.fg = style.fg
        cell.bg = style.bg
        cell.flags = style.flags
        cell.wide = wide
        cell.continuation = false
        if (wide) {
            cells.getOrNull(col + 1)?.let { right ->
                right.ch = ' '
                right.ch2 = 0.toChar()
                right.fg = style.fg
                right.bg = style.bg
                right.flags = style.flags
                right.wide = false
                right.continuation = true
            }
        } else if (cells.getOrNull(col + 1)?.continuation == true) {
            // 窄字符盖掉宽字符的左半边时，右边那一列不能继续被占着
            fill(col + 1, style)
        }
        touched = true
    }

    /** 用当前前景/背景涂一个空格（擦除类操作用的是当前 SGR 的背景，不是默认底色）。 */
    fun fill(col: Int, style: TermCell) {
        val cell = cells.getOrNull(col) ?: return
        cell.ch = ' '
        cell.ch2 = 0.toChar()
        cell.fg = style.fg
        cell.bg = style.bg
        cell.flags = style.flags
        cell.wide = false
        cell.continuation = false
        touched = true
    }

    fun blank(background: Short) {
        for (cell in cells) cell.erase(background)
        touched = true
    }

    fun copyFrom(other: TermRow) {
        val n = min(size, other.size)
        for (i in 0 until n) cells[i].take(other.cells[i])
        for (i in n until size) cells[i].erase(TermCell.DEFAULT_COLOR)
        touched = other.touched
        normalize()
    }

    /**
     * 修被局部改写（删字符、插字符、缩窄）拆散的宽字符对：
     * 左半边没了就把孤立的右半边抹成空格，右半边没了就把宽字符降级成窄的。
     */
    fun normalize() {
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.continuation && (i == 0 || !cells[i - 1].wide)) cell.erase(cell.bg)
            if (cell.wide && (i == cells.lastIndex || !cells[i + 1].continuation)) cell.wide = false
        }
    }

    /** 界面拿这个串直接画：被占掉的那一列不出字，画出来才和终端的列数对得上。 */
    fun text(): String {
        val builder = StringBuilder(size)
        for (cell in cells) {
            if (cell.continuation) continue
            builder.append(cell.ch)
            if (cell.ch2 != 0.toChar()) builder.append(cell.ch2)
        }
        return builder.toString()
    }

    /** 进回滚环的那一份必须是副本：原行马上会被上面的内容盖掉。 */
    fun snapshot(): TermRow =
        TermRow(Array(size) { cells[it].copy() }).also { it.touched = touched }
}

/**
 * 一块能自己养活终端程序的屏幕：`top`、`vim`、`less` 靠的就是这里的绝对定位、滚动区域和备用屏。
 *
 * 只吃字节、只吐格子，不认 Compose 也不认 SSH，所以整块能在纯 JVM 单测里逐条 ANSI 钉住。
 * 界面拿 [rowAt] / [rowText] / [revision] 重画；键盘输入由界面编码成字节直接写进通道，不经这里。
 */
class TerminalScreen(
    initialCols: Int = DEFAULT_COLS,
    initialRows: Int = DEFAULT_ROWS,
    val scrollbackLimit: Int = DEFAULT_SCROLLBACK
) {
    var cols: Int = initialCols.coerceIn(MIN_COLS, MAX_COLS)
        private set
    var rows: Int = initialRows.coerceIn(MIN_ROWS, MAX_ROWS)
        private set

    private var screen = newScreen(cols, rows)
    private var savedMain: Array<TermRow>? = null
    private var savedMainCursorRow = 0
    private var savedMainCursorCol = 0
    private var scrollback = ArrayDeque<TermRow>()

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set
    var windowTitle = ""
        private set
    var applicationCursorKeys = false
        private set
    var bracketedPaste = false
        private set
    var autoWrap = true
        private set

    /** 程序 bell（`\a`）响了几次，界面上可以顶一下，不渲染。 */
    var bellCount = 0
        private set

    /** 每喂一包字节自增一次，UI 拿它决定要不要重画。 */
    var revision = 0L
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedRow = 0
    private var savedCol = 0
    private var wrapPending = false
    private var style = TermCell()
    private val utf8 = Utf8Stream()
    private var state = State.Text
    private val paramText = StringBuilder()
    private var privateMarker = ""
    private val oscText = StringBuilder()

    // ── 读 ──

    val onAltScreen: Boolean get() = savedMain != null

    val scrollbackSize: Int get() = scrollback.size

    /** [scrollback] 的最后一行是 index 0，往上翻就是 1、2、3…… */
    fun scrollbackRow(index: Int): TermRow? {
        if (index < 0 || index >= scrollback.size) return null
        return scrollback[scrollback.size - 1 - index]
    }

    fun cell(row: Int, col: Int): TermCell? {
        if (row !in 0 until rows || col !in 0 until cols) return null
        return screen[row].cells[col]
    }

    /** 界面按行取整排的格子画。 */
    fun rowAt(row: Int): TermRow? = screen.getOrNull(row)

    fun rowText(row: Int): String = screen.getOrNull(row)?.text().orEmpty()

    // ── 连接建立 / 断开 ──

    fun reset() {
        state = State.Text
        utf8.reset()
        style = TermCell()
        cursorVisible = true
        applicationCursorKeys = false
        bracketedPaste = false
        autoWrap = true
        windowTitle = ""
        bellCount = 0
        newScreenInto()
        revision++
    }

    /** 窗口尺寸变了：老内容左上角对齐留下，多余的行列裁掉。 */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(MIN_COLS, MAX_COLS)
        val r = newRows.coerceIn(MIN_ROWS, MAX_ROWS)
        if (c == cols && r == rows) return
        screen = copyIntoNewScreen(screen, c, r)
        savedMain?.let { savedMain = copyIntoNewScreen(it, c, r) }
        cols = c
        rows = r
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = min(cursorRow, rows - 1)
        cursorCol = min(cursorCol, cols - 1)
        savedRow = min(savedRow, rows - 1)
        savedCol = min(savedCol, cols - 1)
        wrapPending = false
        revision++
    }

    // ── 喂字节 ──

    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val text = utf8.decode(bytes)
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            advance(code)
            i += Character.charCount(code)
        }
        revision++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun advance(code: Int) {
        when (state) {
            State.Text -> when (code) {
                ESC -> state = State.Esc
                0x07 -> bellCount++
                0x08 -> backspace()
                0x09 -> { cursorCol = min((cursorCol / TAB + 1) * TAB, cols - 1); wrapPending = false; snapCursor() }
                0x0A, 0x0B, 0x0C -> newline()
                0x0D -> { cursorCol = 0; wrapPending = false }
                0x7F, 0x0E, 0x0F -> Unit
                else -> if (code >= 0x20) putCodePoint(code)
            }

            State.Esc -> {
                state = State.Text
                when (code.toChar()) {
                    '[' -> { paramText.setLength(0); privateMarker = ""; state = State.Csi }
                    ']' -> { oscText.setLength(0); state = State.Osc }
                    '7' -> { savedRow = cursorRow; savedCol = cursorCol }
                    '8' -> { cursorRow = savedRow; cursorCol = savedCol; wrapPending = false; snapCursor() }
                    'c' -> reset()
                    '(', ')', '*', '+' -> state = State.Charset
                    else -> Unit
                }
            }

            // `ESC ( B` 这类字符集指定：吃掉尾字节就行。
            State.Charset -> state = State.Text

            State.Csi -> {
                val ch = code.toChar()
                when {
                    ch in '@'..'~' -> { dispatchCsi(ch); state = State.Text }
                    ch == '?' || ch == '>' || ch == '=' || ch == '<' -> privateMarker += ch
                    ch == ';' || ch in '0'..'9' -> paramText.append(ch)
                    // 0x20..0x2F 是中间字节（`ESC[$p`、`ESC[ !1`），这层不支持，吃掉别漏字。
                    ch in ' ' .. '/' -> Unit
                    else -> state = State.Text
                }
            }

            State.Osc -> when {
                code == 0x07 -> { applyOsc(); state = State.Text }
                code == ESC -> state = State.OscSt
                oscText.length < OSC_MAX -> oscText.append(code.toChar())
                else -> Unit
            }

            State.OscSt -> {
                val closed = code == '\\'.code
                if (closed) applyOsc()
                state = if (closed) State.Text else State.Esc
            }
        }
    }

    private fun applyOsc() {
        val payload = oscText.toString()
        val split = payload.indexOf(';')
        if (split <= 0) return
        // 0;/2; 是窗口标题，1; 是图标名，两个都只留标题栏用。
        val kind = payload.substring(0, split)
        if (kind == "0" || kind == "1" || kind == "2") windowTitle = payload.substring(split + 1)
    }

    // ── CSI ──

    /** 空的参数段留给调用方给默认值：`ESC[;5H` 的行号是 1 而不是 0。 */
    private fun csiParams(): List<Int?> {
        val raw = paramText.toString()
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { it.trim().toIntOrNull() }
    }

    private fun at(list: List<Int?>, index: Int, default: Int): Int = list.getOrNull(index) ?: default

    private fun dispatchCsi(final: Char) {
        val private = privateMarker.isNotEmpty()
        if (final == 'm') {
            // `ESC[>4;2m`（modifyOtherKeys）这类带前缀的 SGR 不是颜色，别当属性应用
            if (!private) applySgr()
            return
        }
        if (final == 'h' || final == 'l') {
            applyMode(csiParams(), final == 'h', private)
            return
        }
        if (private && final in "pqyxt{c") return
        val list = csiParams()
        when (final) {
            'H', 'f' -> {
                cursorRow = (at(list, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (at(list, 1, 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'A' -> moveRow(-at(list, 0, 1))
            'B' -> moveRow(at(list, 0, 1))
            'C' -> moveCol(at(list, 0, 1))
            'D' -> moveCol(-at(list, 0, 1))
            'E' -> { moveRow(at(list, 0, 1)); cursorCol = 0 }
            'F' -> { moveRow(-at(list, 0, 1)); cursorCol = 0 }
            'd' -> { cursorRow = (at(list, 0, 1) - 1).coerceIn(0, rows - 1); wrapPending = false }
            'G', '`' -> { cursorCol = (at(list, 0, 1) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'J' -> eraseDisplay(at(list, 0, 0))
            'K' -> eraseLine(at(list, 0, 0))
            'L' -> insertLines(at(list, 0, 1))
            'M' -> deleteLines(at(list, 0, 1))
            'P' -> deleteChars(at(list, 0, 1))
            '@' -> insertChars(at(list, 0, 1))
            'X' -> eraseChars(at(list, 0, 1))
            'S' -> scrollUp(at(list, 0, 1))
            'T' -> scrollDown(at(list, 0, 1))
            'r' -> {
                val top = (at(list, 0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (at(list, 1, rows) - 1).coerceIn(top, rows - 1)
                scrollTop = top
                scrollBottom = bottom
                cursorRow = top
                cursorCol = 0
            }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol; wrapPending = false }
            else -> Unit
        }
        if (CURSOR_MOVERS.indexOf(final) >= 0) snapCursor()
    }

    private fun applyMode(list: List<Int?>, on: Boolean, private: Boolean) {
        if (list.isEmpty()) return
        for (raw in list) {
            val code = raw ?: continue
            when {
                private && code == 25 -> cursorVisible = on
                private && (code == 1049 || code == 1047 || code == 47) -> switchAltScreen(on)
                private && code == 2004 -> bracketedPaste = on
                private && code == 7 -> autoWrap = on
                private && code == 1 -> applicationCursorKeys = on
                else -> Unit
            }
        }
    }

    /** 备用屏（`vim`、`less` 进的那个）：主屏整块留着，退出时连光标一起原样放回去。 */
    private fun switchAltScreen(on: Boolean) {
        if (on == (savedMain != null)) return
        if (on) {
            savedMain = screen
            savedMainCursorRow = cursorRow
            savedMainCursorCol = cursorCol
            screen = newScreen(cols, rows)
            cursorRow = 0
            cursorCol = 0
        } else {
            screen = savedMain ?: newScreen(cols, rows)
            savedMain = null
            cursorRow = savedMainCursorRow
            cursorCol = savedMainCursorCol
        }
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    private fun applySgr() {
        val list = if (paramText.isEmpty()) listOf(0) else csiParams()
        var i = 0
        while (i < list.size) {
            val code = list[i] ?: 0
            when {
                code == 0 -> style = TermCell()
                code in 1..9 -> style = withFlag(TermCell.flagOf(code), true)
                code in 21..29 -> {
                    val mask = TermCell.offFlagsOf(code)
                    if (mask != 0.toShort()) style = withFlag(mask, false)
                }
                code in 30..37 -> style = style.copy(fg = (code - 30).toShort())
                code == 39 -> style = style.copy(fg = TermCell.DEFAULT_COLOR)
                code in 40..47 -> style = style.copy(bg = (code - 40).toShort())
                code == 49 -> style = style.copy(bg = TermCell.DEFAULT_COLOR)
                code in 90..97 -> style = style.copy(fg = (code - 90 + 8).toShort())
                code in 100..107 -> style = style.copy(bg = (code - 100 + 8).toShort())
                code == 38 || code == 48 -> {
                    val color = extendedColor(list, i)
                    if (color == null) {
                        i = list.size
                    } else {
                        style = if (code == 38) style.copy(fg = color) else style.copy(bg = color)
                        i += if (list.getOrNull(i + 1) == 5) 3 else 5
                        continue
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    /** `38;5;N` 直接取 N，`38;2;r;g;b` 降到 256 色立方。 */
    private fun extendedColor(list: List<Int?>, at: Int): Short? = when (list.getOrNull(at + 1)) {
        5 -> (list.getOrNull(at + 2) ?: 0).coerceIn(0, 255).toShort()
        2 -> {
            val r = list.getOrNull(at + 2) ?: return null
            val g = list.getOrNull(at + 3) ?: return null
            val b = list.getOrNull(at + 4) ?: return null
            TermCell.rgbToCube(r, g, b).toShort()
        }
        else -> null
    }

    private fun withFlag(flag: Short, on: Boolean): TermCell {
        val flags = style.flags.toInt()
        val mask = flag.toInt()
        return style.copy(
            flags = (if (on) flags or mask else flags and mask.inv()).toShort()
        )
    }

    // ── 落格、滚动、擦除 ──

    private fun backspace() {
        if (cursorCol == 0) return
        cursorCol--
        if (screen[cursorRow].cells[cursorCol].continuation && cursorCol > 0) cursorCol--
        wrapPending = false
    }

    private fun putCodePoint(code: Int) {
        if (TermWidth.isCombining(code)) return
        if (autoWrap && wrapPending) newline()
        val wide = TermWidth.isWide(code)
        // 最后一个格子放不下宽字符的两列，先折行再写（xterm 就是这么处理的）
        if (wide && cursorCol >= cols - 1) {
            cursorCol = 0
            if (cursorRow < scrollBottom) cursorRow++ else scrollUp(1)
            wrapPending = false
        }
        screen[cursorRow].put(cursorCol, code, style, wide)
        advanceCol(if (wide) 2 else 1)
    }

    /** 写完之后前进 [step] 列；顶到最后一列时先挂 wrapPending（延迟换行），下一格才真的折。 */
    private fun advanceCol(step: Int) {
        if (cursorCol + step < cols) {
            cursorCol += step
            wrapPending = false
        } else {
            cursorCol = cols - 1
            wrapPending = autoWrap
        }
    }

    private fun newline() {
        wrapPending = false
        cursorCol = 0
        if (cursorRow < scrollBottom) cursorRow++ else scrollUp(1)
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            val dropped = screen[scrollTop]
            if (savedMain == null) {
                if (scrollback.size >= scrollbackLimit) scrollback.removeFirst()
                scrollback.addLast(dropped.snapshot())
            }
            for (i in scrollTop until scrollBottom) screen[i].copyFrom(screen[i + 1])
            screen[scrollBottom].blank(style.bg)
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            for (i in scrollBottom downTo scrollTop + 1) screen[i].copyFrom(screen[i - 1])
            screen[scrollTop].blank(style.bg)
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (i in scrollBottom downTo cursorRow + 1) screen[i].copyFrom(screen[i - 1])
            screen[cursorRow].blank(style.bg)
        }
        cursorCol = 0
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (i in cursorRow until scrollBottom) screen[i].copyFrom(screen[i + 1])
            screen[scrollBottom].blank(style.bg)
        }
        cursorCol = 0
    }

    private fun insertChars(count: Int) {
        val line = screen[cursorRow]
        val n = count.coerceIn(1, cols - cursorCol)
        for (i in cols - 1 downTo cursorCol + n) line.cells[i].take(line.cells[i - n])
        for (i in cursorCol until min(cursorCol + n, cols)) line.fill(i, style)
        line.normalize()
    }

    private fun deleteChars(count: Int) {
        val line = screen[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (i in cursorCol until cols - n) line.cells[i].take(line.cells[i + n])
        for (i in max(0, cols - n) until cols) line.fill(i, style)
        line.normalize()
    }

    private fun eraseChars(count: Int) {
        val n = count.coerceAtLeast(1)
        val line = screen[cursorRow]
        for (i in cursorCol until min(cursorCol + n, cols)) line.fill(i, style)
        line.normalize()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLineFrom(cursorCol)
                for (i in cursorRow + 1 until rows) screen[i].blank(style.bg)
            }
            1 -> {
                for (i in 0 until cursorRow) screen[i].blank(style.bg)
                eraseLineTo(cursorCol)
            }
            else -> {
                for (i in 0 until rows) screen[i].blank(style.bg)
                // 面板上 `clear` 一般是 `ESC[2J` 加 `ESC[3J`，这里两种都连带把回滚清掉，
                // 免得 `top` / `htop` 反复刷整屏时把历史堆成几千行废话。
                if (mode >= 2) scrollback = ArrayDeque()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseLineFrom(cursorCol)
            1 -> eraseLineTo(cursorCol)
            else -> screen[cursorRow].blank(style.bg)
        }
    }

    private fun eraseLineFrom(from: Int) {
        val line = screen[cursorRow]
        for (i in from until cols) line.fill(i, style)
        line.normalize()
    }

    private fun eraseLineTo(to: Int) {
        val line = screen[cursorRow]
        for (i in 0..min(to, cols - 1)) line.fill(i, style)
        line.normalize()
    }

    private fun moveRow(delta: Int) {
        cursorRow = (cursorRow + delta).coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun moveCol(delta: Int) {
        cursorCol = (cursorCol + delta).coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun newScreenInto() {
        screen = newScreen(cols, rows)
        savedMain = null
        scrollback = ArrayDeque()
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    private fun newScreen(columns: Int, lineCount: Int): Array<TermRow> =
        Array(lineCount) { TermRow(Array(columns) { TermCell() }) }

    private fun copyIntoNewScreen(old: Array<TermRow>, columns: Int, lineCount: Int): Array<TermRow> {
        val next = newScreen(columns, lineCount)
        for (i in 0 until min(old.size, lineCount)) {
            for (j in 0 until min(old[i].size, columns)) next[i].cells[j].take(old[i].cells[j])
            next[i].touched = old[i].touched
            next[i].normalize()
        }
        return next
    }

    /** 光标落在了宽字符的右半边：把它挪回左半边，免得程序按列定位时写歪。 */
    private fun snapCursor() {
        val cell = screen.getOrNull(cursorRow)?.cells?.getOrNull(cursorCol) ?: return
        if (cell.continuation && cursorCol > 0) cursorCol -= 1
    }

    private enum class State { Text, Esc, Charset, Csi, Osc, OscSt }

    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val DEFAULT_SCROLLBACK = 2000
        const val MIN_COLS = 20
        const val MIN_ROWS = 3
        const val MAX_COLS = 260
        const val MAX_ROWS = 200
        const val ESC = 0x1B
        const val TAB = 8
        const val OSC_MAX = 512

        /** 会把光标摆到某一列的那些 CSI，落点如果压在宽字符右半边要挪回来。 */
        const val CURSOR_MOVERS = "HfABCDEFG`du"
    }
}

/**
 * 流式 UTF-8 解码：一包字节可能把一个汉字劈成两半，尾巴留在内部等下一包。
 */
class Utf8Stream {
    private var pending = ByteArray(0)

    fun reset() {
        pending = ByteArray(0)
    }

    fun decode(bytes: ByteArray): String {
        val all = if (pending.isEmpty()) bytes else pending + bytes
        val usable = completePrefixLength(all)
        pending = if (usable < all.size) all.copyOfRange(usable, all.size) else ByteArray(0)
        if (usable == 0) return ""
        return String(all, 0, usable, Charsets.UTF_8)
    }

    /** 从尾部找一个「起点在最后四字节里的多字节序列」，长度不够就先不解。 */
    private fun completePrefixLength(bytes: ByteArray): Int {
        for (back in 1..min(4, bytes.size)) {
            val index = bytes.size - back
            val b = bytes[index].toInt() and 0xFF
            if (b and 0xC0 != 0x80) {
                val needed = when {
                    b and 0x80 == 0 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                return if (needed > back) index else bytes.size
            }
        }
        return bytes.size
    }
}
