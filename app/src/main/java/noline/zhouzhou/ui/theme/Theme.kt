package noline.zhouzhou.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val LightColorScheme = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary,
    onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer,
    onTertiaryContainer = md_light_onTertiaryContainer,
    error = md_light_error,
    onError = md_light_onError,
    errorContainer = md_light_errorContainer,
    onErrorContainer = md_light_onErrorContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline,
    outlineVariant = md_light_outlineVariant,
    surfaceContainerLow = md_light_surfaceContainerLow,
    surfaceContainerHigh = md_light_surfaceContainerHigh
)

private val DarkColorScheme = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer,
    onTertiaryContainer = md_dark_onTertiaryContainer,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
    outlineVariant = md_dark_outlineVariant,
    surfaceContainerLow = md_dark_surfaceContainerLow,
    surfaceContainerHigh = md_dark_surfaceContainerHigh
)

val LocalFontScale = compositionLocalOf { 1.0f }

/** 强调色点缀：选中态、发布按钮、点赞收藏。 */
val LocalAccent = compositionLocalOf { BrandAccentLight }
val LocalAccentOn = compositionLocalOf { BrandAccentOnLight }
val LocalAccentContainer = compositionLocalOf { BrandAccentContainerLight }

/** 网站主题的原始令牌：卡片描边、软面、发丝线这些 Material 色槽装不下的东西。 */
val LocalNoLine = compositionLocalOf { LightNoLine }

/** 站点尺寸令牌：卡片 12、小面 8、图片 16、胶囊 999。 */
val TypechoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * 网站卡片的分层方式：卡面靠 1px 蓝灰描边和浅灰白底分开，投影只负责抬起一点点。
 * 只给描边会让卡片贴在地上，只给投影在浅色下会糊成一片白，两件事一起做。
 */
@Composable
fun Modifier.noLineCard(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    val t = LocalNoLine.current
    return this
        .shadow(3.dp, shape, ambientColor = Color(0x0D2C2D30), spotColor = Color(0x142C2D30))
        .clip(shape)
        .background(t.cardBg, shape)
        .border(1.dp, t.cardBorder, shape)
}

/**
 * 站点表单输入框的画法：卡白底 + 1px 蓝灰描边，聚焦才换成强调色描边。
 * Material 默认的深灰粗边框在这套浅底上太重，看着像没做完。
 */
@Composable
fun noLineFieldColors(): TextFieldColors {
    val t = LocalNoLine.current
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = t.cardBg,
        unfocusedContainerColor = t.cardBg,
        disabledContainerColor = t.surfaceSoft,
        errorContainerColor = t.cardBg,
        focusedBorderColor = t.accent,
        unfocusedBorderColor = t.cardBorder,
        cursorColor = t.accent,
        focusedLabelColor = t.accent,
        unfocusedLabelColor = t.textLight,
        focusedTextColor = t.textMain,
        unfocusedTextColor = t.textMain,
        unfocusedPlaceholderColor = t.textLight,
        focusedPlaceholderColor = t.textLight.copy(alpha = t.textLight.alpha * 0.6f),
        unfocusedLeadingIconColor = t.textLight,
        focusedLeadingIconColor = t.accent,
        errorBorderColor = t.healthObese
    )
}

/** 站点上的筛选药丸：白底 1px 描边，选中整颗翻成品牌色。首页分类条、后台筛选共用这一颗。 */
@Composable
fun NoLineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val t = LocalNoLine.current
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) t.brand else t.cardBg)
            .border(1.dp, if (selected) t.brand else t.cardBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) t.onBrand else t.textSecondary
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) t.onBrand else t.textStrong
        )
    }
}

/** 网站 .stat-item：软面小胶囊装计数，等宽数字免得数字一变就把旁边的挤来挤去。 */
@Composable
fun StatPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = LocalNoLine.current.textLight
) {
    val t = LocalNoLine.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(t.surfaceSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun TextStyle.scaled(scale: Float): TextStyle {
    val newLineHeight = if (lineHeight.isUnspecified) {
        lineHeight
    } else {
        (lineHeight.value * scale).roundToInt().em
    }
    return copy(fontSize = (fontSize.value * scale).roundToInt().sp, lineHeight = newLineHeight)
}

/** 字号设置要作用于全部 Compose 文字，而不只是 WebView，故在此整体缩放。 */
private fun scaledTypography(scale: Float): Typography {
    if (scale == 1f) return TypechoTypography
    val base = TypechoTypography
    fun s(style: TextStyle) = style.scaled(scale)
    return Typography(
        displayLarge = s(base.displayLarge),
        displayMedium = s(base.displayMedium),
        displaySmall = s(base.displaySmall),
        headlineLarge = s(base.headlineLarge),
        headlineMedium = s(base.headlineMedium),
        headlineSmall = s(base.headlineSmall),
        titleLarge = s(base.titleLarge),
        titleMedium = s(base.titleMedium),
        titleSmall = s(base.titleSmall),
        bodyLarge = s(base.bodyLarge),
        bodyMedium = s(base.bodyMedium),
        bodySmall = s(base.bodySmall),
        labelLarge = s(base.labelLarge),
        labelMedium = s(base.labelMedium),
        labelSmall = s(base.labelSmall)
    )
}

@Composable
fun TypechoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val scale = fontScale.coerceIn(0.85f, 1.5f)
    val density = LocalDensity.current
    val typography = remember(scale) { scaledTypography(scale) }

    CompositionLocalProvider(
        LocalFontScale provides scale,
        LocalDensity provides Density(density.density, density.fontScale * scale),
        LocalNoLine provides if (darkTheme) DarkNoLine else LightNoLine,
        LocalAccent provides if (darkTheme) BrandAccentDark else BrandAccentLight,
        LocalAccentOn provides if (darkTheme) BrandAccentOnDark else BrandAccentOnLight,
        LocalAccentContainer provides if (darkTheme) BrandAccentContainerDark else BrandAccentContainerLight
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = TypechoShapes,
            content = content
        )
    }
}
