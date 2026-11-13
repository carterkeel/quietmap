package app.quietmap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A clean, Signal-like look: white or near-black surfaces, one ultramarine accent, soft grey
 * containers, pill buttons, and the phone's own system font (Roboto on Android, San Francisco on
 * iOS), which is what Signal uses on each platform.
 */
object Palette {
    val Ultramarine = Color(0xFF2C6BED)
    val UltramarineDark = Color(0xFF8AB2FF)
    val Green = Color(0xFF0B8A3E)
    val GreenDark = Color(0xFF5BD68A)
    val Amber = Color(0xFFB45309)
    val Red = Color(0xFFD32F2F)
}

private val Light = lightColorScheme(
    primary = Palette.Ultramarine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FB),
    onPrimaryContainer = Color(0xFF16397E),
    secondary = Color(0xFF545863),
    secondaryContainer = Color(0xFFE6E9F0),
    onSecondaryContainer = Color(0xFF1B1B1D),
    background = Color.White,
    onBackground = Color(0xFF1B1B1D),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1D),
    surfaceVariant = Color(0xFFF0F1F4),
    onSurfaceVariant = Color(0xFF5C5F66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFF0F1F4),
    surfaceContainerHigh = Color(0xFFE9EBEF),
    surfaceContainerHighest = Color(0xFFE2E4E9),
    outline = Color(0xFFC4C6CC),
    outlineVariant = Color(0xFFE6E7EA),
    error = Palette.Red,
    errorContainer = Color(0xFFFCE4E4),
    onErrorContainer = Color(0xFFB71C1C),
)

private val Dark = darkColorScheme(
    primary = Palette.UltramarineDark,
    onPrimary = Color(0xFF0A2557),
    primaryContainer = Color(0xFF233A6B),
    onPrimaryContainer = Color(0xFFDCE5FB),
    secondary = Color(0xFFB9BCC4),
    secondaryContainer = Color(0xFF2E3036),
    onSecondaryContainer = Color(0xFFE6E7EA),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE9E9EA),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE9E9EA),
    surfaceVariant = Color(0xFF232427),
    onSurfaceVariant = Color(0xFFA9ACB3),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF1A1A1C),
    surfaceContainer = Color(0xFF1F2023),
    surfaceContainerHigh = Color(0xFF26272A),
    surfaceContainerHighest = Color(0xFF2E2F33),
    outline = Color(0xFF4A4D55),
    outlineVariant = Color(0xFF2E2F33),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF3B1D1D),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val System = FontFamily.Default

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = System, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = System, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = System, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = System, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun QuietmapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** The "verified" green, readable in both themes. */
@Composable
fun verifiedGreen(): Color = if (isSystemInDarkTheme()) Palette.GreenDark else Palette.Green
