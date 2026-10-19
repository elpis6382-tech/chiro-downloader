package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cool blue-grey neutrals with one indigo accent; green for "done", red for "failed".
private val Light = lightColorScheme(
    primary = Color(0xFF3949AB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF0B1660),
    secondary = Color(0xFF555D7E),
    secondaryContainer = Color(0xFFE1E3F2),
    onSecondaryContainer = Color(0xFF1A1E33),
    background = Color(0xFFF3F4F9),
    onBackground = Color(0xFF161925),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161925),
    surfaceVariant = Color(0xFFE4E6F1),
    onSurfaceVariant = Color(0xFF5A5F74),
    outline = Color(0xFFD5D8E6),
    outlineVariant = Color(0xFFE4E6F1),
    error = Color(0xFFB3261E),
    tertiary = Color(0xFF1D7A3A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4BEFF),
    onPrimary = Color(0xFF0E1A6B),
    primaryContainer = Color(0xFF2B378C),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC0C5E2),
    secondaryContainer = Color(0xFF2A2F45),
    onSecondaryContainer = Color(0xFFDFE2F5),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE3E5F0),
    surface = Color(0xFF181B24),
    onSurface = Color(0xFFE3E5F0),
    surfaceVariant = Color(0xFF242836),
    onSurfaceVariant = Color(0xFFA3A8BC),
    outline = Color(0xFF2E3344),
    outlineVariant = Color(0xFF242836),
    error = Color(0xFFFF8A80),
    tertiary = Color(0xFF5BD17E),
)

@Composable
fun VideoDlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
