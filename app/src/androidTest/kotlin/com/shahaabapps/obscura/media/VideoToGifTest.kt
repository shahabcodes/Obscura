package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Turning a stretch of a sealed video into a GIF, sealed back into the vault. */
@RunWith(AndroidJUnit4::class)
class VideoToGifTest {

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

    /** A real H.264 MP4 whose picture changes, so the frames differ from one another. */
    private fun encodeMp4(seconds: Int, width: Int = 320, height: Int = 240): ByteArray {
        val file = File(context.cacheDir, "to-gif.mp4")
        val fps = 10
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
        repeat(fps * seconds) { frame ->
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(59, 91, 255))
            canvas.drawCircle(20f + (frame % 25) * 11f, height / 2f, 36f, paint)
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

    private fun sealVideo(bytes: ByteArray): VaultFile = runBlocking {
        entry.session().create(passphrase.toCharArray())
        entry.session().openVault!!.files.put(
            bytes.inputStream(),
            IncomingFile("holiday.mp4", "video/mp4", 1_700_000_000_000, 320, 240, 3_000),
        )
    }

    @Test
    fun aGifIsMadeFromTheVideoAndSealedInTheVault() = runBlocking {
        val video = sealVideo(encodeMp4(3))
        val vault = entry.session().openVault!!
        val seen = mutableListOf<Float>()

        val gif = VideoToGif.create(vault, video, GifRequest(startMs = 200, lengthMs = 2_000, fps = 8, maxWidth = 160)) { seen += it }

        // It is a file in the vault, of the new kind, named after the video.
        assertEquals(FileKind.GIF, gif.kind)
        assertEquals("holiday.gif", gif.name)
        assertEquals("image/gif", gif.mimeType)
        assertTrue("size ${gif.sizeBytes}", gif.sizeBytes > 1_000)
        assertEquals(2, vault.database.files().all().size)
        assertTrue("progress was reported", seen.isNotEmpty() && seen.last() == 1f)

        // And it really is an animation Android can read, at the asked-for width.
        val bytes = vault.files.open(vault.database.files().get(gif.id)!!).use { it.readBytes() }
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertTrue("an animation, not a still", drawable is AnimatedImageDrawable)
        assertEquals(160, drawable.intrinsicWidth)
        assertEquals(120, drawable.intrinsicHeight)
        assertEquals(160, gif.width)
        assertEquals(120, gif.height)
    }

    @Test
    fun theGifKeepsTheVideosShapeRatherThanBeingStretched() = runBlocking {
        val video = sealVideo(encodeMp4(2, width = 640, height = 360))
        val vault = entry.session().openVault!!
        val gif = VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 1_000, fps = 6, maxWidth = 320))
        assertEquals(320, gif.width)
        assertEquals(180, gif.height)
    }

    /**
     * Counts the frames in a GIF by walking its blocks, the way a decoder does, rather than
     * searching for bytes that could also appear inside compressed data.
     */
    private fun frameCount(bytes: ByteArray): Int {
        var at = 13 // header and screen descriptor
        val flags = bytes[10].toInt() and 0xFF
        if (flags and 0x80 != 0) at += 3 * (1 shl ((flags and 0x07) + 1)) // global colour table
        fun skipSubBlocks() {
            while (true) {
                val length = bytes[at++].toInt() and 0xFF
                if (length == 0) return
                at += length
            }
        }
        var frames = 0
        loop@ while (at < bytes.size) {
            when (bytes[at++].toInt() and 0xFF) {
                0x21 -> { at++; skipSubBlocks() } // extension: label, then its data
                0x2C -> {
                    val local = bytes[at + 8].toInt() and 0xFF
                    at += 9
                    if (local and 0x80 != 0) at += 3 * (1 shl ((local and 0x07) + 1))
                    at++ // LZW minimum code size
                    skipSubBlocks()
                    frames++
                }
                0x3B -> break@loop
                else -> break@loop
            }
        }
        return frames
    }

    /**
     * Near the end of a video there is less left than was asked for. The GIF must be that much
     * shorter, not the full count of frames all showing the same frozen instant.
     */
    @Test
    fun askingForMoreThanIsLeftGivesAShorterGifNotAFrozenOne() = runBlocking {
        val video = sealVideo(encodeMp4(3))
        val vault = entry.session().openVault!!
        val bytesOf: suspend (VaultFile) -> ByteArray =
            { f -> vault.files.open(vault.database.files().get(f.id)!!).use { it.readBytes() } }

        val full = VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 10, maxWidth = 120))
        val fullFrames = frameCount(bytesOf(full))
        assertTrue("got $fullFrames frames for two seconds at ten a second", fullFrames in 15..20)

        // Only a fraction of a second is left here, however much was asked for.
        val tail = VideoToGif.create(vault, video, GifRequest(startMs = 2_900, lengthMs = 5_000, fps = 10, maxWidth = 120))
        val tailFrames = frameCount(bytesOf(tail))
        assertTrue("the tail GIF has $tailFrames frames, the whole clip has $fullFrames", tailFrames < 8)
        assertTrue("and it is much smaller: ${tail.sizeBytes} vs ${full.sizeBytes}", tail.sizeBytes < full.sizeBytes)
    }

    /**
     * A file that is not a video at all must come back as a plain message, not a crash: it is in
     * the vault and the user is entitled to tap GIF on it whatever it turns out to be.
     */
    @Test
    fun somethingThatIsNotAVideoFailsWithAMessage() = runBlocking {
        val video = sealVideo(ByteArray(64 * 1024) { (it * 31).toByte() })
        val vault = entry.session().openVault!!

        val thrown = runCatching {
            VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 6, maxWidth = 120))
        }.exceptionOrNull()

        assertNotNull("rubbish should not quietly succeed", thrown)
        // A plain, named failure with something to show the user, not a crash from deep inside.
        assertTrue(
            "got ${thrown!!::class.java.simpleName}: ${thrown.message}",
            thrown is IllegalStateException || thrown is java.io.IOException,
        )
        assertTrue("the message is empty", !thrown.message.isNullOrBlank())
        // And it left nothing half-sealed behind.
        assertEquals(1, vault.database.files().all().size)
    }

    /**
     * A video cut short part way through still has frames at the front. Reading it straight
     * through recovers those, which is better than refusing the whole clip; either way it must
     * not crash or leave half a file behind.
     */
    @Test
    fun aDamagedVideoEitherGivesWhatSurvivedOrSaysSo() = runBlocking {
        val whole = encodeMp4(2)
        val damaged = whole.copyOf(whole.size / 3)
        val video = sealVideo(damaged)
        val vault = entry.session().openVault!!

        val result = runCatching {
            VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 2_000, fps = 6, maxWidth = 120))
        }
        result.fold(
            onSuccess = { gif ->
                assertEquals(2, vault.database.files().all().size)
                val bytes = vault.files.open(vault.database.files().get(gif.id)!!).use { it.readBytes() }
                assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
                assertTrue("a GIF of ${bytes.size} bytes", bytes.size > 100)
            },
            onFailure = { thrown ->
                assertTrue(
                    "got ${thrown::class.java.simpleName}: ${thrown.message}",
                    thrown is IllegalStateException || thrown is java.io.IOException,
                )
                assertEquals(1, vault.database.files().all().size)
            },
        )
    }

    /**
     * Locking the vault must stop the decoder there and then. The check used to happen only when
     * a frame was handed over, so a long stretch between keyframes could keep reading a locked
     * vault's video — and the hold it takes would keep the vault from locking at all.
     */
    @Test
    fun lockingTheVaultStopsTheDecoderAtOnce() = runBlocking {
        val video = sealVideo(encodeMp4(3))
        val vault = entry.session().openVault!!

        var open = true
        var framesSeen = 0
        val began = System.currentTimeMillis()
        val thrown = runCatching {
            VideoToGif.create(
                vault = vault,
                video = video,
                request = GifRequest(startMs = 0, lengthMs = 3_000, fps = 10, maxWidth = 240),
                stillOpen = {
                    // Open just long enough to get going, then shut.
                    if (framesSeen++ > 2) open = false
                    open
                },
            )
        }.exceptionOrNull()
        val took = System.currentTimeMillis() - began

        assertTrue("expected it to stop, got $thrown", thrown is kotlinx.coroutines.CancellationException)
        assertTrue("took ${took}ms, which is too long to notice a lock", took < 15_000)
        // Nothing half-sealed: the video is still the only thing in the vault.
        assertEquals(1, vault.database.files().all().size)
    }

    /** A length past the end of the video is trimmed rather than failing. */
    @Test
    fun askingForMoreThanTheVideoHasStillWorks() = runBlocking {
        val video = sealVideo(encodeMp4(2))
        val vault = entry.session().openVault!!
        val gif = VideoToGif.create(vault, video, GifRequest(startMs = 1_500, lengthMs = 60_000, fps = 6, maxWidth = 120))
        assertNotNull(vault.database.files().get(gif.id))
        assertTrue("size ${gif.sizeBytes}", gif.sizeBytes > 200)
    }
}
