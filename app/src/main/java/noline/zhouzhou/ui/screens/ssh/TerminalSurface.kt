package noline.zhouzhou.ui.screens.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import noline.zhouzhou.data.model.TermCell
import noline.zhouzhou.data.model.TermRow
import noline.zhouzhou.data.model.TerminalScreen
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * xterm 的 256 色表。
 *
 * 前 16 色偏暖：宝塔那台机器上 `ls --color`、`vim` 的语法着色、`htop` 的进度条全靠这
 * 16 个，标准 xterm 的红绿摆在暖底上扎眼。中间 216 色和末 24 阶灰照标准算，
 * 服务器那边按号码发过来的颜色才不会串位。
 */
object TerminalPalette {
    private val Base = intArrayOf(
        0xFF23252D.toInt(), 0xFFD06A6E.toInt(), 0xFF8FBF6A.toInt(), 0xFFD9A34A.toInt(),
        0xFF6AA5D8.toInt(), 0xFFBE74C6.toInt(), 0xFF5CB3BE.toInt(), 0xFFB9BFCB.toInt(),
        0xFF5A5F6B.toInt(), 0xFFE58A8D.toInt(), 0xFFA8D487.toInt(), 0xFFEFC978.toInt(),
        0xFF8CC3F0.toInt(), 0xFFD99AE0.toInt(), 0xFF7FD3DE.toInt(), 0xFFF2F4F8.toInt()
    )

    val Colors: IntArray = IntArray(256).also { table ->
        for (i in 0 until 16) table[i] = Base[i]
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            table[16 + 36 * r + 6 * g + b] =
                0xFF000000.toInt() or (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
        }
        for (i in 0 until 24) {
            val gray = 8 + i * 10
            table[232 + i] = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
    }
}

/**
 * 一列多宽、一行多高。
 *
 * 界面先按这套度量算出「这块地方摆得下几列几行」，再把行列数报给 PTY；
 * 服务器按同一套宽度折行，屏幕上的框线才对得齐。
 */
class TerminalMetrics(textSizePx: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = textSizePx
    }

    /** 等宽字体里 'M' 的前进宽度就是列宽。 */
    val advance: Float = max(1f, paint.measureText("M"))
    val ascent: Float = paint.fontMetrics.ascent
    val descent: Float = paint.fontMetrics.descent

    /** 行高留一点行距，`htop` 这类满屏刷新的东西挤成一团就没法读了。 */
    val lineHeight: Float = max(1f, (descent - ascent) * 1.16f)

    fun columnsFor(widthPx: Float): Int = (widthPx / advance).toInt().coerceAtLeast(1)

    fun rowsFor(heightPx: Float): Int = (heightPx / lineHeight).toInt().coerceAtLeast(1)
}

@Composable
fun rememberTerminalMetrics(textSize: TextUnit, density: Density): TerminalMetrics {
    val px = with(density) { textSize.toPx() }
    return remember(px) { TerminalMetrics(px) }
}

/**
 * 终端画布：把 [TerminalScreen] 的格子按等宽网格画出来，往上拖能翻回滚区。
 *
 * 之所以自己画 View 而不是拿 Compose 的 Text 逐行摆：`vim`、`htop` 的框线要求每一列
 * 严格等宽，汉字和 emoji 这类全角字要占满两列，交给文本排版做不到。
 */
class TerminalSurface(context: Context) : View(context) {

    var model: TerminalScreen? = null

    /** 屏幕内容的改动次数；界面每收到一个新值就重画一次。 */
    var revision: Long = Long.MIN_VALUE
        set(value) {
            if (field == value) return
            field = value
            // 正在看历史的时候别把人拽回最新一行。
            if (stickToBottom) scrollOffset = 0
            invalidate()
        }

    var textSizePx: Float = 34f
        set(value) {
            if (field == value) return
            field = value
            metrics = TerminalMetrics(value)
            textPaint.textSize = value
            invalidate()
        }

    var backgroundArgb: Int = 0xFF15171C.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var foregroundArgb: Int = 0xFFD8DEE9.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 光标那块的颜色，跟着 App 的强调色走。 */
    var cursorArgb: Int = 0xFF8A9BFF.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 往上翻了多少行；0 就是贴着最新输出。 */
    var scrollOffset: Int = 0
        set(value) {
            val capped = value.coerceIn(0, model?.scrollbackSize ?: 0)
            if (field == capped) return
            field = capped
            stickToBottom = capped == 0
            onScrolled?.invoke(capped)
            invalidate()
        }

    var stickToBottom: Boolean = true
        private set

    var onScrolled: ((Int) -> Unit)? = null

    /** 单击画布：界面拿它切换软键盘。 */
    var onTap: (() -> Unit)? = null

    private var metrics = TerminalMetrics(textSizePx)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = textSizePx
    }
    private val block = RectF()
    private val run = StringBuilder(128)
    private val single = StringBuilder(2)

    private var pendingLines = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 手指往下拖看更早的内容，攒够一整行才真的翻。
            // onScroll 的 distanceY 是「手指上移为正」，和滚动偏移的方向相反，这里要取负。
            pendingLines -= distanceY / metrics.lineHeight
            val lines = pendingLines.roundToInt()
            if (lines != 0) {
                pendingLines -= lines
                scrollOffset += lines
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }
    })

    init {
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    fun scrollToBottom() {
        scrollOffset = 0
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundArgb)
        val screen = model ?: return
        val columns = max(1, (width / metrics.advance).toInt())
        val rows = max(1, (height / metrics.lineHeight).toInt())

        val total = screen.scrollbackSize + screen.rows
        val windowStart = max(0, total - rows - scrollOffset)
        for (y in 0 until rows) {
            val index = windowStart + y
            if (index >= total) break
            rowAt(screen, index)?.let { drawRow(canvas, it, y, columns) }
        }
        if (scrollOffset == 0) drawCursor(canvas, screen, windowStart, rows)
    }

    /** 回滚区（按时间正序）接在主屏上面，视口永远填满。 */
    private fun rowAt(screen: TerminalScreen, contentIndex: Int): TermRow? =
        if (contentIndex < screen.scrollbackSize) {
            screen.scrollbackRow(screen.scrollbackSize - 1 - contentIndex)
        } else {
            screen.rowAt(contentIndex - screen.scrollbackSize)
        }

    private fun drawRow(canvas: Canvas, row: TermRow, y: Int, columns: Int) {
        val top = y * metrics.lineHeight
        val bottom = top + metrics.lineHeight
        val limit = min(row.cells.size, columns)

        // 一遍画底，一遍画字：底色按段铺（连空格和全角占位都盖到），字按能连成一串的等宽段画。
        var segmentStart = 0
        var segmentBg = bgOf(row.cells[0])
        for (i in 1..limit) {
            val current = if (i < limit) bgOf(row.cells[i]) else Int.MIN_VALUE
            if (current != segmentBg) {
                if (segmentBg != backgroundArgb) {
                    block.set(segmentStart * metrics.advance, top, i * metrics.advance, bottom)
                    fillPaint.color = segmentBg
                    canvas.drawRect(block, fillPaint)
                }
                segmentStart = i
                segmentBg = current
            }
        }

        var i = 0
        while (i < limit) {
            val cell = row.cells[i]
            if (cell.continuation || isBlank(cell) || (cell.flags.toInt() and TermCell.FlagInvisible.toInt()) != 0) {
                i++
                continue
            }
            // 全角字占两列，混进一串里会让后面所有字都偏半格，单独画。
            if (cell.wide || cell.ch2 != 0.toChar()) {
                single.setLength(0)
                single.append(cell.ch)
                if (cell.ch2 != 0.toChar()) single.append(cell.ch2)
                drawGlyph(canvas, single, i * metrics.advance, top, cell)
                i += if (cell.wide) 2 else 1
                continue
            }
            run.setLength(0)
            run.append(cell.ch)
            var j = i + 1
            while (j < limit) {
                val next = row.cells[j]
                if (next.continuation || next.wide || isBlank(next) || next.ch2 != 0.toChar() ||
                    !next.sameStyle(cell)
                ) break
                run.append(next.ch)
                j++
            }
            drawGlyph(canvas, run, i * metrics.advance, top, cell)
            i = j
        }
    }

    /** 字在行盒里竖直居中：上下各留一半余量，基线落在下面那条线上。 */
    private fun baseline(top: Float): Float =
        top + (metrics.lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent

    private fun drawGlyph(canvas: Canvas, text: CharSequence, left: Float, top: Float, cell: TermCell) {
        applyStyle(cell)
        canvas.drawText(text, 0, text.length, left, baseline(top), textPaint)
    }

    private fun applyStyle(cell: TermCell) {
        val flags = cell.flags.toInt()
        var paintFlags = Paint.ANTI_ALIAS_FLAG
        if ((flags and TermCell.FlagBold.toInt()) != 0) paintFlags = paintFlags or Paint.FAKE_BOLD_TEXT_FLAG
        if ((flags and TermCell.FlagUnderline.toInt()) != 0) paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        if ((flags and TermCell.FlagStrike.toInt()) != 0) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        textPaint.flags = paintFlags
        textPaint.textSkewX = if ((flags and TermCell.FlagItalic.toInt()) != 0) -0.22f else 0f
        val color = fgOf(cell)
        textPaint.color =
            if ((flags and TermCell.FlagFaint.toInt()) != 0) blur(color) else color
    }

    /** 暗显（SGR 2）：只把字色压到五成半，不碰底色。 */
    private fun blur(argb: Int): Int {
        val alpha = (Color.alpha(argb) * 0.55f).roundToInt().coerceAtLeast(1)
        return Color.argb(
            alpha,
            Color.red(argb),
            Color.green(argb),
            Color.blue(argb)
        )
    }

    private fun drawCursor(canvas: Canvas, screen: TerminalScreen, windowStart: Int, rows: Int) {
        if (!screen.cursorVisible) return
        val cell = screen.cell(screen.cursorRow, screen.cursorCol) ?: return
        val y = screen.scrollbackSize + screen.cursorRow - windowStart
        if (y !in 0 until rows) return
        val left = screen.cursorCol * metrics.advance
        val top = y * metrics.lineHeight
        val columns = if (cell.wide) 2 else 1
        block.set(left, top, left + columns * metrics.advance, top + metrics.lineHeight)
        fillPaint.color = cursorArgb
        canvas.drawRect(block, fillPaint)
        if (!isBlank(cell)) {
            single.setLength(0)
            single.append(cell.ch)
            if (cell.ch2 != 0.toChar()) single.append(cell.ch2)
            // 光标块上的字反色：块用强调色，字就换成底色，深浅自然对得上
            textPaint.flags = Paint.ANTI_ALIAS_FLAG
            textPaint.textSkewX = 0f
            textPaint.color = backgroundArgb
            canvas.drawText(single, 0, single.length, left, baseline(top), textPaint)
        }
    }

    private fun isBlank(cell: TermCell): Boolean = cell.ch == ' ' && cell.ch2 == 0.toChar()

    private fun fgOf(cell: TermCell): Int {
        val reverse = (cell.flags.toInt() and TermCell.FlagReverse.toInt()) != 0
        return if (reverse) color(cell.bg, backgroundArgb) else color(cell.fg, foregroundArgb)
    }

    private fun bgOf(cell: TermCell): Int {
        val reverse = (cell.flags.toInt() and TermCell.FlagReverse.toInt()) != 0
        return if (reverse) color(cell.fg, backgroundArgb) else color(cell.bg, backgroundArgb)
    }

    private fun color(index: Short, fallback: Int): Int =
        if (index < 0) fallback else TerminalPalette.Colors[index.toInt().coerceIn(0, 255)]

    private val fillPaint = Paint()
}
