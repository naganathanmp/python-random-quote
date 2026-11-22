package com.tareimbursement.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val Navy = Color(0xFF1F3864)
val Green = Color(0xFF2E9E6A)
val Amber = Color(0xFFB7791F)
val Danger = Color(0xFFC0392B)

private val Light = lightColorScheme(
    primary = Navy, onPrimary = Color.White, primaryContainer = Color(0xFFDDE6F5), onPrimaryContainer = Navy,
    secondary = Green, onSecondary = Color.White, secondaryContainer = Color(0xFFD7F2E5), onSecondaryContainer = Color(0xFF0E4D31),
    tertiary = Amber, tertiaryContainer = Color(0xFFFCEFD9), onTertiaryContainer = Color(0xFF5C3B07),
    background = Color(0xFFF7F9FC), surface = Color.White, surfaceVariant = Color(0xFFEEF2F7), onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainer = Color(0xFFF1F4F9), surfaceContainerHigh = Color(0xFFEAEFF6), surfaceContainerLow = Color.White,
    error = Danger, outlineVariant = Color(0xFFDCE2EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6F0), onPrimary = Color(0xFF0B1E3D), primaryContainer = Color(0xFF223A63), onPrimaryContainer = Color(0xFFDDE6F5),
    secondary = Color(0xFF7FD3AB), secondaryContainer = Color(0xFF1B4D37), onSecondaryContainer = Color(0xFFD7F2E5),
    tertiary = Color(0xFFF2C26B), tertiaryContainer = Color(0xFF4F3A12), onTertiaryContainer = Color(0xFFFCEFD9),
    background = Color(0xFF10141A), surface = Color(0xFF161B22), surfaceVariant = Color(0xFF232A33), surfaceContainer = Color(0xFF1B2129),
    surfaceContainerHigh = Color(0xFF222932), surfaceContainerLow = Color(0xFF161B22), error = Color(0xFFF2A49B),
)

@Composable
fun TaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}
