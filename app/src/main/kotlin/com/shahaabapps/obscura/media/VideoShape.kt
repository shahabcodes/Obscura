package com.shahaabapps.obscura.media

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.VideoSize

/** The shape of the picture: width over height, counting non-square pixels. Null until known. */
fun VideoSize.aspectOrNull(): Float? =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else null

/**
 * Gives [content] the largest area inside this space that still has the video's own shape, so a
 * tall video is not stretched sideways to fill a wide screen, or the other way round. Without
 * this the surface simply takes whatever space it is given and the picture is squeezed.
 */
@Composable
fun FitVideo(aspect: Float?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = aspect?.takeIf { it.isFinite() && it > 0f }
        // One call site, whichever shape is chosen: calling [content] from two places would make
        // two composition groups, and the video surface would be thrown away and rebuilt — a
        // black flash mid-playback — each time the shape became known or stopped being known.
        val fitted = when {
            ratio == null || maxHeight.value <= 0f -> Modifier.fillMaxWidth().fillMaxHeight()
            // Wider than the space: let it span the width. Taller: let it span the height.
            ratio >= maxWidth / maxHeight -> Modifier.fillMaxWidth().aspectRatio(ratio)
            else -> Modifier.fillMaxHeight().aspectRatio(ratio)
        }
        content(fitted)
    }
}
