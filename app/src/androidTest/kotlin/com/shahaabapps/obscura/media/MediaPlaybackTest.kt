package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import com.shahaabapps.obscura.vault.DeviceClock
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.LockoutPolicy
import com.shahaabapps.obscura.vault.LockoutStorage
import com.shahaabapps.obscura.vault.OpenVault
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real decoding through the decrypting path: MP4 video, WAV audio and a PDF. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var session: VaultSession
    private val vault: OpenVault get() = session.openVault!!

    private class MemoryStorage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private object Clock : DeviceClock {
        override fun elapsedRealtime() = 1L
        override fun bootCount() = 1
    }

    @Before
    fun setUp() {
        root = File(context.cacheDir, "media-test-${System.nanoTime()}")
        session = VaultSession(
            context, VaultStore(root), LockoutPolicy(MemoryStorage(), Clock),
            KeystoreBinding("obscura.test.media.pin"), KeystoreGate("obscura.test.media.bio"), KdfParams(1024, 1, 1),
        )
        runBlocking { session.create("passphrase".toCharArray()) }
    }

    @After
    fun tearDown() {
        session.lock()
        root.deleteRecursively()
        KeystoreBinding("obscura.test.media.pin").delete()
    }

    private fun seal(bytes: ByteArray, name: String, mime: String): VaultFile =
        runBlocking { vault.files.put(bytes.inputStream(), IncomingFile(name, mime, 0)) }

    private fun factory(vararg files: VaultFile) =
        SealedDataSource.Factory(vault.keys.fileCipher, vault.files) { id -> files.firstOrNull { it.id == id } }

    @Test
    fun dataSourceReadsRequestedRanges() {
        val data = Random(3).nextBytes(2_600_000)
        val file = seal(data, "blob.bin", "application/octet-stream")
        val source = factory(file).createDataSource()
        for ((position, length) in listOf(0L to 1000L, 1_048_000L to 5000L, 2_599_000L to C.LENGTH_UNSET.toLong())) {
            val opened = source.open(DataSpec.Builder().setUri(SealedDataSource.uriFor(file)).setPosition(position).setLength(length).build())
            val expectedLength = if (length == C.LENGTH_UNSET.toLong()) data.size - position else length
            assertEquals(expectedLength, opened)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            source.close()
            assertArrayEquals(data.copyOfRange(position.toInt(), (position + expectedLength).toInt()), out.toByteArray())
        }
    }

    /** Prepares a player on the main thread and waits for READY (or fails on error). */
    private fun prepare(file: VaultFile, seekToMs: Long = 0): Triple<Long, Int, Int> {
        val ready = CountDownLatch(1)
        var error: PlaybackException? = null
        var result = Triple(0L, 0, 0)
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper())
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory(file))).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        // No surface in a test, so read the size from the parsed video track.
                        val video = player.currentTracks.groups
                            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
                            .firstOrNull { it.sampleMimeType?.startsWith("video/") == true }
                        result = Triple(player.duration, video?.width ?: 0, video?.height ?: 0)
                        ready.countDown()
                    }
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    ready.countDown()
                }
            })
            player.setMediaItem(mediaItem(file))
            if (seekToMs > 0) player.seekTo(seekToMs)
            player.prepare()
        }
        val ok = ready.await(20, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player.release() }
        error?.let { throw AssertionError("Playback failed: ${it.errorCodeName}", it) }
        assertTrue("player never became ready", ok)
        return result
    }

    /** A 2-second, 15 fps, 320×240 H.264 MP4 drawn frame by frame with Android's own encoder. */
    private fun encodeMp4(): ByteArray {
        val file = File(context.cacheDir, "encoded.mp4")
        val width = 320
        val height = 240
        val fps = 15
        val format = android.media.MediaFormat.createVideoFormat(android.media.MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = android.media.MediaMuxer(file.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = android.media.MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    index == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index)!!
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, data, info)
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val paint = Paint().apply { color = Color.rgb(255, 225, 77) }
        repeat(fps * 2) { frame ->
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(59, 91, 255))
            canvas.drawCircle(20f + frame * 9f, height / 2f, 30f, paint)
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

    @Test
    fun sealedVideoPlaysAndSeeks() {
        val video = seal(encodeMp4(), "clip.mp4", "video/mp4")
        val (duration, width, height) = prepare(video)
        assertTrue("duration $duration", duration > 1_000)
        assertEquals(320, width)
        assertEquals(240, height)
        prepare(video, seekToMs = duration / 2)
    }

    /** 2 seconds of a 440 Hz tone as 16-bit mono WAV. */
    private fun wav(): ByteArray {
        val rate = 22_050
        val samples = rate * 2
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { i -> buffer.putShort((sin(2 * PI * 440 * i / rate) * 12_000).toInt().toShort()) }
        return buffer.array()
    }

    @Test
    fun sealedAudioPlays() {
        val track = seal(wav(), "tone.wav", "audio/wav")
        val (duration, _, _) = prepare(track)
        assertTrue("duration $duration", duration in 1_900..2_100)
    }

    /** Regression: pressing Play after a track had finished did nothing. */
    @Test
    fun playAfterTheEndRestarts() {
        val track = seal(wav(), "tone.wav", "audio/wav")
        val ended = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper())
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory(track))).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended.countDown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && ended.count == 0L) restarted.countDown()
                }
            })
            player.setMediaItem(mediaItem(track))
            player.prepare()
            player.play()
        }
        assertTrue("track played to the end", ended.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { player.togglePlayback() }
        val ok = restarted.await(5, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player.release() }
        assertTrue("play after the end starts again", ok)
    }

    @Test
    fun sealedPdfRendersPages() = runBlocking {
        val pdfBytes = ByteArrayOutputStream().also { out ->
            PdfDocument().apply {
                repeat(3) { n ->
                    val page = startPage(PdfDocument.PageInfo.Builder(300, 400, n + 1).create())
                    page.canvas.drawRect(20f, 20f, 280f, 120f, Paint().apply { color = Color.rgb(59, 91, 255) })
                    finishPage(page)
                }
                writeTo(out)
                close()
            }
        }.toByteArray()
        val doc = seal(pdfBytes, "statement.pdf", "application/pdf")
        // Through the real sealed file, decrypted as the renderer reads it.
        SealedPdf.open(context, vault.pdfSource(doc)).use { pdf ->
            assertEquals(3, pdf.pageCount)
            val page: Bitmap = requireNotNull(pdf.render(1, 600)) { "page rendered" }
            assertEquals(600, page.width)
            assertEquals(800, page.height)
            assertEquals("the blue box was drawn", Color.rgb(59, 91, 255), page.getPixel(300, 120))
            assertEquals(Color.WHITE, page.getPixel(300, 700))
        }
    }

    @Test
    fun textPreviewReadsUtf8() = runBlocking {
        val doc = seal("Ünïcödé notes ✓".toByteArray(), "notes.txt", "text/plain")
        val (text, truncated) = TextPreview.read { vault.files.open(doc) }
        assertEquals("Ünïcödé notes ✓", text)
        assertEquals(false, truncated)
    }
}
