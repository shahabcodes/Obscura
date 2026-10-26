package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How close the GIF looks to the video it came from, and how long it takes to make. A gradient
 * is the hard case: smooth shading is where too few colours shows up as patches and banding.
 */
@RunWith(AndroidJUnit4::class)
class GifQualityTest {

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
            entry.session().reload()
        }
    }

    @Before fun setUp() = wipe()

    @After fun tearDown() = wipe()

    /** A clip with a wide sky-like gradient and a moving shape over it. */
    private fun gradientMp4(seconds: Int, width: Int = 480, height: Int = 270): ByteArray {
        val file = File(context.cacheDir, "gradient.mp4")
        val fps = 10
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat); muxer.start()
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
        val sky = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(32, 68, 160), Color.rgb(120, 170, 228), Color.rgb(238, 214, 176)),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val sun = Paint().apply { color = Color.rgb(255, 232, 120); isAntiAlias = true }
        repeat(fps * seconds) { frame ->
            val canvas = surface.lockCanvas(null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
            canvas.drawCircle(40f + frame * 7f, height * 0.35f, 26f, sun)
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

    private fun seal(bytes: ByteArray): VaultFile = runBlocking {
        entry.session().create(passphrase.toCharArray())
        entry.session().openVault!!.files.put(
            bytes.inputStream(),
            IncomingFile("sky.mp4", "video/mp4", 1_700_000_000_000, 480, 270, 3_000),
        )
    }

    /** The first frame of the GIF, as Android decodes it. */
    private fun firstFrame(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }

    /** The video's own first frame at the GIF's size, to compare against. */
    private fun sourceFrame(video: VaultFile, width: Int, height: Int): Bitmap {
        val vault = entry.session().openVault!!
        val retriever = android.media.MediaMetadataRetriever()
        val source = com.shahaabapps.obscura.vault.EncryptedMediaDataSource(
            vault.keys.fileCipher, video, vault.files.blobFile(video.id),
        )
        try {
            retriever.setDataSource(source)
            return retriever.getScaledFrameAtTime(
                0, android.media.MediaMetadataRetriever.OPTION_CLOSEST, width, height,
            )!!
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    /**
     * Average distance per pixel between the GIF's first frame and the video's own first frame,
     * in RGB units out of 255. Smaller is closer to the original.
     */
    private fun difference(gif: Bitmap, source: Bitmap): Double {
        var total = 0.0
        var counted = 0
        for (y in 0 until minOf(gif.height, source.height)) {
            for (x in 0 until minOf(gif.width, source.width)) {
                val a = gif.getPixel(x, y)
                val b = source.getPixel(x, y)
                val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
                val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
                val db = (a and 0xFF) - (b and 0xFF)
                total += sqrt((dr * dr + dg * dg + db * db).toDouble())
                counted++
            }
        }
        return total / counted
    }

    /** How many distinct colours survive in the sky, which is where banding shows. */
    private fun coloursInTopHalf(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height / 2) for (x in 0 until bitmap.width) seen += bitmap.getPixel(x, y)
        return seen.size
    }

    /** Where the time actually goes: pulling frames out of the video, or everything else. */
    @Test
    fun whereTheTimeGoes(): Unit = runBlocking {
        val video = seal(gradientMp4(3))
        val vault = entry.session().openVault!!

        val whole = System.currentTimeMillis()
        VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 10, maxWidth = 480))
        val wholeMs = System.currentTimeMillis() - whole

        // The same 26 frame reads the run above makes, and nothing else.
        val retriever = android.media.MediaMetadataRetriever()
        val source = com.shahaabapps.obscura.vault.EncryptedMediaDataSource(
            vault.keys.fileCipher, video, vault.files.blobFile(video.id),
        )
        val reads = System.currentTimeMillis()
        try {
            retriever.setDataSource(source)
            repeat(26) { index ->
                retriever.getScaledFrameAtTime(
                    index * 100_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST, 480, 270,
                )?.recycle()
            }
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
        val readsMs = System.currentTimeMillis() - reads
        Log.i("GIFQUALITY", "TIMING whole=${wholeMs}ms frameReads=${readsMs}ms rest=${wholeMs - readsMs}ms")
    }

    @Test
    fun aGifOfAGradientStaysCloseToTheVideo() = runBlocking {
        val video = seal(gradientMp4(3))
        val vault = entry.session().openVault!!

        val began = System.currentTimeMillis()
        val gif = VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 10, maxWidth = 480))
        val took = System.currentTimeMillis() - began

        val bytes = vault.files.open(vault.database.files().get(gif.id)!!).use { it.readBytes() }
        val made = firstFrame(bytes)
        val source = sourceFrame(video, made.width, made.height)

        val error = difference(made, source)
        val colours = coloursInTopHalf(made)
        Log.i("GIFQUALITY", "QUALITY error=$error colours=$colours bytes=${bytes.size} tookMs=$took")

        assertTrue("average pixel error $error is too far from the video", error < 12.0)
        assertTrue("only $colours colours in the sky, which means banding", colours > 40)
    }
}
