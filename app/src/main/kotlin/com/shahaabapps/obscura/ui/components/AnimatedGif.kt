package com.shahaabapps.obscura.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The largest GIF worth holding in memory at once. Real ones are far smaller; beyond this the
 * still preview is shown instead, because the whole file has to be decrypted to animate it.
 */
const val MAX_ANIMATED_BYTES = 32L * 1024 * 1024

/** Keeps one frame's bitmap sane even if the GIF declares absurd dimensions. */
private const val MAX_FRAME_SIDE = 2048

/**
 * A GIF playing from bytes decrypted in memory. Android's own decoder handles the frames; it
 * needs the whole file, which is why GIFs are read in full rather than streamed. Nothing is
 * written to storage.
 *
 * [bytes] null means still loading, and nothing is drawn.
 */
@Composable
fun AnimatedGif(
    bytes: ByteArray?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    playing: Boolean = true,
) {
    val drawable by produceState<Drawable?>(null, bytes) {
        val data = bytes ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_FRAME_SIDE) decoder.setTargetSampleSize(longest / MAX_FRAME_SIDE + 1)
                }
            }.getOrNull()
        }
    }
    val animation = drawable as? AnimatedImageDrawable

    DisposableEffect(animation) {
        onDispose { animation?.stop() }
    }
    DisposableEffect(animation, playing) {
        if (playing) animation?.start() else animation?.stop()
        onDispose { }
    }

    if (drawable != null) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { view ->
                view.setImageDrawable(drawable)
                view.contentDescription = contentDescription
            },
            modifier = modifier,
        )
    }
}
