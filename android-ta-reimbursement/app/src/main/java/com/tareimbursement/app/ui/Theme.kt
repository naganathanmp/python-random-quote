package com.tareimbursement.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tareimbursement.app.container
import com.tareimbursement.app.data.UiPrefs

val Navy = Color(0xFF1F3864)
val Green = Color(0xFF2E9E6A)
val Amber = Color(0xFFB7791F)
val Danger = Color(0xFFC0392B)

/** Signal yellow of the departure-board look (used for highlights in both themes). */
val Signal = Color(0xFFFFCC00)

/**
 * Accent choices. [light]/[dark] are readable as text on the matching background; [fill] is the
 * bright colour for buttons and chips, with [onFill] for text on it.
 */
enum class Accent(val label: String, val light: Color, val dark: Color, val fill: Color, val onFill: Color) {
    YELLOW("Signal yellow", Color(0xFF6E5300), Color(0xFFFFCC00), Color(0xFFFFCC00), Color(0xFF16181C)),
    SAFFRON("Saffron", Color(0xFFAE4A00), Color(0xFFFF9A4D), Color(0xFFFF8A1F), Color(0xFF1B0B00)),
    TEAL("Teal", Color(0xFF00695F), Color(0xFF4DD0C0), Color(0xFF0F9A8A), Color.White),
    BLUE("Blue", Color(0xFF1F55C8), Color(0xFF8AB4FF), Color(0xFF2F6BFF), Color.White),
    VIOLET("Violet", Color(0xFF5B35C8), Color(0xFFB9A3FF), Color(0xFF7046E8), Color.White),
    WALLPAPER("My wallpaper", Color(0xFF1F55C8), Color(0xFF8AB4FF), Color(0xFF2F6BFF), Color.White);

    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } ?: YELLOW }
}

private fun lightScheme(a: Accent) = lightColorScheme(
    primary = a.light, onPrimary = Color.White, primaryContainer = a.fill, onPrimaryContainer = a.onFill,
    secondary = Green, onSecondary = Color.White, secondaryContainer = Color(0xFFD7F2E5), onSecondaryContainer = Color(0xFF0E4D31),
    tertiary = Amber, tertiaryContainer = Color(0xFFFCEFD9), onTertiaryContainer = Color(0xFF5C3B07),
    background = Color(0xFFF2F1ED), onBackground = Color(0xFF16181C), surface = Color(0xFFFBFAF7), onSurface = Color(0xFF16181C),
    surfaceVariant = Color(0xFFE8E6DF), onSurfaceVariant = Color(0xFF585B62),
    surfaceContainer = Color(0xFFEFEDE7), surfaceContainerHigh = Color(0xFFE8E6DF), surfaceContainerLow = Color(0xFFFBFAF7), surfaceContainerHighest = Color(0xFFE2DFD7),
    error = Danger, outline = Color(0xFF8E9098), outlineVariant = Color(0xFFD7D4CB),
)

private fun darkScheme(a: Accent) = darkColorScheme(
    primary = a.dark, onPrimary = Color(0xFF16181C), primaryContainer = a.fill, onPrimaryContainer = a.onFill,
    secondary = Color(0xFF7FD3AB), secondaryContainer = Color(0xFF1B4D37), onSecondaryContainer = Color(0xFFD7F2E5),
    tertiary = Color(0xFFF2C26B), tertiaryContainer = Color(0xFF4F3A12), onTertiaryContainer = Color(0xFFFCEFD9),
    background = Color(0xFF0F1114), onBackground = Color(0xFFECEBE6), surface = Color(0xFF181B20), onSurface = Color(0xFFECEBE6),
    surfaceVariant = Color(0xFF23272E), onSurfaceVariant = Color(0xFFA3A6AD),
    surfaceContainer = Color(0xFF1A1D23), surfaceContainerHigh = Color(0xFF22262D), surfaceContainerLow = Color(0xFF15181C), surfaceContainerHighest = Color(0xFF2A2E36),
    error = Color(0xFFF2A49B), outline = Color(0xFF6B6F78), outlineVariant = Color(0xFF2D3138),
)

/** Settings → Reduce motion; read by navigation and small animations. */
val LocalReduceMotion = compositionLocalOf { false }

fun UiPrefs.isDark(system: Boolean) = when (theme) { "DARK" -> true; "LIGHT" -> false; else -> system }

@Composable
fun TaTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val ui by ctx.container.prefs.ui.collectAsState()
    val dark = ui.isDark(isSystemInDarkTheme())
    val accent = Accent.of(ui.accent)
    val scheme: ColorScheme = if (accent == Accent.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkScheme(accent) else lightScheme(accent)
    val density = LocalDensity.current
    CompositionLocalProvider(
        // Text size inside the app only (on top of the phone's own font size).
        LocalDensity provides Density(density.density, density.fontScale * ui.textScale.coerceIn(0.85f, 1.4f)),
        LocalReduceMotion provides ui.reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp)),
            content = content,
        )
    }
}
