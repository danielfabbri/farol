package app.farol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.farol.data.prefs.ThemeMode

private val Navy = Color(0xFF0A1222)
private val NavySurface = Color(0xFF0F1A2E)
private val NavyContainer = Color(0xFF15233D)
private val NavyContainerHigh = Color(0xFF1B2C4A)
private val Blue = Color(0xFF5B9BFF)
private val BlueDeep = Color(0xFF1E5FD9)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF04122B),
    primaryContainer = Color(0xFF173A73),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFF8FB8FF),
    onSecondary = Color(0xFF07152E),
    secondaryContainer = NavyContainerHigh,
    onSecondaryContainer = Color(0xFFD5E2F7),
    tertiary = Color(0xFFF2C46D),
    background = Navy,
    onBackground = Color(0xFFE6ECF5),
    surface = Navy,
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = NavyContainer,
    onSurfaceVariant = Color(0xFF9AA8BF),
    surfaceContainerLowest = Navy,
    surfaceContainerLow = NavySurface,
    surfaceContainer = NavySurface,
    surfaceContainerHigh = NavyContainer,
    surfaceContainerHighest = NavyContainerHigh,
    outline = Color(0xFF2E4063),
    outlineVariant = Color(0xFF1E2D4A),
    error = Color(0xFFFF8A80),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = BlueDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF3D5F99),
    secondaryContainer = Color(0xFFE3EAF7),
    onSecondaryContainer = Color(0xFF13284D),
    tertiary = Color(0xFF8A5A00),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF0B1426),
    surface = Color(0xFFF6F8FC),
    onSurface = Color(0xFF0B1426),
    surfaceVariant = Color(0xFFE8EEF8),
    onSurfaceVariant = Color(0xFF4A5872),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFF3FA),
    surfaceContainerHighest = Color(0xFFE6ECF6),
    outline = Color(0xFFC3CEDF),
    outlineVariant = Color(0xFFDDE4EF),
)

private val Sans = FontFamily.SansSerif

private val FarolTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 29.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp),
)

@Composable
fun FarolTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = FarolTypography, content = content)
}
