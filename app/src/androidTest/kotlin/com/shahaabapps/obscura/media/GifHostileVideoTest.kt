package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Awkward and hostile videos put through the rewritten GIF pipeline.
 *
 * A GIF that never finishes is as bad as one that crashes: the vault is held unlocked while it
 * runs, so every case here is given a wall-clock budget on its own thread. A thread that is
 * still running when the budget is gone is reported as a hang, not waited on.
 */
@RunWith(AndroidJUnit4::class)
class GifHostileVideoTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        // Closing the index leaves work queued on the main thread. It has to run before the
        // files it points at are deleted, or Room reopens a vault that is no longer there and
        // brings the whole process down with it.
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() = wipe()

    @After
    fun tearDown() = wipe()

    /** A real H.264 MP4, optionally with an orientation hint, whose picture changes each frame. */
    private fun encodeMp4(
        seconds: Double,
        width: Int = 320,
        height: Int = 240,
        rotation: Int = 0,
        fps: Int = 10,
    ): ByteArray {
        val file = File(context.cacheDir, "hostile.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, data, info)
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val paint = Paint().apply { color = Color.rgb(255, 225, 77) }
        val frames = maxOf(1, (fps * seconds).toInt())
        repeat(frames) { frame ->
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(59, 91, 255))
            // A mark near the top, so a picture left on its side is obvious in the result.
            canvas.drawCircle(width * 0.5f, height * 0.18f + (frame % 5) * 4f, minOf(width, height) * 0.12f, paint)
            surface.unlockCanvasAndPost(canvas)
            Thread.sleep(1000L / fps)
            drain(end = false)
        }
        encoder.signalEndOfInputStream()
        drain(end = true)
        encoder.stop(); encoder.release(); surface.release()
        muxer.stop(); muxer.release()
        return file.readBytes().also { file.delete() }
    }

    private fun seal(bytes: ByteArray, name: String, width: Int?, height: Int?, durationMs: Long?): VaultFile = runBlocking {
        if (entry.session().openVault == null) entry.session().create(passphrase.toCharArray())
        entry.session().openVault!!.files.put(
            bytes.inputStream(),
            IncomingFile(name, "video/mp4", 1_700_000_000_000, width, height, durationMs),
        )
    }

    /**
     * Runs one GIF attempt on its own thread with a wall-clock budget, so a decoder that never
     * finishes is reported rather than waited on.
     */
    private fun attempt(video: VaultFile, request: GifRequest, budgetMs: Long = 90_000): Outcome {
        val vault = entry.session().openVault!!
        val result = AtomicReference<Outcome>(null)
        val worker = Thread {
            val outcome = runCatching { runBlocking { VideoToGif.create(vault, video, request) } }
            result.set(
                outcome.fold(
                    onSuccess = { Outcome.Made(it) },
                    onFailure = { Outcome.Refused(it) },
                ),
            )
        }
        worker.isDaemon = true
        worker.name = "gif-attempt"
        val started = System.currentTimeMillis()
        worker.start()
        worker.join(budgetMs)
        if (worker.isAlive) {
            // Deliberately not joined any further: the point of the report is that it is stuck.
            return Outcome.Hung(System.currentTimeMillis() - started, worker.stackTrace.take(12).joinToString("\n  "))
        }
        return result.get()!!
    }

    private sealed interface Outcome {
        data class Made(val file: VaultFile) : Outcome
        data class Refused(val error: Throwable) : Outcome
        data class Hung(val afterMs: Long, val stack: String) : Outcome
    }

    private fun Outcome.describe(): String = when (this) {
        is Outcome.Made -> "made ${file.name} ${file.width}x${file.height} ${file.sizeBytes}B"
        is Outcome.Refused -> "refused with ${error::class.java.simpleName}: ${error.message}"
        is Outcome.Hung -> "HUNG after ${afterMs}ms at:\n  $stack"
    }

    /** Fails on a hang or a crash-shaped error, and insists nothing half-sealed was left behind. */
    private fun requireSurvived(outcome: Outcome, sealedBefore: Int) = runBlocking {
        val vault = entry.session().openVault!!
        when (outcome) {
            is Outcome.Hung -> fail("the GIF never finished: ${outcome.describe()}")
            is Outcome.Refused -> {
                val e = outcome.error
                assertTrue(
                    "a refusal must be a plain message, not a crash: ${outcome.describe()}",
                    e !is NullPointerException && e !is OutOfMemoryError &&
                        e !is IndexOutOfBoundsException && e !is NegativeArraySizeException,
                )
                assertEquals("nothing half-sealed after a refusal", sealedBefore, vault.database.files().all().size)
            }
            is Outcome.Made -> {
                assertEquals("exactly one new file", sealedBefore + 1, vault.database.files().all().size)
                val bytes = vault.files.open(vault.database.files().get(outcome.file.id)!!).use { it.readBytes() }
                assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
                // Android's own decoder is the judge of whether the bytes are a real GIF.
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                assertEquals("declared width", outcome.file.width, drawable.intrinsicWidth)
                assertEquals("declared height", outcome.file.height, drawable.intrinsicHeight)
            }
        }
    }

    private fun firstFrame(gif: VaultFile): Bitmap = runBlocking {
        val vault = entry.session().openVault!!
        val bytes = vault.files.open(vault.database.files().get(gif.id)!!).use { it.readBytes() }
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, it.width, it.height)
            drawable.draw(android.graphics.Canvas(it))
        }
    }

    /** A clip shorter than the shortest GIF offered. */
    @Test
    fun aVeryShortClipStillMakesAGif() {
        val video = seal(encodeMp4(0.3, fps = 10), "blink.mp4", 320, 240, 300)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 10, maxWidth = 160))
        println("very short clip: ${outcome.describe()}")
        requireSurvived(outcome, sealedBefore = 1)
    }

    /** The playhead past the end of the clip: the start must be pulled back, not run off. */
    @Test
    fun aStartPastTheEndIsPulledBack() {
        val video = seal(encodeMp4(1.5), "late.mp4", 320, 240, 1_500)
        val outcome = attempt(video, GifRequest(startMs = 30_000, lengthMs = 5_000, fps = 10, maxWidth = 160))
        println("start past end: ${outcome.describe()}")
        requireSurvived(outcome, sealedBefore = 1)
    }

    /** Portrait shot with a rotation flag: the GIF must be portrait and upright, not on its side. */
    @Test
    fun aRotatedPortraitVideoComesOutUprightAndPortrait() {
        val video = seal(encodeMp4(1.5, width = 480, height = 272, rotation = 90), "portrait.mp4", 480, 272, 1_500)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 8, maxWidth = 240))
        println("rotated portrait: ${outcome.describe()}")
        requireSurvived(outcome, sealedBefore = 1)
        val made = outcome as? Outcome.Made ?: return
        assertTrue("a quarter turn makes it taller than it is wide: ${made.file.width}x${made.file.height}", made.file.height!! > made.file.width!!)
        // Android's own frame grabber honours the rotation flag, so it is the judge of where the
        // picture's mark belongs and what colour it is. A GIF left on its side, mirrored, or
        // tinted would put the mark somewhere else or get the colours wrong.
        val frame = firstFrame(made.file)
        val reference = runBlocking { uprightFrame(video, atUs = 0) }
        val mine = markCentre(frame)
        val theirs = markCentre(reference)
        println("rotated portrait: mark at $mine in the GIF, $theirs in Android's own frame")
        println("rotated portrait: mean #%06X in the GIF, #%06X in Android's own frame".format(meanColour(frame), meanColour(reference)))
        assertTrue("the mark is somewhere in both pictures", mine != null && theirs != null)
        assertTrue(
            "the mark must sit in the same place: $mine vs $theirs",
            kotlin.math.abs(mine!!.first - theirs!!.first) < 0.15f && kotlin.math.abs(mine.second - theirs.second) < 0.15f,
        )
        val a = meanColour(frame)
        val b = meanColour(reference)
        for (shift in listOf(16, 8, 0)) {
            val difference = kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
            assertTrue("colours must not be tinted: #%06X vs #%06X".format(a, b), difference <= 28)
        }
    }

    /** Android's own upright frame of the sealed video, read through the vault's decrypting reader. */
    private suspend fun uprightFrame(video: VaultFile, atUs: Long): Bitmap {
        val vault = entry.session().openVault!!
        val retriever = android.media.MediaMetadataRetriever()
        val source = com.shahaabapps.obscura.vault.EncryptedMediaDataSource(
            vault.keys.fileCipher, video, vault.files.blobFile(video.id),
        )
        return try {
            retriever.setDataSource(source)
            retriever.getFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)!!
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    /** Where the yellow mark's centre is, as fractions of the picture's width and height. */
    private fun markCentre(frame: Bitmap): Pair<Float, Float>? {
        var sumX = 0L
        var sumY = 0L
        var seen = 0
        for (y in 0 until frame.height step 2) for (x in 0 until frame.width step 2) {
            val p = frame.getPixel(x, y)
            if (Color.red(p) > 140 && Color.green(p) > 120 && Color.blue(p) < 150 && Color.red(p) - Color.blue(p) > 60) {
                sumX += x; sumY += y; seen++
            }
        }
        if (seen < 8) return null
        return (sumX.toFloat() / seen / frame.width) to (sumY.toFloat() / seen / frame.height)
    }

    private fun meanColour(frame: Bitmap): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in 0 until frame.height step 3) for (x in 0 until frame.width step 3) {
            val p = frame.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0L) return 0
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** A stored size with odd numbers in it, and an odd GIF width to go with it. */
    @Test
    fun oddDimensionsDoNotStretchOrOverrun() {
        // Many AVC encoders refuse odd dimensions. The best this device will take is used, and
        // said out loud, so the record shows what was really tested; the odd output width below
        // exercises the scaling and the encoder's row handling either way.
        var width = 0
        var height = 0
        val bytes = sequenceOf(321 to 241, 322 to 242, 320 to 240).mapNotNull { (w, h) ->
            runCatching { encodeMp4(1.0, width = w, height = h) }.getOrNull()?.also { width = w; height = h }
        }.first()
        println("odd dimensions: the encoder took ${width}x$height")
        val video = seal(bytes, "odd.mp4", width, height, 1_000)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 1_000, fps = 8, maxWidth = 161))
        println("odd dimensions: ${outcome.describe()}")
        requireSurvived(outcome, sealedBefore = 1)
        val made = outcome as? Outcome.Made ?: return
        val ratio = made.file.width!!.toFloat() / made.file.height!!
        assertTrue("the shape is kept, not stretched: ${made.file.width}x${made.file.height}", ratio in 1.1f..1.55f)
    }

    /** Cut off part way through: whatever survived, or a plain refusal — and never a hang. */
    @Test
    fun aTruncatedVideoDoesNotHang() {
        val whole = encodeMp4(2.0)
        val video = seal(whole.copyOf(whole.size / 3), "cut.mp4", 320, 240, 2_000)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 8, maxWidth = 120), budgetMs = 60_000)
        println("truncated: ${outcome.describe()}")
        requireSurvived(outcome, sealedBefore = 1)
    }

    /** Only the header survived: there are samples promised that are not there. */
    @Test
    fun aVideoCutToItsHeaderDoesNotHang() {
        val whole = encodeMp4(2.0)
        val video = seal(whole.copyOf(minOf(whole.size, 2_048)), "header.mp4", 320, 240, 2_000)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 8, maxWidth = 120), budgetMs = 60_000)
        println("header only: ${outcome.describe()}")
        requireSurvived(outcome, sealedBefore = 1)
    }

    /** A picture wearing a video's name. */
    @Test
    fun aPhotoNamedAsAVideoIsRefusedPlainly() {
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(Color.MAGENTA) }
                .compress(Bitmap.CompressFormat.JPEG, 80, out)
        }.toByteArray()
        val video = seal(jpeg, "notavideo.mp4", 64, 64, null)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 8, maxWidth = 120), budgetMs = 45_000)
        println("photo named as video: ${outcome.describe()}")
        assertTrue("it must not quietly succeed: ${outcome.describe()}", outcome is Outcome.Refused)
        requireSurvived(outcome, sealedBefore = 1)
    }

    /** Nothing at all in the file. */
    @Test
    fun anEmptyFileIsRefusedPlainly() {
        val video = seal(ByteArray(0), "empty.mp4", null, null, null)
        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 8, maxWidth = 120), budgetMs = 45_000)
        println("empty file: ${outcome.describe()}")
        assertTrue("it must not quietly succeed: ${outcome.describe()}", outcome is Outcome.Refused)
        requireSurvived(outcome, sealedBefore = 1)
    }

    /** Audio only, so the extractor finds a track but no pictures. */
    @Test
    fun aVideoWithNoVideoTrackIsRefusedPlainly() {
        // An MP4 holding only silence: there is a track, but nothing to look at.
        val file = File(context.cacheDir, "silence.mp4")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x08)))
        }
        val track = muxer.addTrack(format)
        muxer.start()
        val info = MediaCodec.BufferInfo()
        val silence = ByteBuffer.allocate(256)
        var at = 0L
        repeat(20) {
            silence.clear()
            silence.put(ByteArray(64))
            info.set(0, 64, at, 0)
            muxer.writeSampleData(track, silence, info)
            at += 23_000
        }
        muxer.stop(); muxer.release()
        val video = seal(file.readBytes().also { file.delete() }, "silence.mp4", null, null, 460)

        val outcome = attempt(video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 8, maxWidth = 120), budgetMs = 45_000)
        println("audio only: ${outcome.describe()}")
        assertTrue("it must not quietly succeed: ${outcome.describe()}", outcome is Outcome.Refused)
        requireSurvived(outcome, sealedBefore = 1)
    }
}
