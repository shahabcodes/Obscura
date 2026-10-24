package com.shahaabapps.obscura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shahaabapps.obscura.R

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
 * Display and label styles: Space Grotesk (one variable font file, weights 300–700) and
 * JetBrains Mono. Both are bundled under res/font with their OFL licences in assets/licences;
 * fonts are never downloaded, because the app has no network access.
 */
object PrismType {
    // The variation-settings overload is still marked experimental; it sets the variable font's weight axis.
    @OptIn(ExperimentalTextApi::class)
    private fun grotesk(weight: Int) =
        Font(R.font.space_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    private val Display = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
    private val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    )

    /** For text outside the named styles, such as Material defaults. */
    val displayFamily: FontFamily get() = Display

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
        // Every Material slot gets Space Grotesk, so default component text matches the named styles.
        typography = Typography().let { base ->
            val family = PrismType.displayFamily
            base.copy(
                displayLarge = PrismType.hero,
                displayMedium = base.displayMedium.copy(fontFamily = family),
                displaySmall = base.displaySmall.copy(fontFamily = family),
                headlineLarge = base.headlineLarge.copy(fontFamily = family),
                headlineMedium = PrismType.title,
                headlineSmall = base.headlineSmall.copy(fontFamily = family),
                titleLarge = PrismType.heading,
                titleMedium = base.titleMedium.copy(fontFamily = family),
                titleSmall = base.titleSmall.copy(fontFamily = family),
                bodyLarge = PrismType.body,
                bodyMedium = base.bodyMedium.copy(fontFamily = family),
                bodySmall = base.bodySmall.copy(fontFamily = family),
                labelLarge = PrismType.button,
                labelMedium = base.labelMedium.copy(fontFamily = family),
                labelSmall = PrismType.label,
            )
        },
        content = content,
    )
}
