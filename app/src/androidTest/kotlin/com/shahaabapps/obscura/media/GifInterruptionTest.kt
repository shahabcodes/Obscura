package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.KeepVaultOpen
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A GIF interrupted part way through. Someone presses home, the screen goes off, the vault
 * locks: whatever happens, there must be no half-sealed GIF in the vault and no frame of the
 * video left in the clear on disk.
 */
@RunWith(AndroidJUnit4::class)
class GifInterruptionTest {

    /** The vault stays open while this runs, as it does in the app during long work. */
    @get:Rule
    val keepOpen = KeepVaultOpen()


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

    /** A long enough clip that there is a middle to interrupt. */
    private fun encodeMp4(seconds: Int, width: Int = 640, height: Int = 480, keyframeSeconds: Int = 1): ByteArray {
        val file = File(context.cacheDir, "interrupt.mp4")
        val fps = 15
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSeconds)
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
            canvas.drawCircle(30f + (frame % 20) * 28f, height / 2f, 70f, paint)
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
            IncomingFile("evening.mp4", "video/mp4", 1_700_000_000_000, 640, 480, 4_000),
        )
    }

    /**
     * Anything readable the app wrote outside the sealed store. A GIF or a JPEG's own first bytes
     * are enough to recognise one, and the video's name must not appear either.
     */
    private fun leftInTheClear(): List<String> {
        val roots = listOfNotNull(context.cacheDir, context.filesDir, context.noBackupFilesDir, context.codeCacheDir)
        val found = mutableListOf<String>()
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.length() > 0 }.forEach { file ->
                if (file.path.contains("blobs")) return@forEach // the sealed store itself
                val head = file.inputStream().use { stream ->
                    ByteArray(16).let { buffer -> buffer.copyOf(maxOf(0, stream.read(buffer))) }
                }
                val ascii = String(head, Charsets.ISO_8859_1)
                if (ascii.startsWith("GIF8") || (head.size > 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte())) {
                    found += "${file.path} (${file.length()} bytes, starts \"${ascii.take(6)}\")"
                }
                if (file.name.contains("evening", ignoreCase = true)) found += "${file.path} (named after the video)"
            }
        }
        return found
    }

    /** Backing out of the sheet part way through: nothing sealed, nothing left behind. */
    @Test
    fun cancellingMidGifLeavesNoHalfSealedGifAndNothingInTheClear() = runBlocking {
        val video = sealVideo(encodeMp4(4))
        val vault = entry.session().openVault!!
        var reached = 0f

        val job = async(Dispatchers.IO) {
            VideoToGif.create(vault, video, GifRequest(startMs = 0, lengthMs = 5_000, fps = 15, maxWidth = 480)) {
                reached = it
            }
        }
        // Let it get properly under way: past the palette survey and into writing frames.
        withTimeout(30_000) { while (reached < 0.3f && job.isActive) delay(20) }
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        println("cancelled at $reached with ${thrown?.let { it::class.java.simpleName }}")

        assertTrue("it was really under way, reached $reached", reached >= 0.3f)
        assertTrue("cancellation, not a failure: $thrown", thrown is CancellationException)
        assertEquals("only the video is in the vault", 1, vault.database.files().all().size)
        assertEquals("nothing readable left behind", emptyList<String>(), leftInTheClear())
    }

    /**
     * The vault locking under a running GIF: the work stops, and it does not go on reading
     * frames out of a vault it no longer has permission to.
     */
    @Test
    fun lockingMidGifStopsItAndSealsNothing() = runBlocking {
        val video = sealVideo(encodeMp4(4))
        val vault = entry.session().openVault!!
        var reached = 0f
        var framesAfterLock = 0
        var locked = false

        val job = async(Dispatchers.IO) {
            VideoToGif.create(
                vault = vault,
                video = video,
                request = GifRequest(startMs = 0, lengthMs = 5_000, fps = 15, maxWidth = 480),
                stillOpen = { entry.session().openVault === vault },
            ) {
                reached = it
                if (locked) framesAfterLock++
            }
        }
        withTimeout(30_000) { while (reached < 0.3f && job.isActive) delay(20) }
        locked = true
        instrumentation.runOnMainSync { entry.session().lock() }
        val thrown = runCatching { job.await() }.exceptionOrNull()
        println("locked at $reached, $framesAfterLock frames reported after the lock, got ${thrown?.let { it::class.java.simpleName }}")

        assertTrue("stopped on the lock: $thrown", thrown is CancellationException)
        assertTrue("it must not keep reading frames: $framesAfterLock", framesAfterLock <= 2)
        assertTrue("the vault is locked", entry.session().openVault == null)
        assertEquals("nothing readable left behind", emptyList<String>(), leftInTheClear())
    }

    /**
     * Two GIFs of the same clip at once, as happens when a tap is registered twice. Both must
     * finish or stop cleanly, and the vault must hold exactly what was sealed.
     */
    @Test
    fun twoGifsAtOnceBothLandOrBothStop() = runBlocking {
        val video = sealVideo(encodeMp4(3))
        val vault = entry.session().openVault!!
        val first = async(Dispatchers.IO) {
            runCatching { VideoToGif.create(vault, video, GifRequest(0, 2_000, fps = 10, maxWidth = 320)) }
        }
        val second = async(Dispatchers.IO) {
            runCatching { VideoToGif.create(vault, video, GifRequest(500, 2_000, fps = 10, maxWidth = 320)) }
        }
        val results = listOf(first.await(), second.await())
        val made = results.count { it.isSuccess }
        results.forEach { r -> r.exceptionOrNull()?.let { println("one of two failed: ${it::class.java.simpleName}: ${it.message}") } }
        println("$made of two GIFs landed")
        assertEquals("the vault holds the video plus whatever landed", 1 + made, vault.database.files().all().size)
        assertEquals("nothing readable left behind", emptyList<String>(), leftInTheClear())
    }

    /**
     * A GIF of the tail of a clip with one keyframe at the front: nothing can be handed over
     * until the decoder has run through the whole clip. Cancellation is only noticed when a
     * frame arrives, so this measures how long a cancel — or a lock — goes unanswered. The
     * vault is held unlocked for exactly that long.
     */
    @Test
    fun cancellingBeforeTheFirstFrameIsAnsweredPromptly() = runBlocking {
        // 20 seconds, one keyframe: a GIF of the last second must decode all 300 frames first.
        val bytes = runCatching { encodeMp4(20, width = 640, height = 480, keyframeSeconds = 100) }.getOrNull()
            ?: return@runBlocking
        val video = sealVideo(bytes)
        val vault = entry.session().openVault!!
        var frames = 0

        val job = async(Dispatchers.IO) {
            VideoToGif.create(vault, video, GifRequest(startMs = 18_500, lengthMs = 2_000, fps = 10, maxWidth = 480)) {
                frames++
            }
        }
        delay(400) // under way, but far from the first wanted frame
        val askedAt = System.currentTimeMillis()
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        val answeredAfter = System.currentTimeMillis() - askedAt
        println("cancel before the first frame was answered after ${answeredAfter}ms (progress callbacks: $frames)")

        assertTrue("cancellation, not a failure: $thrown", thrown is CancellationException)
        assertEquals("nothing sealed", 1, vault.database.files().all().size)
        assertTrue(
            "a cancel must not go unanswered for ${answeredAfter}ms: the vault is held unlocked all that time",
            answeredAfter < 3_000,
        )
    }

    /**
     * The hold that keeps the vault open for a GIF must be given back. If it were not, a lock
     * asked for during the GIF would wait for ever and the vault would sit unlocked.
     */
    @Test
    fun theHoldOnTheVaultIsGivenBackWhenTheGifIsDone() = runBlocking {
        val video = sealVideo(encodeMp4(2))
        val vault = entry.session().openVault!!
        val autoLock = entry.autoLock()
        instrumentation.runOnMainSync { autoLock.hold() }

        val job = launch(Dispatchers.IO) {
            runCatching { VideoToGif.create(vault, video, GifRequest(0, 2_000, fps = 10, maxWidth = 320)) }
        }
        job.join()
        // The test's own hold stands in for the one the screen takes; giving it back must let a
        // lock asked for meanwhile happen at once.
        instrumentation.runOnMainSync { entry.session().lock() }
        instrumentation.runOnMainSync { autoLock.release() }
        instrumentation.runOnMainSync { }
        assertTrue("the vault locked once the hold was given back", entry.session().openVault == null)
        assertTrue("no lock is still waiting", !autoLock.isLockDeferred)
    }
}
