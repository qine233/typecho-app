package noline.zhouzhou.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛玻璃里唯一能脱离 Android 画的数学：盒式模糊。
 * 卡片背景那层好不好看得在桌面上看，但「有没有真的糊开」「半透明的边会不会往黑里掉」
 * 这两件是算术问题，算术问题就该在单测里钉死。
 */
class WidgetGlassBlurTest {

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun channel(pixel: Int, shift: Int) = (pixel shr shift) and 0xFF

    private fun field(width: Int, height: Int, paint: (x: Int, y: Int) -> Int) =
        IntArray(width * height) { index -> paint(index % width, index / width) }

    @Test
    fun `a flat color survives any amount of blurring`() {
        val flat = argb(255, 120, 200, 40)
        val src = field(12, 8) { _, _ -> flat }
        val out = boxBlurArgb(src, 12, 8, radius = 3, passes = 3)
        assertEquals(List(12 * 8) { flat }, out.toList())
    }

    @Test
    fun `a hard edge turns into a ramp`() {
        // 左半边纯白、右半边纯黑：糊过之后紧贴分界线的那一列必须是中间灰，
        // 而且离分界线越远越靠回各自那头——这就是「化开」，不是「抹平」。
        val width = 24
        val height = 4
        val white = argb(255, 255, 255, 255)
        val black = argb(255, 0, 0, 0)
        val src = field(width, height) { x, _ -> if (x < width / 2) white else black }
        val out = boxBlurArgb(src, width, height, radius = 2, passes = 3)

        fun redAt(x: Int) = channel(out[x], 16)
        // 半径 2、三次盒式：分界那两列落在中间灰附近，越往外越回到各自那头。
        assertTrue("分界左侧应该是亮灰，实测 ${redAt(width / 2 - 1)}", redAt(width / 2 - 1) in 140..152)
        assertTrue("分界右侧应该是暗灰，实测 ${redAt(width / 2)}", redAt(width / 2) in 100..115)
        assertTrue(redAt(width / 2 - 1) > redAt(width / 2))
        assertTrue("离分界够远的地方得回到纯白，实测 ${redAt(0)}", redAt(0) > 250)
        assertTrue("离分界够远的地方得回到纯黑，实测 ${redAt(width - 1)}", redAt(width - 1) < 5)
        (6 until 18).forEach { x ->
            assertTrue("从左到右得一路暗下去，$x 处却是 ${redAt(x)} vs ${redAt(x + 1)}", redAt(x) >= redAt(x + 1))
        }
    }

    @Test
    fun `one bright pixel stains its neighbourhood instead of vanishing`() {
        // 壁纸是不透明的，所以这里也用不透明的黑底：一个亮点糊完应该是个钟形——
        // 中心最亮、旁边次之、够远的角落还是黑的。糊到整张图一起发白就说明半径给大了。
        val size = 15
        val black = argb(255, 0, 0, 0)
        val src = field(size, size) { x, y ->
            if (x == size / 2 && y == size / 2) argb(255, 255, 255, 255) else black
        }
        val out = boxBlurArgb(src, size, size, radius = 2, passes = 3)
        fun redAt(x: Int, y: Int) = channel(out[y * size + x], 16)
        val center = redAt(size / 2, size / 2)
        val side = redAt(size / 2 + 1, size / 2)
        val far = redAt(0, 0)
        assertTrue("中心该比旁边亮：$center vs $side", center > side)
        assertTrue("旁边该比角落亮：$side vs $far", side > far)
        assertEquals(0, far)
    }

    @Test
    fun `mixing weights by alpha so a translucent edge does not darken`() {
        // 一半透明黑、一半不透明纯白。按通道直接平均会算出中灰，
        // 按 alpha 加权才知道那 255 全来自白色那一侧，颜色应该还是白。
        val width = 12
        val src = field(width, 2) { x, _ -> if (x < width / 2) 0 else argb(255, 255, 255, 255) }
        val out = boxBlurArgb(src, width, 2, radius = 1, passes = 1)
        val seam = out[width / 2]
        assertEquals(255, channel(seam, 16))
        assertEquals(255, channel(seam, 8))
        assertEquals(255, channel(seam, 0))
        assertTrue("alpha 得按窗口平均，实测 ${channel(seam, 24)}", channel(seam, 24) in 85..254)
    }

    @Test
    fun `nothing to blur stays untouched`() {
        val src = field(4, 4) { x, y -> argb(255, x * 20, y * 30, 7) }
        assertEquals(src.toList(), boxBlurArgb(src, 4, 4, radius = 0, passes = 3).toList())
        assertEquals(src.toList(), boxBlurArgb(src, 4, 4, radius = 2, passes = 0).toList())
        assertEquals(emptyList<Int>(), boxBlurArgb(IntArray(0), 0, 0, radius = 2, passes = 3).toList())
    }
}
