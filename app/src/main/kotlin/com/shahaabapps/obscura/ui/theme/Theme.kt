package com.shahaabapps.obscura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Prism palette: black ground, bold block colours, one accent at a time. */
object Prism {
    val Ink = Color(0xFF0B0B0C)
    val Surface = Color(0xFF17171A)
    val SurfaceHigh = Color(0xFF1E1E22)
    val Line = Color(0xFF2A2A30)
    val Paper = Color(0xFFF5F5F0)
    val Muted = Color(0xFFA3A39B)
    val Sun = Color(0xFFFFE14D)
    val Pink = Color(0xFFFF7AB6)
    val Mint = Color(0xFF5EF0C2)
    val Cobalt = Color(0xFF3B5BFF)
    val Orange = Color(0xFFFF8A3D)
}

/**
 * Display and label styles. System families stand in for Space Grotesk and JetBrains Mono
 * until the OFL font files are bundled under res/font (fonts must ship in the APK, never
 * be downloaded, because the app has no network access).
 */
object PrismType {
    private val Display = FontFamily.SansSerif
    private val Mono = FontFamily.Monospace

    val hero = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1.6).sp)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = (-1.1).sp)
    val heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp)
    val body = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val button = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp)
    val stat = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-2.5).sp)
}

private val PrismColors = darkColorScheme(
    primary = Prism.Sun,
    onPrimary = Prism.Ink,
    secondary = Prism.Pink,
    onSecondary = Prism.Ink,
    tertiary = Prism.Mint,
    onTertiary = Prism.Ink,
    background = Prism.Ink,
    onBackground = Prism.Paper,
    surface = Prism.Surface,
    onSurface = Prism.Paper,
    surfaceVariant = Prism.SurfaceHigh,
    onSurfaceVariant = Prism.Muted,
    outline = Prism.Line,
    error = Prism.Orange,
    onError = Prism.Ink,
)

@Composable
fun ObscuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PrismColors,
        typography = Typography(
            displayLarge = PrismType.hero,
            headlineMedium = PrismType.title,
            titleLarge = PrismType.heading,
            bodyLarge = PrismType.body,
            labelLarge = PrismType.button,
            labelSmall = PrismType.label,
        ),
        content = content,
    )
}
