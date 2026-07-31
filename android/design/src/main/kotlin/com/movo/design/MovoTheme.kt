package com.movo.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MOVO brand palette.
 *
 * Forest green carries the brand, amber marks money and urgency, and the neutral
 * ramp keeps map-heavy screens legible in Kigali daylight. Every pairing used for
 * text meets WCAG AA contrast on its background (spec §18: accessible contrast).
 */
object MovoPalette {
    val Forest = Color(0xFF086B4D)
    val ForestDark = Color(0xFF054733)
    val ForestDeep = Color(0xFF03301F)
    val Signal = Color(0xFF19A974)
    val Mint = Color(0xFFE6F4EE)
    val MintDark = Color(0xFF0F2A20)
    val Amber = Color(0xFFF5A623)
    val AmberSoft = Color(0xFFFFF4E0)
    val Ink = Color(0xFF121614)
    val Slate = Color(0xFF5A6B63)
    val Mist = Color(0xFFEDF1EF)
    val RouteWhite = Color(0xFFFCFCFA)
    val Danger = Color(0xFFD1382C)
    val DangerSoft = Color(0xFFFDEDEC)
    val Info = Color(0xFF2563EB)
    val NightSurface = Color(0xFF161A18)
    val NightElevated = Color(0xFF1F2523)
    val NightBackground = Color(0xFF0E1211)
}

private val LightScheme = lightColorScheme(
    primary = MovoPalette.Forest,
    onPrimary = Color.White,
    primaryContainer = MovoPalette.Mint,
    onPrimaryContainer = MovoPalette.ForestDeep,
    secondary = MovoPalette.Amber,
    onSecondary = MovoPalette.Ink,
    secondaryContainer = MovoPalette.AmberSoft,
    onSecondaryContainer = Color(0xFF6B4400),
    tertiary = MovoPalette.Signal,
    onTertiary = Color.White,
    background = Color(0xFFF6F8F7),
    onBackground = MovoPalette.Ink,
    surface = MovoPalette.RouteWhite,
    onSurface = MovoPalette.Ink,
    surfaceVariant = MovoPalette.Mist,
    onSurfaceVariant = MovoPalette.Slate,
    outline = Color(0xFFCBD6D0),
    outlineVariant = Color(0xFFE2EAE5),
    error = MovoPalette.Danger,
    onError = Color.White,
    errorContainer = MovoPalette.DangerSoft,
    onErrorContainer = Color(0xFF7A1710),
    scrim = Color(0x99000000)
)

private val DarkScheme = darkColorScheme(
    primary = MovoPalette.Signal,
    onPrimary = Color(0xFF00281A),
    primaryContainer = MovoPalette.MintDark,
    onPrimaryContainer = Color(0xFFAEE9CE),
    secondary = MovoPalette.Amber,
    onSecondary = Color(0xFF2A1B00),
    secondaryContainer = Color(0xFF3D2A05),
    onSecondaryContainer = Color(0xFFFFE0AC),
    tertiary = MovoPalette.Signal,
    onTertiary = Color(0xFF00281A),
    background = MovoPalette.NightBackground,
    onBackground = Color(0xFFE6ECE9),
    surface = MovoPalette.NightSurface,
    onSurface = Color(0xFFE6ECE9),
    surfaceVariant = MovoPalette.NightElevated,
    onSurfaceVariant = Color(0xFFA9B8B1),
    outline = Color(0xFF3B4643),
    outlineVariant = Color(0xFF2A322F),
    error = Color(0xFFFF897D),
    onError = Color(0xFF5F1109),
    errorContainer = Color(0xFF7A1710),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color(0xCC000000)
)

private fun display(size: Int, weight: FontWeight, lineHeight: Int, spacing: Double = 0.0) = TextStyle(
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight.sp,
    letterSpacing = spacing.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
)

/** Compact, high-legibility scale tuned for one-handed use on small Android phones. */
val MovoTypography = Typography(
    displaySmall = display(34, FontWeight.Bold, 40, (-0.5)),
    headlineLarge = display(28, FontWeight.Bold, 34, (-0.4)),
    headlineMedium = display(24, FontWeight.Bold, 30, (-0.3)),
    headlineSmall = display(20, FontWeight.SemiBold, 26, (-0.2)),
    titleLarge = display(18, FontWeight.SemiBold, 24),
    titleMedium = display(16, FontWeight.SemiBold, 22),
    titleSmall = display(14, FontWeight.SemiBold, 20),
    bodyLarge = display(16, FontWeight.Normal, 24),
    bodyMedium = display(14, FontWeight.Normal, 20),
    bodySmall = display(12, FontWeight.Normal, 17),
    labelLarge = display(14, FontWeight.SemiBold, 18, 0.1),
    labelMedium = display(12, FontWeight.Medium, 16, 0.2),
    labelSmall = display(11, FontWeight.Medium, 14, 0.3)
)

val MovoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Spacing rhythm — every screen uses these steps so the apps feel like one product. */
object MovoSpacing {
    val hairline = 2.dp
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val default = 16.dp
    val large = 20.dp
    val xlarge = 24.dp
    val section = 32.dp
    val actionHeight = 56.dp
    val sheetCorner = 28.dp
}

/** Status colours shared by both apps so a delivery state always looks the same. */
data class MovoStatusColors(
    val positive: Color,
    val positiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val critical: Color,
    val criticalContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
    val live: Color,
    val map: Color
)

private val LightStatusColors = MovoStatusColors(
    positive = Color(0xFF0F7B4F), positiveContainer = Color(0xFFDDF3E7),
    warning = Color(0xFF9A6100), warningContainer = MovoPalette.AmberSoft,
    critical = MovoPalette.Danger, criticalContainer = MovoPalette.DangerSoft,
    neutral = MovoPalette.Slate, neutralContainer = MovoPalette.Mist,
    live = MovoPalette.Signal, map = Color(0xFF0B3D2E)
)

private val DarkStatusColors = MovoStatusColors(
    positive = Color(0xFF5FD3A0), positiveContainer = Color(0xFF11362A),
    warning = Color(0xFFFFC46B), warningContainer = Color(0xFF3A2A08),
    critical = Color(0xFFFF897D), criticalContainer = Color(0xFF48120C),
    neutral = Color(0xFFA9B8B1), neutralContainer = Color(0xFF262E2B),
    live = Color(0xFF5FD3A0), map = Color(0xFFCDE9DC)
)

val LocalMovoStatusColors: ProvidableCompositionLocal<MovoStatusColors> =
    staticCompositionLocalOf { LightStatusColors }

object MovoTheme {
    val status: MovoStatusColors
        @Composable @ReadOnlyComposable get() = LocalMovoStatusColors.current
}

/**
 * Wraps content in the MOVO Material 3 theme. Dark mode follows the system by
 * default: riders work at night, and a bright screen is a safety problem.
 */
@Composable
fun MovoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMovoStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = MovoTypography,
            shapes = MovoShapes,
            content = content
        )
    }
}
