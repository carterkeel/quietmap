package app.quietmap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Blue = Color(0xFF1D4ED8)
    val BlueLight = Color(0xFF8AA6FF)
    val Green = Color(0xFF15803D)
    val GreenLight = Color(0xFF5BD68A)
    val Amber = Color(0xFFB45309)
    val Red = Color(0xFFB91C1C)
    val Gray = Color(0xFF6B6B6B)
}

private val Light = lightColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EFFE),
    onPrimaryContainer = Palette.Blue,
    secondary = Color(0xFF1F1F1F),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Palette.Gray,
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE4E4E4),
    error = Palette.Red,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Palette.Red,
)

private val Dark = darkColorScheme(
    primary = Palette.BlueLight,
    onPrimary = Color(0xFF0B1B4F),
    primaryContainer = Color(0xFF1F2A4F),
    onPrimaryContainer = Palette.BlueLight,
    background = Color(0xFF121418),
    surface = Color(0xFF121418),
    surfaceVariant = Color(0xFF1E2128),
    onSurfaceVariant = Color(0xFFA9AEB8),
    outline = Color(0xFF4A4F5A),
    outlineVariant = Color(0xFF2A2E36),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF3A1A1A),
    onErrorContainer = Color(0xFFFF8A80),
)

@Composable
fun QuietmapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
