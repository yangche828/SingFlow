package com.singflow.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Ink100 = Color(0xFFE2E8F0)
private val Ink300 = Color(0xFFA9B7C9)
private val Ink800 = Color(0xFF0F172A)
private val Ink850 = Color(0xFF0F2A3A)
private val Ink860 = Color(0xFF13283C)
private val Line900 = Color(0xFF2A3A55)
private val Line950 = Color(0xFF1B2942)

private val FrostCyan = Color(0xFFCFFAFE)
private val FrostTeal = Color(0xFF083344)
private val FrostSky = Color(0xFFE0F2FE)
private val FrostSea = Color(0xFF082F49)
private val FrostEdge = Color(0xFFE7EFF7)
private val FrostInk = Color(0xFF425466)
private val FrostLine = Color(0xFFB6C6D8)
private val FrostLineSoft = Color(0xFFD6E2EE)
private val LightPrimary = Color(0xFF0E7490)
private val LightSecondary = Color(0xFF0369A1)
private val LightTertiary = Color(0xFF6D28D9)
private val LightError = Color(0xFFB91C1C)

private val DarkColors = darkColorScheme(
    primary = AuroraCyan,
    onPrimary = DeepSpace,
    primaryContainer = Ink850,
    onPrimaryContainer = AuroraCyan,
    secondary = AuroraBlue,
    onSecondary = DeepSpace,
    secondaryContainer = Ink860,
    onSecondaryContainer = AuroraBlue,
    tertiary = AuroraViolet,
    onTertiary = DeepSpace,
    background = DeepSpace,
    onBackground = Ink100,
    surface = DeepSurface,
    onSurface = Ink100,
    surfaceVariant = DeepCard,
    onSurfaceVariant = Ink300,
    outline = Line900,
    outlineVariant = Line950,
    error = SignalRed,
    onError = DeepSpace
)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = FrostSurface,
    primaryContainer = FrostCyan,
    onPrimaryContainer = FrostTeal,
    secondary = LightSecondary,
    onSecondary = FrostSurface,
    secondaryContainer = FrostSky,
    onSecondaryContainer = FrostSea,
    tertiary = LightTertiary,
    onTertiary = FrostSurface,
    background = FrostBackground,
    onBackground = Ink800,
    surface = FrostSurface,
    onSurface = Ink800,
    surfaceVariant = FrostEdge,
    onSurfaceVariant = FrostInk,
    outline = FrostLine,
    outlineVariant = FrostLineSoft,
    error = LightError,
    onError = FrostSurface
)

/**
 * SingFlow 主题。
 *
 * @param darkTheme 是否深色
 * @param dynamicColor 是否启用 Android 12+ 动态取色
 */
@Composable
fun SingFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SingFlowTypography,
        content = content
    )
}
