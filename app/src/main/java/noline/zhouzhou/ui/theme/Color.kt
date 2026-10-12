package noline.zhouzhou.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 逐条对齐站点主题 Noline/css/design.css v1.16.0 的 :root 与 :root[data-theme="dark"]。
 * 站点的中性色全是一个墨色按浓度派生，这里照做：α 留在 Color 上，交给绘制时与底混合，
 * 结果和浏览器一致。改配色请先改网站 :root，再同步过来。
 */

private val InkLight = Color(0xFF363636)
private val InkDark = Color(0xFFF7F7FA)
private val PaperLight = Color(0xFFFFFFFF)
private val PaperDark = Color(0xFF1B1C20)

@Immutable
data class NoLineTokens(
    val accent: Color,
    val accentHover: Color,
    /** --accent-bg：强调色掺一成（深色一成八），铺在选中态底下。 */
    val accentBg: Color,
    val accentRing: Color,
    val accentContainer: Color,
    val brand: Color,
    val onBrand: Color,
    val pageBg: Color,
    val cardBg: Color,
    /** --card-border：这套观感的骨架，卡片靠 1px 蓝灰线和底色分层。 */
    val cardBorder: Color,
    /** --border-color：发丝分隔线。 */
    val hairline: Color,
    val borderStrong: Color,
    val surfaceSoft: Color,
    val surfaceSunken: Color,
    val surfaceCode: Color,
    val textStrong: Color,
    val textMain: Color,
    val textSecondary: Color,
    val textLight: Color,
    /** --heading-marker：正文标题前那颗暖金人字锚点，刻意不跟强调色走。 */
    val headingMarker: Color,
    val coverFallback: Color,
    /** --on-cover：封面罩上面的字色，深色主题下不是纯白而是偏蓝的浅灰。 */
    val onCover: Color,
    val heroVeilTop: Color,
    val heroVeilBottom: Color,
    val glassBg: Color,
    val scrim: Color,
    val lightboxBg: Color,
    val codeBg: Color,
    val codeFg: Color,
    val markBg: Color,
    val warnBg: Color,
    val warnText: Color,
    val healthThin: Color,
    val healthNormal: Color,
    val healthOver: Color,
    val healthObese: Color,
    /** --health-*-bg：分档色掺在底上的浓度，深色主题下要掺得更重才读得出来。 */
    val healthFill: Float
)

val LightNoLine = NoLineTokens(
    accent = Color(0xFF425AEF),
    accentHover = Color(0xFF3549C9),
    accentBg = Color(0x1A425AEF),                    // rgba(accent, .10)
    accentRing = Color(0x59425AEF),                  // rgba(accent, .35)
    accentContainer = Color(0x24425AEF),             // rgba(accent, .14)
    brand = Color(0xFF425AEF),
    onBrand = Color(0xFFFFFFFF),
    pageBg = Color(0xFFF7F9FE),
    cardBg = PaperLight,
    cardBorder = Color(0xFFE3E8F7),
    hairline = InkLight.copy(alpha = .07f),
    borderStrong = InkLight.copy(alpha = .20f),
    surfaceSoft = Color(0xFFF7F7F9),
    surfaceSunken = Color(0xFFF2F5F8),
    surfaceCode = InkLight.copy(alpha = .08f),
    textStrong = InkLight,
    textMain = InkLight.copy(alpha = .90f),
    textSecondary = InkLight.copy(alpha = .78f),
    textLight = InkLight.copy(alpha = .50f),
    headingMarker = Color(0xFFF2BA4B),
    coverFallback = Color(0xFFE8ECF7),
    onCover = Color(0xFFFFFFFF),
    heroVeilTop = Color(0x47101426),                  // rgba(16,20,38,.28)
    heroVeilBottom = Color(0x99101426),               // rgba(16,20,38,.60)
    glassBg = Color(0xB8FFFFFF),                     // rgba(255,255,255,.72)
    scrim = Color(0x730F172A),                       // rgba(15,23,42,.45)
    lightboxBg = Color(0xD1080C12),                  // rgba(8,12,18,.82)
    codeBg = Color(0xFF282C34),
    codeFg = Color(0xFFABB2BF),
    markBg = Color(0xFFFEF08A),
    warnBg = Color(0xFFFEF3C7),
    warnText = Color(0xFFB45309),
    healthThin = Color(0xFF0C7489),
    healthNormal = Color(0xFF2F7A44),
    healthOver = Color(0xFFA8600F),
    healthObese = Color(0xFFB3352F),
    healthFill = 0.11f
)

val DarkNoLine = NoLineTokens(
    accent = Color(0xFF8A9BFF),
    accentHover = Color(0xFFA3B2FF),
    accentBg = Color(0x2E8A9BFF),                    // 深底上 .10 太淡，站点抬到 .18
    accentRing = Color(0x738A9BFF),                  // rgba(accent, .45)
    accentContainer = Color(0x338A9BFF),             // rgba(accent, .20)
    brand = Color(0xFF8A9BFF),
    onBrand = Color(0xFF14161C),                     // 提亮后的强调色上白字会糊，翻成深字
    pageBg = Color(0xFF17191D),
    cardBg = PaperDark,
    cardBorder = Color(0xFF3D3D3F),
    hairline = InkDark.copy(alpha = .07f),
    borderStrong = InkDark.copy(alpha = .16f),
    surfaceSoft = Color(0xFF21232A),
    surfaceSunken = Color(0x47000000),               // rgba(0,0,0,.28)
    surfaceCode = InkDark.copy(alpha = .08f),
    textStrong = InkDark,
    textMain = InkDark.copy(alpha = .86f),
    textSecondary = InkDark.copy(alpha = .66f),
    textLight = InkDark.copy(alpha = .46f),
    headingMarker = Color(0xFFE8C476),
    coverFallback = Color(0xFF2A2D36),
    onCover = Color(0xFFF2F5F9),
    heroVeilTop = Color(0x57000000),                  // rgba(0,0,0,.34)
    heroVeilBottom = Color(0xC70A0E14),               // rgba(10,14,20,.78)
    glassBg = Color(0xB817191D),
    scrim = Color(0x9E000000),
    lightboxBg = Color(0xE6000000),
    codeBg = Color(0xFF1A1F27),
    codeFg = Color(0xFFABB2BF),
    markBg = Color(0xFF575019),
    warnBg = Color(0xFF3A2F14),
    warnText = Color(0xFFE0B25C),
    healthThin = Color(0xFF58BDD0),
    healthNormal = Color(0xFF6EC389),
    healthOver = Color(0xFFE2A75F),
    healthObese = Color(0xFFE0837A),
    healthFill = 0.16f
)

// Material 色槽只是网站令牌的投影，别在别的文件里引用这些名字。
val md_light_primary = LightNoLine.accent
val md_light_onPrimary = LightNoLine.onBrand
val md_light_primaryContainer = LightNoLine.accentContainer
val md_light_onPrimaryContainer = LightNoLine.accentHover
val md_light_secondary = LightNoLine.textSecondary
val md_light_onSecondary = LightNoLine.cardBg
val md_light_secondaryContainer = LightNoLine.surfaceSoft
val md_light_onSecondaryContainer = LightNoLine.textMain
val md_light_tertiary = Color(0xFFB4791A)            // 暖金的深字版，够压住白底
val md_light_onTertiary = LightNoLine.cardBg
val md_light_tertiaryContainer = Color(0xFFFBE9C6)
val md_light_onTertiaryContainer = Color(0xFF5B3D0A)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)
val md_light_background = LightNoLine.pageBg
val md_light_onBackground = LightNoLine.textStrong
val md_light_surface = LightNoLine.cardBg
val md_light_onSurface = LightNoLine.textStrong
val md_light_surfaceVariant = LightNoLine.surfaceSoft
val md_light_onSurfaceVariant = LightNoLine.textSecondary
val md_light_outline = LightNoLine.textLight
val md_light_outlineVariant = LightNoLine.cardBorder
val md_light_surfaceContainerLow = LightNoLine.cardBg
val md_light_surfaceContainerHigh = LightNoLine.surfaceSoft

val md_dark_primary = DarkNoLine.accent
val md_dark_onPrimary = DarkNoLine.onBrand
val md_dark_primaryContainer = DarkNoLine.accentContainer
val md_dark_onPrimaryContainer = DarkNoLine.accentHover
val md_dark_secondary = DarkNoLine.textSecondary
val md_dark_onSecondary = DarkNoLine.cardBg
val md_dark_secondaryContainer = DarkNoLine.surfaceSoft
val md_dark_onSecondaryContainer = DarkNoLine.textMain
val md_dark_tertiary = Color(0xFFE8C476)
val md_dark_onTertiary = Color(0xFF3A2F05)
val md_dark_tertiaryContainer = Color(0xFF52451A)
val md_dark_onTertiaryContainer = Color(0xFFF4E1A7)
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = DarkNoLine.pageBg
val md_dark_onBackground = DarkNoLine.textStrong
val md_dark_surface = DarkNoLine.cardBg
val md_dark_onSurface = DarkNoLine.textStrong
val md_dark_surfaceVariant = DarkNoLine.surfaceSoft
val md_dark_onSurfaceVariant = DarkNoLine.textSecondary
val md_dark_outline = DarkNoLine.textLight
val md_dark_outlineVariant = DarkNoLine.cardBorder
val md_dark_surfaceContainerLow = DarkNoLine.cardBg
val md_dark_surfaceContainerHigh = DarkNoLine.surfaceSoft

/** 选中态、发布按钮、点赞收藏用的强调色；等价于品牌色。 */
val BrandAccentLight = LightNoLine.accent
val BrandAccentOnLight = LightNoLine.onBrand
val BrandAccentContainerLight = LightNoLine.accentBg
val BrandAccentDark = DarkNoLine.accent
val BrandAccentOnDark = DarkNoLine.onBrand
val BrandAccentContainerDark = DarkNoLine.accentBg
