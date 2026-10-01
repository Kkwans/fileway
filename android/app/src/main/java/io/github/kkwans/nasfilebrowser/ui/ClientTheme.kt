package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.isSystemInDarkTheme
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

private val light = lightColorScheme(
    primary = Color(0xFF235ED7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EEFC), onPrimaryContainer = Color(0xFF204987),
    secondary = Color(0xFF4D607E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E9F5), onSecondaryContainer = Color(0xFF263B59),
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF172235),
    surface = Color.White, onSurface = Color(0xFF172235),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFEBF0F7), surfaceContainerHigh = Color(0xFFE5EBF4), surfaceContainerHighest = Color(0xFFDCE4EF),
    surfaceVariant = Color(0xFFEBF0F7), onSurfaceVariant = Color(0xFF536176),
    outline = Color(0xFF73829A), outlineVariant = Color(0xFFDCE3EE),
)
private val dark = darkColorScheme(
    primary = Color(0xFF9AB9FF), onPrimary = Color(0xFF123575),
    primaryContainer = Color(0xFF1D3459), onPrimaryContainer = Color(0xFFC4D7FF),
    secondary = Color(0xFFB2C3DF), onSecondary = Color(0xFF263B59),
    secondaryContainer = Color(0xFF2B3D58), onSecondaryContainer = Color(0xFFDCE7F7),
    background = Color(0xFF10141B), onBackground = Color(0xFFEEF3FA),
    surface = Color(0xFF1B2230), onSurface = Color(0xFFEEF3FA),
    surfaceContainerLowest = Color(0xFF0B0F16), surfaceContainerLow = Color(0xFF161D28),
    surfaceContainer = Color(0xFF202A39), surfaceContainerHigh = Color(0xFF273246), surfaceContainerHighest = Color(0xFF303D52),
    surfaceVariant = Color(0xFF273246), onSurfaceVariant = Color(0xFFAAB8CE),
    outline = Color(0xFF74849E), outlineVariant = Color(0xFF344157),
)
private val type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
)
@Composable fun ClientTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) dark else light, typography = type, content = content)
}
