package noline.zhouzhou.ui.widget

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.R

/**
 * 桌面卡片的玻璃底：先弄到一张「壁纸的颜色」，真的模糊掉，再叠上原来那层半透明色调、
 * 顶部高光和一圈描边，画成一张位图当根背景。
 *
 * 为什么只能这么绕：RemoteViews 里没有模糊这条路（`android.jar` 里 `RemoteViews` 一个
 * blur 方法都没有，Glance 1.1.1 也只有 layer-list 能叠），桌面进程愿不愿意给卡片后面糊一层
 * 是它的自由，App 侧要模糊就只能自己把糊好的图递进去。颜色来源分两种，能拿到哪种算哪种：
 * 1. 壁纸像素——部分系统让第三方进程直接读 `WallpaperManager.drawable`，读到就按卡片宽高
 *    居中裁一块糊掉。这条最像真玻璃，但 17 上实测被拦（要 `READ_EXTERNAL_STORAGE`），
 *    所以不能当唯一出路；
 * 2. 壁纸取色——`getWallpaperColors` 不要权限，拿到主/次/第三色，按壁纸的排布画成色块场，
 *    再走同一条模糊。糊完看不出块，只剩「这张壁纸是什么颜色」这件事。
 * 两种都是壁纸的快照：壁纸换了会重画（按 `wallpaperId` 判），卡片挪了位置不跟，
 * 因为小组件不知道自己被摆在屏幕的哪儿。
 */

/**
 * 出图最长的一边。再大就是白占 binder 预算——RemoteViews 整棵树只有 1MB 可用，
 * 而模糊过后的图本来也没细节可留。
 */
private const val RenderSide = 360

/**
 * 采样只采这么小。磨砂玻璃要的是「壁纸只剩大块颜色」，按卡片实际尺寸采样再糊，
 * 边缘那道分界还是看得出来；先在 120 这一档糊开，再双线性放大到出图尺寸，
 * 出来的才是化开的颜色而不是拉模糊的照片。
 */
private const val SampleSide = 120

/** 三次盒式近似高斯：一到两次还能看出方块，三次之后边缘就化开了。 */
private const val BlurPasses = 3

private val glassCache = LruCache<String, Bitmap>(8)

/**
 * 取一张和这张卡片般配的毛玻璃。取不到才回 null，调用方退回 [R.drawable.widget_card_bg]
 * 那张纯半透明 layer-list——注意那条退路上的圆角外面是透明的，桌面往后垫什么都挡不住，
 * 所以能出图就一定得出图。
 */
internal suspend fun widgetGlassBackground(context: Context, id: GlanceId): Bitmap? =
    withContext(Dispatchers.Default) {
        runCatching { buildGlass(context, id) }.getOrNull()
    }

private suspend fun buildGlass(context: Context, id: GlanceId): Bitmap? {
    val card = GlanceAppWidgetManager(context).getAppWidgetSizes(id)
        .maxByOrNull { it.width.value * it.height.value } ?: return null
    val cardWidthDp = card.width.value
    val cardHeightDp = card.height.value
    if (cardWidthDp <= 0f || cardHeightDp <= 0f) return null

    val manager = WallpaperManager.getInstance(context)
    // 动态壁纸的 `drawable` 给的是那张应用的预览图而不是当前画面，糊出来是一张贴图；
    // 它的颜色倒是不要权限就能拿到，所以只走取色那条。
    val live = manager.wallpaperInfo != null
    val wallpaperId = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(0)

    val aspect = cardWidthDp / cardHeightDp
    val sampleWidth = sideOf(SampleSide, aspect, horizontal = true)
    val sampleHeight = sideOf(SampleSide, aspect, horizontal = false)
    val renderWidth = sideOf(RenderSide, aspect, horizontal = true)
    val renderHeight = sideOf(RenderSide, aspect, horizontal = false)

    val fromPixels = if (live) null else runCatching { manager.drawable }.getOrNull()
    val key = "${if (fromPixels == null) "c" else "p"}$wallpaperId-${renderWidth}x$renderHeight"
    glassCache.get(key)?.let { return it }

    val sampled = fromPixels?.let { sampleCenterCrop(it, sampleWidth, sampleHeight) }
        ?: paletteSample(manager, sampleWidth, sampleHeight)
        ?: return null
    val blurred = blurSampled(sampled, radiusFor(maxOf(sampleWidth, sampleHeight)))
    val upscaled = Bitmap.createScaledBitmap(blurred, renderWidth, renderHeight, true)
    val glass = paintCard(upscaled, context, renderHeight / cardHeightDp)
    sampled.recycle()
    if (blurred !== sampled) blurred.recycle()
    if (upscaled !== blurred) upscaled.recycle()
    glassCache.put(key, glass)
    return glass
}

private fun sideOf(longSide: Int, aspect: Float, horizontal: Boolean): Int = when {
    aspect >= 1f && horizontal -> longSide
    aspect >= 1f -> (longSide / aspect).toInt()
    horizontal -> (longSide * aspect).toInt()
    else -> longSide
}.coerceAtLeast(16)

/** 模糊半径按采样尺寸给：这一档上糊掉三成宽度，放大到卡片就正好是「只剩颜色」。 */
private fun radiusFor(sampleSide: Int) = (sampleSide / 3).coerceIn(2, 48)

private fun sampleCenterCrop(drawable: Drawable, width: Int, height: Int): Bitmap? {
    val sourceWidth = drawable.intrinsicWidth
    val sourceHeight = drawable.intrinsicHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // 等比铺满再居中裁掉多出的一边，和桌面把壁纸摆在屏幕上的算法是一回事。
    val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
    canvas.translate((width - sourceWidth * scale) / 2f, (height - sourceHeight * scale) / 2f)
    canvas.scale(scale, scale)
    drawable.setBounds(0, 0, sourceWidth, sourceHeight)
    drawable.draw(canvas)
    return bitmap
}

/**
 * 拿不到壁纸像素时的底：壁纸那三个取色点按「左上偏次色、右下偏第三色」铺开。
 * 位置是固定的，反正下一步就糊掉——要的是颜色构成，不是构图。
 *
 * 一律从白打底、颜色只压七到五成：壁纸本身可能很深，原样铺上去再叠那层半透明白，
 * 卡片就成了块灰板，字压在上面发闷。打底是白，出来的才是「带壁纸颜色的毛玻璃」。
 */
private fun paletteSample(manager: WallpaperManager, width: Int, height: Int): Bitmap? {
    val colors = runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
    val primary = colors?.primaryColor ?: return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.drawColor(fade(primary.toArgb(), 0.7f))
    drawBlob(canvas, width, height, colors.secondaryColor?.let { fade(it.toArgb(), 0.5f) }, 0.24f, 0.3f, 0.8f)
    drawBlob(canvas, width, height, colors.tertiaryColor?.let { fade(it.toArgb(), 0.5f) }, 0.82f, 0.78f, 0.7f)
    return bitmap
}

/** 只改透明度，不动色相——打底是白，压几成就淡成一层颜色。 */
private fun fade(color: Int, factor: Float): Int =
    ColorUtils.setAlphaComponent(color, (255 * factor).toInt().coerceIn(0, 255))

private fun drawBlob(canvas: Canvas, width: Int, height: Int, color: Int?, x: Float, y: Float, reach: Float) {
    if (color == null) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = maxOf(width, height) * reach
    paint.shader = RadialGradient(
        width * x, height * y, radius,
        color, color and 0x00FFFFFF, Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
}

private fun blurSampled(sampled: Bitmap, radius: Int): Bitmap {
    val width = sampled.width
    val height = sampled.height
    val pixels = IntArray(width * height)
    sampled.getPixels(pixels, 0, width, 0, 0, width, height)
    val blurred = boxBlurArgb(pixels, width, height, radius, BlurPasses)
    return Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * 出图一律**铺满不透明**：先整张铺一层没调过色的壁纸（圆角外面留的就是它），
 * 再把抠好圆角的卡片本体盖上去。
 *
 * 为什么不能像以前那样把圆角外面抠成透明：桌面垫在卡片底下的那块板子是从**外面**盖不上来的，
 * 我们这里透明一分，它就露一分。澎湃垫的是直角白板，于是「圆角」变成了「白板的一圈直角边」。
 * 只要缺口是不透明的壁纸，垫谁都垫不出来——这一条不看桌面的脸色。
 * 代价是那一圈约 6dp 宽的角是壁纸的**快照**（居中裁的那块），不是卡片底下真正的壁纸；
 * 反正糊过、又只剩一条边，肉眼分不出。
 */
private fun paintCard(source: Bitmap, context: Context, pxPerDp: Float): Bitmap {
    val glass = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(glass)
    canvas.drawBitmap(source, null, RectF(0f, 0f, glass.width.toFloat(), glass.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    val card = paintCardLayer(source, context, pxPerDp)
    canvas.drawBitmap(card, 0f, 0f, null)
    card.recycle()
    return glass
}

/**
 * 卡片本体那一层：糊好的颜色 + 原来那四层，最后用 CLEAR 抠出圆角（所以这一层圆角外是透明的，
 * 由 [paintCard] 铺到壁纸底上）。
 * [pxPerDp] 是「一位 dp 在这张出图上占几个像素」，圆角和描边都得按它换算，
 * 否则出图被 ImageView 放大之后，22dp 的圆角会大得离谱。
 */
private fun paintCardLayer(source: Bitmap, context: Context, pxPerDp: Float): Bitmap {
    val glass = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(glass)
    val bounds = RectF(0f, 0f, glass.width.toFloat(), glass.height.toFloat())
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    canvas.drawBitmap(source, null, bounds, paint)
    // 上亮下暗的色调：还是原来那几个颜色值，只是这次压在糊过的壁纸上而不是透明底上。
    paint.shader = LinearGradient(
        0f, bounds.top, 0f, bounds.bottom,
        ContextCompat.getColor(context, R.color.widget_card_bg_top),
        ContextCompat.getColor(context, R.color.widget_card_bg),
        Shader.TileMode.CLAMP
    )
    canvas.drawRect(bounds, paint)
    paint.shader = LinearGradient(
        0f, 0f, 0f, glass.height * 0.45f,
        ContextCompat.getColor(context, R.color.widget_glass_sheen), 0x00FFFFFF,
        Shader.TileMode.CLAMP
    )
    canvas.drawRect(bounds, paint)
    paint.shader = null

    val corner = 22f * pxPerDp
    // 圆角靠 CLEAR 抠掉圆角**外面**那一圈。
    //
    // 不能用更直觉的 DST_IN：实测在位图 canvas 上它是一个彻底的空操作——`PorterDuffXfermode(DST_IN)`、
    // `BlendMode.DST_IN`、以及整段包进 `saveLayer` 再画，三种写法画完之后整张图一个透明像素都没有
    // （同一张图上 `Mode.CLEAR` 却能真的写穿）。方向反过来，把「非圆角区域」CLEAR 掉才成立。
    // 直接 `clipPath` 那条也不行：`clipPath` 不带抗锯齿，而且它挡不住 `drawColor`。
    val outside = Path().apply {
        addRect(bounds, Path.Direction.CW)
        op(roundRectPath(bounds, corner), Path.Op.DIFFERENCE)
    }
    val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    canvas.drawPath(outside, mask)

    // 描边往里挤半个线宽，正好落在卡片内侧，不然它压在已经透明的圆角外面，什么都看不见。
    val stroke = 1f * pxPerDp
    val inset = RectF(stroke / 2f, stroke / 2f, glass.width - stroke / 2f, glass.height - stroke / 2f)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.color = ContextCompat.getColor(context, R.color.widget_card_border)
    paint.xfermode = null
    canvas.drawPath(roundRectPath(inset, (corner - stroke / 2f).coerceAtLeast(0f)), paint)
    return glass
}

private fun roundRectPath(bounds: RectF, radius: Float): Path = Path().apply {
    addRoundRect(bounds, radius, radius, Path.Direction.CW)
}

/**
 * 桌面卡片的根。两种材质叠在一起：底下那张 [R.drawable.widget_card_bg] 是卡片自己的底，
 * 上面铺满的是按这张壁纸现糊的毛玻璃（[widgetGlassBackground]），拿不到壁纸就只留底那张。
 *
 * 为什么非得是「底 + 子图」两层，而不是直接把位图当根背景：位图当不了资源 id，Glance 会把
 * `background(ImageProvider(bitmap))` 挪进一个子 ImageView（拆 AAR 看到
 * `transformBackgroundImageAndActionRipple` 只处理 `AndroidResourceImageProvider`，走
 * `RemoteViewsCompat.setViewBackgroundResource` 留在 View 自己身上）。所以位图只能当子图。
 *
 * [appWidgetBackground] 还是要挂着：它把这一层标记成桌面认背景用的那个 `android.R.id.background`
 * （拆 AAR 看到 `LayoutSelectionKt.insertViewInternal` 里填的 viewId 是 0x01020000）。
 * 但**别指望它单独能挡住白板**——Glance 的组合根不是宿主看到的那个根：
 * `RemoteViewsTranslatorKt.translateComposition` 先拿 `EmittableSizeBox` 的 modifier 调
 * `createRootView`，那个方法只从链上摘 Width/Height 两条、其余不看，然后把我们的 Box 塞进
 * `root_match_match.xml` 里那个 ViewStub。最终树是
 * `AppWidgetHostView → FrameLayout(rootView，无背景) → 我们的 Box(带 id)`，
 * 桌面只要看一眼 `getChildAt(0).getBackground()` 就还是判定「这张卡没底」。
 * 真正兜住的是 [paintCard] 那张**不透明**的图：底下垫什么都露不出来。
 */
@Composable
internal fun WidgetCard(
    glass: Bitmap?,
    contentPaddingHorizontal: Dp,
    contentPaddingVertical: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_card_bg))
    ) {
        if (glass != null) {
            Image(
                provider = ImageProvider(glass),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = contentPaddingHorizontal, vertical = contentPaddingVertical),
            content = content
        )
    }
}

/**
 * 可分离的盒式模糊，三次之后接近高斯。按 alpha 加权重算颜色（先乘后除），
 * 不然半透明的地方会把旁边的颜色往黑里拖——色调那层渐变本身就是半透明的。
 */
internal fun boxBlurArgb(
    src: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int
): IntArray {
    if (width <= 0 || height <= 0 || radius <= 0 || passes <= 0) return src.copyOf()
    val current = src.copyOf()
    val scratch = IntArray(current.size)
    repeat(passes) {
        boxPass(current, scratch, width, height, radius, horizontal = true)
        boxPass(scratch, current, width, height, radius, horizontal = false)
    }
    return current
}

private fun boxPass(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean
) {
    val lineCount = if (horizontal) height else width
    val lineLength = if (horizontal) width else height
    val step = if (horizontal) 1 else width
    for (line in 0 until lineCount) {
        val start = if (horizontal) line * width else line
        for (offset in 0 until lineLength) {
            val from = (offset - radius).coerceAtLeast(0)
            val to = (offset + radius).coerceAtMost(lineLength - 1)
            var sumAlpha = 0L
            var sumRed = 0L
            var sumGreen = 0L
            var sumBlue = 0L
            for (sample in from..to) {
                val pixel = src[start + sample * step]
                val alpha = (pixel ushr 24) and 0xFF
                sumAlpha += alpha
                sumRed += ((pixel shr 16) and 0xFF) * alpha
                sumGreen += ((pixel shr 8) and 0xFF) * alpha
                sumBlue += (pixel and 0xFF) * alpha
            }
            val index = start + offset * step
            dst[index] = if (sumAlpha == 0L) {
                0
            } else {
                val count = (to - from + 1).toLong()
                // alpha 按窗口平均，颜色按各自的 alpha 加权——半透明的地方不会把颜色拖黑。
                val alpha = (sumAlpha / count).toInt().coerceIn(0, 255)
                val red = (sumRed / sumAlpha).toInt().coerceIn(0, 255)
                val green = (sumGreen / sumAlpha).toInt().coerceIn(0, 255)
                val blue = (sumBlue / sumAlpha).toInt().coerceIn(0, 255)
                alpha shl 24 or (red shl 16) or (green shl 8) or blue
            }
        }
    }
}


