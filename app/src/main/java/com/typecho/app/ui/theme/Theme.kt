package com.typecho.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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

/** 暖红点缀色，随深浅色切换；供自绘组件（TabBar、互动栏、搜索药丸）使用。 */
val LocalAccent = compositionLocalOf { BrandAccentLight }
val LocalAccentOn = compositionLocalOf { BrandAccentOnLight }
val LocalAccentContainer = compositionLocalOf { BrandAccentContainerLight }

val TypechoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

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
