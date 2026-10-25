package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.GallerySettings
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reel mode plays videos rather than showing a frozen frame. Three things have to hold: the
 * slideshow timer must not cut a video off, the reel must move on once it ends, and a file that
 * cannot be played must not leave the reel parked on it for ever.
 *
 * The reel animates without pause, so these drive Compose's clock by hand. Note that `delay`
 * inside the screen runs on that clock too, which is why the virtual time each test spends is
 * kept deliberately small.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReelVideoTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }.compress(Bitmap.CompressFormat.JPEG, 80, it)
    }.toByteArray()

    /**
     * A real H.264 MP4 of about [seconds] seconds, encoded here rather than screen-recorded, so
     * the clip is the same whatever else is happening on the device.
     */
    private fun encodeMp4(seconds: Int): ByteArray {
        val file = File(context.cacheDir, "reel-clip.mp4")
        val width = 320
        val height = 240
        val fps = 6
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
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
            canvas.drawCircle(20f + (frame % 30) * 9f, height / 2f, 30f, paint)
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

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    /** Seals a photo and, newest so the reel opens on it, the given video. */
    private fun seed(video: ByteArray, name: String = "clip.mp4") = runBlocking {
        entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
        val files = entry.session().openVault!!.files
        files.put(jpeg().inputStream(), IncomingFile("after.jpg", "image/jpeg", 1_700_000_000_000 - 60_000))
        files.put(video.inputStream(), IncomingFile(name, "video/mp4", 1_700_000_000_000))
    }

    private fun openGallery() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("2 ITEMS", substring = true), 20_000)
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun exists(label: String) = compose.onAllNodes(hasContentDescription(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        wipe()
        entry.gallerySettings().update { GallerySettings() }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        scenario?.close()
        wipe()
    }

    /** The 5-second slideshow interval must not cut a 9-second video short. */
    @Test
    fun theSlideshowTimerDoesNotCutOffAVideo() {
        seed(encodeMp4(7))
        openGallery()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Reel mode").performClick()

        // One frame at a time, paced to the wall clock: the screen's own give-up timer runs on
        // this clock too, so racing it ahead would trip that instead of the slideshow interval.
        fun step() {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (!exists("Playing clip.mp4")) {
            check(System.currentTimeMillis() < deadline) { "the video never started playing" }
            step()
        }
        // Past the 5s slideshow interval, but short of the 7s video's own length.
        val until = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < until) step()
        assertTrue("the timer cut the video off at the slideshow interval", shows("1 OF 2"))
    }

    /** Once the video really ends, the reel moves on by itself. */
    @Test
    fun theReelMovesOnWhenAVideoEnds() {
        seed(encodeMp4(2))
        openGallery()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Reel mode").performClick()

        // Real time passes for the player while the composition clock creeps, so the reel
        // advances because the video ended, not because a timer ran out.
        val deadline = System.currentTimeMillis() + 45_000
        while (!shows("2 OF 2")) {
            check(System.currentTimeMillis() < deadline) { "the reel never moved on after the video ended" }
            compose.mainClock.advanceTimeBy(100)
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(60)
        }
    }

    /** A file the player cannot read must not leave the reel stuck on it. */
    @Test
    fun aVideoThatCannotBePlayedDoesNotStallTheReel() {
        seed(encodeMp4(2).copyOf(900), name = "broken.mp4")
        openGallery()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Reel mode").performClick()

        val deadline = System.currentTimeMillis() + 60_000
        while (!shows("2 OF 2")) {
            check(System.currentTimeMillis() < deadline) { "the reel parked on a video it could not play" }
            compose.mainClock.advanceTimeBy(250)
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }
}
