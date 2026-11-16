package com.wavecue.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Palette tuned for long sessions in a dim studio: low glare, high separation. */
object WaveCueColors {
    val Background = Color(0xFF0B0F12)
    val Surface = Color(0xFF121A1F)
    val SurfaceHigh = Color(0xFF1A242B)
    val Outline = Color(0xFF2A3942)
    val Primary = Color(0xFF4FD1C5)
    val PrimaryDim = Color(0xFF2B7A74)
    val Secondary = Color(0xFFE8C47A)
    val Error = Color(0xFFFF7A7A)
    val TextPrimary = Color(0xFFE6EDF1)
    val TextSecondary = Color(0xFF93A6B0)
    val WaveformFill = Color(0xFF4FD1C5)
    val WaveformRms = Color(0xFF8BE8DF)
    val WaveformPlayed = Color(0xFF2A6F6A)
    val Playhead = Color(0xFFE8C47A)
    val Flag = Color(0xFFE8C47A)
    val Silence = Color(0x334FD1C5)
    val Note = Color(0xFF9B8CFF)
}

private val WaveCueScheme = darkColorScheme(
    primary = WaveCueColors.Primary,
    onPrimary = Color(0xFF04191A),
    primaryContainer = WaveCueColors.PrimaryDim,
    onPrimaryContainer = WaveCueColors.TextPrimary,
    secondary = WaveCueColors.Secondary,
    onSecondary = Color(0xFF1D1608),
    background = WaveCueColors.Background,
    onBackground = WaveCueColors.TextPrimary,
    surface = WaveCueColors.Surface,
    onSurface = WaveCueColors.TextPrimary,
    surfaceVariant = WaveCueColors.SurfaceHigh,
    onSurfaceVariant = WaveCueColors.TextSecondary,
    outline = WaveCueColors.Outline,
    error = WaveCueColors.Error,
)

private val WaveCueTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
    ),
)

@Composable
fun WaveCueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = WaveCueScheme,
        typography = WaveCueTypography,
        content = content,
    )
}
