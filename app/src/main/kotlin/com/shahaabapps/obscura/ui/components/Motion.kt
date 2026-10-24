package com.shahaabapps.obscura.ui.components

import android.provider.Settings
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.shahaabapps.obscura.R
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import kotlinx.coroutines.flow.first

/**
 * The Prism motion kit: Lottie animations bundled in res/raw (built by
 * tools/lottie/generate_motion.py). Nothing is ever fetched; the app has no network access.
 *
 * [still] is the frame shown when the system's "remove animations" setting is on.
 */
enum class Motion(@RawRes val res: Int, val loop: Boolean, val still: Float) {
    /** PIN accepted or fingerprint matched. */
    UNLOCK(R.raw.motion_unlock, loop = false, still = 1f),
    /** A file dropping into the vault while it is encrypted. Has a sun-coloured hole: use on sun. */
    SEAL(R.raw.motion_seal, loop = true, still = 0.45f),
    /** Heart burst for favourites; ends fully faded. */
    FAVORITE(R.raw.motion_favorite, loop = false, still = 1f),
    /** Radar sweep for the duplicate finder and backup checks. */
    SCAN(R.raw.motion_scan, loop = true, still = 0.3f),
    /** Mint disc with a check mark: space freed, backup done. */
    SUCCESS(R.raw.motion_success, loop = false, still = 1f),
    /** Floating shapes for empty lists. Has an ink triangle: use on mint. */
    EMPTY(R.raw.motion_empty, loop = true, still = 0f),
    /** Shimmering tiles while something is decrypted into memory. */
    DECRYPT(R.raw.motion_decrypt, loop = true, still = 0f),
    /** Shock rings and a bolt after the panic lock. Ink artwork: use on pink. */
    PANIC(R.raw.motion_panic, loop = false, still = 1f),
}

/** True when the user turned animations off (Settings → Accessibility → Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Plays a [Motion]. Loops run until the composable leaves; one-shots play once and then call
 * [onFinished] (also called straight away if animations are off or the file can't be read).
 * Wrap a one-shot in `key(...)` to replay it.
 *
 * [recolor] maps a layer name, or `layer/group`, to a colour, so artwork can sit on another
 * background. Animations are decorative and hidden from screen readers.
 */
@Composable
fun MotionArt(
    motion: Motion,
    modifier: Modifier = Modifier,
    recolor: Map<String, Color> = emptyMap(),
    onFinished: (() -> Unit)? = null,
) {
    val result = rememberLottieComposition(LottieCompositionSpec.RawRes(motion.res))
    val composition = result.value
    val reduceMotion = rememberReduceMotion()
    val animation = animateLottieCompositionAsState(
        composition = composition,
        isPlaying = !reduceMotion,
        iterations = if (motion.loop) LottieConstants.IterateForever else 1,
    )
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(composition, reduceMotion, result.isFailure) {
        if (motion.loop) return@LaunchedEffect
        when {
            result.isFailure || reduceMotion -> finished?.invoke()
            composition != null -> {
                snapshotFlow { animation.isAtEnd && !animation.isPlaying }.first { it }
                finished?.invoke()
            }
        }
    }

    val properties = recolor.flatMap { (path, color) ->
        val keyPath = path.split('/').toTypedArray() + "**"
        listOf(
            rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), *keyPath),
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, color.toArgb(), *keyPath),
        )
    }
    val dynamicProperties = rememberLottieDynamicProperties(*properties.toTypedArray())

    LottieAnimation(
        composition = composition,
        progress = { if (reduceMotion) motion.still else animation.progress },
        modifier = modifier.clearAndSetSemantics { },
        dynamicProperties = if (recolor.isEmpty()) null else dynamicProperties,
    )
}

/** An empty list: the floating-shapes loop on a mint block, a title, and an optional line and action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 200.dp, height = 150.dp).prismBlock(Prism.Mint, 28), contentAlignment = Alignment.Center) {
            MotionArt(Motion.EMPTY, Modifier.size(width = 168.dp, height = 144.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = PrismType.heading, color = Prism.Paper, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = PrismType.body, color = Prism.Muted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** Shimmering tiles while a file is decrypted into memory. */
@Composable
fun DecryptingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.semantics { contentDescription = "Decrypting" }, contentAlignment = Alignment.Center) {
        MotionArt(Motion.DECRYPT, Modifier.size(width = 120.dp, height = 80.dp))
    }
}

/** [Motion.SUCCESS] recoloured to sit on a mint card: ink disc and ring, mint check. */
val SuccessOnMint: Map<String, Color> = mapOf("circle" to Prism.Ink, "ring" to Prism.Ink, "check" to Prism.Mint)
