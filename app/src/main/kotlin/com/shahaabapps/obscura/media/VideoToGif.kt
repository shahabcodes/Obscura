package com.shahaabapps.obscura.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.shahaabapps.obscura.vault.EncryptedMediaDataSource
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.OpenVault
import com.shahaabapps.obscura.vault.VaultFile
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How a GIF should be made from a stretch of video. */
data class GifRequest(
    val startMs: Long,
    val lengthMs: Long,
    val fps: Int = 10,
    val maxWidth: Int = 480,
) {
    init {
        require(fps in 1..50) { "A GIF runs at 1 to 50 frames a second" }
        require(maxWidth in 16..1920) { "A GIF is between 16 and 1920 pixels wide" }
        require(lengthMs > 0) { "A GIF needs some length" }
    }

    val frameCount: Int get() = ((lengthMs * fps) / 1000).toInt().coerceIn(1, MAX_FRAMES)

    companion object {
        /** Enough for several seconds; beyond this a GIF is the wrong format anyway. */
        const val MAX_FRAMES = 150

        /**
         * A cap on the whole picture, not just its width: a video may declare itself 480 by
         * 60000, and a frame that shape would be a hundred megabytes on its own.
         */
        const val MAX_PIXELS = 480 * 960

        /** The finished GIF is held in memory, and one bigger than this could not be shown anyway. */
        const val MAX_BYTES = 32L * 1024 * 1024

        val lengths = listOf(2_000L, 3_000L, 5_000L)
    }
}

/**
 * Turns part of a sealed video into an animated GIF, sealed straight back into the vault.
 *
 * Frames are pulled from the encrypted file through the same decrypting reader the players use,
 * so nothing is written to storage along the way, and the finished GIF is sealed like any other
 * import. Colours are chosen from a handful of sampled frames so the whole clip shares one
 * palette, which is what keeps a GIF small.
 */
object VideoToGif {

    /**
     * @param onProgress how far along, from 0 to 1.
     * @return the new sealed file.
     * @throws IllegalStateException if the video has no frames to read.
     */
    suspend fun create(
        vault: OpenVault,
        video: VaultFile,
        request: GifRequest,
        stillOpen: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): VaultFile = withContext(Dispatchers.IO) {
        // Checked alongside cancellation: a locked vault's frames are not ours to read.
        fun check() {
            if (!stillOpen()) throw kotlinx.coroutines.CancellationException("The vault locked")
        }
        val retriever = MediaMetadataRetriever()
        val source = EncryptedMediaDataSource(vault.keys.fileCipher, video, vault.files.blobFile(video.id))
        try {
            retriever.setDataSource(source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: video.durationMs ?: 0L
            val start = request.startMs.coerceIn(0, maxOf(0, durationMs - 200))
            // A file that does not say how long it is gets the length asked for; clamping against
            // a duration of zero would otherwise cut every such GIF down to a fifth of a second.
            val length =
                if (durationMs <= 0) request.lengthMs
                else request.lengthMs.coerceAtMost(maxOf(200, durationMs - start))
            // From what is left of the video, not what was asked for: a 5-second GIF of the last
            // 200 milliseconds would otherwise be fifty copies of one frozen instant.
            val frames = ((length * request.fps) / 1000).toInt().coerceIn(1, GifRequest.MAX_FRAMES)
            val step = (length * 1000) / frames // microseconds between frames

            val size = frameSize(retriever, video, request.maxWidth)
            fun frameAt(index: Int): Bitmap? =
                retriever.getScaledFrameAtTime(
                    start * 1000 + index * step,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    size.first,
                    size.second,
                )

            // One palette for the whole clip, from frames spread across it.
            val quantizer = Quantizer()
            val samples = (0 until frames step maxOf(1, frames / 6)).take(6)
            var sampled = 0
            for (index in samples) {
                coroutineContext.ensureActive()
                check()
                val bitmap = frameAt(index) ?: continue
                quantizer.sample(bitmap.pixels())
                bitmap.recycle()
                sampled++
                onProgress(0.25f * (sampled.toFloat() / samples.size))
            }
            check(sampled > 0) { "No frames could be read from this video" }
            val palette = quantizer.palette()
            val mapper = Quantizer.Mapper(palette)

            val out = ByteArrayOutputStream()
            val encoder = GifEncoder(out, size.first, size.second, palette)
            val indices = ByteArray(size.first * size.second)
            val delayCentis = (100 / request.fps).coerceAtLeast(2)
            var written = 0
            for (index in 0 until frames) {
                coroutineContext.ensureActive()
                check()
                val bitmap = frameAt(index) ?: continue
                mapper.map(bitmap.pixels(), indices)
                bitmap.recycle()
                encoder.addFrame(indices, delayCentis)
                check(out.size() <= GifRequest.MAX_BYTES) { "This clip makes too large a GIF; try a shorter one" }
                written++
                onProgress(0.25f + 0.7f * (index + 1f) / frames)
            }
            check(written > 0) { "No frames could be read from this video" }
            encoder.finish()

            val name = "${video.name.substringBeforeLast('.', video.name)}.gif"
            vault.files.put(
                out.toByteArray().inputStream(),
                IncomingFile(name, "image/gif", System.currentTimeMillis(), size.first, size.second, length),
            ).also { onProgress(1f) }
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    /** The output size: the video's own shape, no wider than asked, and never more than [GifRequest.MAX_PIXELS]. */
    internal fun frameSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return maxWidth.coerceAtLeast(2) to maxWidth.coerceAtLeast(2)
        var width = minOf(maxWidth, sourceWidth).toDouble().coerceAtLeast(2.0)
        var height = (width * sourceHeight / sourceWidth).coerceAtLeast(2.0)
        // A very tall or very wide video is scaled down as a whole, keeping its shape.
        val pixels = width * height
        if (pixels > GifRequest.MAX_PIXELS) {
            val scale = sqrt(GifRequest.MAX_PIXELS / pixels)
            width *= scale
            height *= scale
        }
        // Rounding can nudge the area back over the budget, so the height is clamped to it.
        val finalWidth = width.roundToInt().coerceAtLeast(2)
        val room = (GifRequest.MAX_PIXELS / finalWidth).coerceAtLeast(2)
        return finalWidth to height.roundToInt().coerceAtLeast(2).coerceAtMost(room)
    }

    private fun frameSize(retriever: MediaMetadataRetriever, video: VaultFile, maxWidth: Int): Pair<Int, Int> {
        fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
        val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
        val rawWidth = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: video.width ?: maxWidth
        val rawHeight = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: video.height ?: maxWidth
        // A quarter turn swaps the sides.
        val (sourceWidth, sourceHeight) = if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight
        return frameSize(sourceWidth, sourceHeight, maxWidth)
    }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
}
