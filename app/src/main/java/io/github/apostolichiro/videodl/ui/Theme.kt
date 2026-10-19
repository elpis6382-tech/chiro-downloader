package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Snow and ice: white / true black, frosted greys, one glacier-blue accent.
// The download button is the one loud thing on screen (a blue gradient); everything else stays quiet.
private val Light = lightColorScheme(
    primary = Color(0xFF2569E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EDFF),
    onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF5B6574),
    secondaryContainer = Color(0xFFEDF1F6),
    onSecondaryContainer = Color(0xFF1B222C),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF10141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10141A),
    surfaceVariant = Color(0xFFF1F4F8),
    onSurfaceVariant = Color(0xFF677180),
    surfaceContainer = Color(0xFFF4F6F9),
    surfaceContainerHigh = Color(0xFFECEFF4),
    surfaceContainerLow = Color(0xFFF8F9FB),
    outline = Color(0xFFD9DEE6),
    outlineVariant = Color(0xFFE8ECF1),
    error = Color(0xFFD93A40),
    tertiary = Color(0xFF14915F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FA6FF),
    onPrimary = Color(0xFF00214F),
    primaryContainer = Color(0xFF15305E),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFFAAB3C0),
    secondaryContainer = Color(0xFF1B1F25),
    onSecondaryContainer = Color(0xFFDDE3EB),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDF1F6),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDF1F6),
    surfaceVariant = Color(0xFF16191E),
    onSurfaceVariant = Color(0xFF8E97A4),
    surfaceContainer = Color(0xFF111317),
    surfaceContainerHigh = Color(0xFF1A1D22),
    surfaceContainerLow = Color(0xFF0A0B0D),
    outline = Color(0xFF2A2F37),
    outlineVariant = Color(0xFF1C2026),
    error = Color(0xFFFF6B6F),
    tertiary = Color(0xFF3FD596),
)

@Immutable
data class Accents(val fab: Brush, val fabIdle: Color, val onFab: Color, val badge: Color)

val LocalAccents = staticCompositionLocalOf {
    Accents(Brush.verticalGradient(listOf(Color(0xFF63B3FF), Color(0xFF2563EB))), Color(0xFF8A94A3), Color.White, Color(0xFFFF4D5A))
}

private val type = Typography().let { t ->
    t.copy(
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun VideoDlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accents = if (dark) {
        Accents(Brush.verticalGradient(listOf(Color(0xFF7CC0FF), Color(0xFF3A74F0))), Color(0xFF3A4048), Color.White, Color(0xFFFF5A66))
    } else {
        Accents(Brush.verticalGradient(listOf(Color(0xFF5AB0FF), Color(0xFF2161E6))), Color(0xFFB4BCC8), Color.White, Color(0xFFF0384A))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAccents provides accents) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = type, content = content)
    }
}
