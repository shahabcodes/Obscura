package com.shahaabapps.obscura.media

import android.media.Image
import android.media.MediaFormat

/**
 * Turns a decoded video frame into ARGB pixels, scaled to the size asked for and turned upright.
 *
 * Video is stored as brightness plus two colour channels at half resolution, which is not what a
 * picture format wants. Getting the sums right matters: the wrong coefficients wash colours out
 * or tint them, which would show up as surely as too few colours would.
 */
internal class YuvToRgb(format: MediaFormat, sourceWidth: Int, sourceHeight: Int) {

    private val fullRange: Boolean
    private val vToR: Int
    private val vToG: Int
    private val uToG: Int
    private val uToB: Int
    private val yScale: Int

    init {
        val standard = format.integerOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val range = format.integerOrNull(MediaFormat.KEY_COLOR_RANGE)
        // High definition is BT.709, standard definition BT.601, which is the usual assumption
        // when a file does not say. Most video is limited range, where black is 16 and white 235.
        val bt709 = standard?.let { it == MediaFormat.COLOR_STANDARD_BT709 }
            ?: (maxOf(sourceWidth, sourceHeight) >= 1280)
        fullRange = range?.let { it == MediaFormat.COLOR_RANGE_FULL } ?: false

        if (bt709) {
            vToR = (1.793 * ONE).toInt()
            vToG = (-0.534 * ONE).toInt()
            uToG = (-0.213 * ONE).toInt()
            uToB = (2.115 * ONE).toInt()
        } else {
            vToR = (1.596 * ONE).toInt()
            vToG = (-0.813 * ONE).toInt()
            uToG = (-0.391 * ONE).toInt()
            uToB = (2.018 * ONE).toInt()
        }
        yScale = if (fullRange) ONE else (1.164 * ONE).toInt()
    }

    /**
     * Writes [image] into [into] as [width] by [height] ARGB pixels, turning it [rotation]
     * degrees so it is the right way up. Pixels are picked rather than blended, which is what
     * keeps this quick; the GIF is smaller than the video either way.
     */
    fun into(image: Image, into: IntArray, width: Int, height: Int, rotation: Int) {
        val crop = image.cropRect
        val sourceWidth = crop.width()
        val sourceHeight = crop.height()
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return

        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val yRow = y.rowStride
        val yPixel = y.pixelStride
        val uRow = u.rowStride
        val uPixel = u.pixelStride
        val vRow = v.rowStride
        val vPixel = v.pixelStride

        // The picture as seen, which for a quarter turn has the sides swapped.
        val turned = rotation == 90 || rotation == 270
        val seenWidth = if (turned) sourceHeight else sourceWidth
        val seenHeight = if (turned) sourceWidth else sourceHeight

        for (row in 0 until height) {
            val seenY = (row.toLong() * seenHeight / height).toInt().coerceIn(0, seenHeight - 1)
            for (column in 0 until width) {
                val seenX = (column.toLong() * seenWidth / width).toInt().coerceIn(0, seenWidth - 1)
                // Back from where it is seen to where it is stored.
                val sourceX: Int
                val sourceY: Int
                when (rotation) {
                    90 -> { sourceX = seenY; sourceY = sourceHeight - 1 - seenX }
                    180 -> { sourceX = sourceWidth - 1 - seenX; sourceY = sourceHeight - 1 - seenY }
                    270 -> { sourceX = sourceWidth - 1 - seenY; sourceY = seenX }
                    else -> { sourceX = seenX; sourceY = seenY }
                }
                val x = (sourceX + crop.left).coerceIn(0, crop.right - 1)
                val yy = (sourceY + crop.top).coerceIn(0, crop.bottom - 1)

                val luma = yBuffer.get(yy * yRow + x * yPixel).toInt() and 0xFF
                val half = (yy shr 1) * uRow + (x shr 1) * uPixel
                val blue = uBuffer.get(half).toInt() and 0xFF
                val red = vBuffer.get((yy shr 1) * vRow + (x shr 1) * vPixel).toInt() and 0xFF

                val base = ((if (fullRange) luma else luma - 16).coerceAtLeast(0)) * yScale
                val cb = blue - 128
                val cr = red - 128
                val r = (base + cr * vToR) shr SHIFT
                val g = (base + cr * vToG + cb * uToG) shr SHIFT
                val b = (base + cb * uToB) shr SHIFT
                into[row * width + column] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
            }
        }
    }

    private companion object {
        const val SHIFT = 10
        const val ONE = 1 shl SHIFT

        fun MediaFormat.integerOrNull(key: String): Int? =
            if (containsKey(key)) getInteger(key) else null
    }
}
