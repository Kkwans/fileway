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

private val light = lightColorScheme(primary = Color(0xFF235ED7), onPrimary = Color.White, background = Color(0xFFF5F7FA), surface = Color.White, onSurface = Color(0xFF172235), onSurfaceVariant = Color(0xFF536176), outlineVariant = Color(0xFFDCE3EE))
private val dark = darkColorScheme(primary = Color(0xFF9AB9FF), onPrimary = Color(0xFF123575), background = Color(0xFF10141B), surface = Color(0xFF1B2230), onSurface = Color(0xFFEEF3FA), onSurfaceVariant = Color(0xFFAAB8CE), outlineVariant = Color(0xFF344157))
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
