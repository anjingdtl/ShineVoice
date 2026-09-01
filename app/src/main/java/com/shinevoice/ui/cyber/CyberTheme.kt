package com.shinevoice.ui.cyber

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ShineVoice cyberpunk design tokens (UI Phase A).
 *
 * Dark is the primary cyberpunk theme: deep blue-black backgrounds, cold gray
 * text, neon yellow core actions, cyan status/info, magenta/red alerts. The
 * light variant keeps the same design language (light gray-white background,
 * dark lines, saturated yellow/cyan accents) so light mode never degrades into
 * a default white Material app. Every custom component reads these tokens via
 * [LocalCyberColors] and stays correct in both themes.
 */
data class CyberColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val outlineStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val accentDim: Color,
    val cyan: Color,
    val cyanDim: Color,
    val magenta: Color,
    val danger: Color,
    val success: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val gridLine: Color,
) {
    val glow: Color get() = accent.copy(alpha = 0.22f)
    val cyanGlow: Color get() = cyan.copy(alpha = 0.20f)
}

private val DarkCyber = CyberColors(
    background = Color(0xFF07090E),
    surface = Color(0xFF0B101A),
    surfaceHigh = Color(0xFF111927),
    outline = Color(0xFF233044),
    outlineStrong = Color(0xFF31425C),
    accent = Color(0xFFFCEE0A),
    onAccent = Color(0xFF0A0C10),
    accentDim = Color(0xFF6B6707),
    cyan = Color(0xFF29E6F6),
    cyanDim = Color(0xFF0E5660),
    magenta = Color(0xFFFF2E63),
    danger = Color(0xFFFF4D6D),
    success = Color(0xFF3CF08C),
    textPrimary = Color(0xFFD9E0EC),
    textMuted = Color(0xFF8A97AC),
    gridLine = Color(0xFF101724),
)

private val LightCyber = CyberColors(
    background = Color(0xFFE9EDF2),
    surface = Color(0xFFF5F7FA),
    surfaceHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFC3CEDA),
    outlineStrong = Color(0xFF8B9AAE),
    accent = Color(0xFFE8C400),
    onAccent = Color(0xFF14171C),
    accentDim = Color(0xFF9C8A05),
    cyan = Color(0xFF007A8C),
    cyanDim = Color(0xFF9DC4CC),
    magenta = Color(0xFFB01642),
    danger = Color(0xFFC4113D),
    success = Color(0xFF0E7A42),
    textPrimary = Color(0xFF1A202B),
    textMuted = Color(0xFF57637A),
    gridLine = Color(0xFFD5DCE4),
)

val LocalCyberColors = staticCompositionLocalOf { DarkCyber }

/** Shared cut-corner geometry: sharp diagonal corners, never plain rectangles. */
object CyberShape {
    val card = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
    val cardSmall = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val chip = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    val button = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val dialog = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp)
}

/** Spacing rhythm shared by cyber screens. */
object CyberSpacing {
    val screen = 16.dp
    val section = 14.dp
    val item = 10.dp
}

/**
 * Terminal-flavored text styles. Chinese body text stays on the default sans
 * family for clarity; codes/numbers/labels use monospace for the HUD feel.
 */
object CyberType {
    val terminalLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
    )
    val terminalValue = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    )
    val sectionCode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.6.sp,
    )
}

/** mm:ss clock for audio durations shown to normal users (never raw ms). */
fun formatDurationClock(durationMs: Long): String {
    val totalSeconds = (durationMs + 500) / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
fun CyberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val cyber = if (darkTheme) DarkCyber else LightCyber
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = cyber.accent,
            onPrimary = cyber.onAccent,
            secondary = cyber.cyan,
            onSecondary = cyber.onAccent,
            tertiary = cyber.magenta,
            background = cyber.background,
            onBackground = cyber.textPrimary,
            surface = cyber.surface,
            onSurface = cyber.textPrimary,
            surfaceVariant = cyber.surfaceHigh,
            onSurfaceVariant = cyber.textMuted,
            outline = cyber.outline,
            outlineVariant = cyber.outline,
            error = cyber.danger,
        )
    } else {
        lightColorScheme(
            primary = cyber.accent,
            onPrimary = cyber.onAccent,
            secondary = cyber.cyan,
            onSecondary = Color.White,
            tertiary = cyber.magenta,
            background = cyber.background,
            onBackground = cyber.textPrimary,
            surface = cyber.surface,
            onSurface = cyber.textPrimary,
            surfaceVariant = cyber.surfaceHigh,
            onSurfaceVariant = cyber.textMuted,
            outline = cyber.outline,
            outlineVariant = cyber.outline,
            error = cyber.danger,
        )
    }
    CompositionLocalProvider(LocalCyberColors provides cyber) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
