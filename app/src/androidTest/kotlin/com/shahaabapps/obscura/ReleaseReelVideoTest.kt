package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reel mode with real video, pushed further than ReelVideoTest: a video LONGER than the
 * slideshow interval (so "waits for the end" and "cut off by the timer" tell apart), and a video
 * the player cannot open at all.
 *
 * Fixtures are read from the app's own files dir, pushed there before the run:
 *   long.mp4   12s H.264
 *   broken.mp4 a truncated MP4
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReleaseReelVideoTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val dir = File(context.filesDir, "fixtures")
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .compress(Bitmap.CompressFormat.JPEG, 80, it)
    }.toByteArray()

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before
    fun resetPanic() {
        entry.panicLock().acknowledge()
    }

    @After
    fun tearDown() {
        scenario?.close()
        entry.gallerySettings().update { GallerySettings() }
        wipe()
    }

    /** Seals [video] as the newest item so the reel opens on it, with two photos behind it. */
    private fun seedAndOpenReel(video: String) {
        wipe()
        entry.gallerySettings().update { GallerySettings() }
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            val f = File(dir, video)
            org.junit.Assume.assumeTrue("fixture $video at $f is not on this device; push it to run this", f.exists())
            files.put(f.inputStream(), IncomingFile(video, "video/mp4", 1_700_000_000_000L))
            files.put(jpeg(Color.RED).inputStream(), IncomingFile("after1.jpg", "image/jpeg", 1_699_999_000_000L))
            files.put(jpeg(Color.GREEN).inputStream(), IncomingFile("after2.jpg", "image/jpeg", 1_699_998_000_000L))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("3 ITEMS", substring = true), 20_000)
        // From here on the reel never idles, so Compose's clock is driven by hand while real
        // time passes alongside it: ExoPlayer runs on the real clock, Compose on the test one.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Reel mode").performClick()
        waitUntil("the reel never opened", 20_000) { shows("1 OF 3") }
    }

    private fun tick(ms: Long = 200) {
        compose.mainClock.advanceTimeBy(ms)
        compose.mainClock.advanceTimeByFrame()
        Thread.sleep(ms)
    }

    private fun exists(label: String) =
        compose.onAllNodes(hasContentDescription(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntil(what: String, timeoutMs: Long, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { what }
            tick()
        }
    }

    /**
     * 12s of video against a 5s slideshow interval: the reel has to stay on the video past 5s and
     * leave only when the video is over.
     */
    @Test
    fun aVideoLongerThanTheIntervalIsNotCutOffByTheTimer() {
        seedAndOpenReel("long.mp4")
        try {
            waitUntil("the video never started playing", 30_000) { exists("Playing long.mp4") }
            val startedAt = System.currentTimeMillis()

            while (System.currentTimeMillis() - startedAt < 8_000) tick()
            assertTrue(
                "the reel left a 12s video after ${System.currentTimeMillis() - startedAt}ms: " +
                    "that is the 5s timer, not the end of the video",
                shows("1 OF 3"),
            )

            waitUntil("the reel never advanced when the video ended", 30_000) { shows("2 OF 3") }
            println("REEL advanced ${System.currentTimeMillis() - startedAt}ms after playback started")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Only one page ever holds the player, so only one file is decrypted for playback. */
    @Test
    fun onlyOnePageHoldsThePlayer() {
        seedAndOpenReel("long.mp4")
        try {
            waitUntil("the video never started playing", 30_000) { exists("Playing long.mp4") }
            assertEquals(
                "more than one video surface is live",
                1,
                compose.onAllNodes(hasContentDescription("Playing ", substring = true)).fetchSemanticsNodes().size,
            )
            assertTrue("a playing video should not also show the waiting badge", !exists("Video, waiting to play"))
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** A single tap over a playing video clears the controls, and brings them back. */
    @Test
    fun tappingOverAPlayingVideoHidesTheControls() {
        seedAndOpenReel("long.mp4")
        try {
            waitUntil("the video never started playing", 30_000) { exists("Playing long.mp4") }
            compose.onNodeWithContentDescription("Playing long.mp4", substring = true).performClick()
            waitUntil("the controls never went away", 10_000) { !exists("Close reel") }
            compose.onNodeWithContentDescription("Playing long.mp4", substring = true).performClick()
            waitUntil("the controls never came back", 10_000) { exists("Close reel") }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /**
     * A truncated MP4: the player reports an error and never reaches STATE_ENDED, so there is
     * nothing to move the reel on. It must not park there for ever.
     */
    @Test
    fun aVideoThatCannotBePlayedDoesNotStallTheReel() {
        seedAndOpenReel("broken.mp4")
        try {
            val startedAt = System.currentTimeMillis()
            var left = false
            while (System.currentTimeMillis() - startedAt < 25_000) {
                if (shows("2 OF 3")) { left = true; break }
                tick()
            }
            println("REEL on broken.mp4 for ${System.currentTimeMillis() - startedAt}ms, advanced=$left")
            assertTrue(
                "the reel never left a video it could not play: the advance effect in " +
                    "ReelScreen.kt returns early while playingVideo && !playback.ended, and a " +
                    "failed player never reaches STATE_ENDED, so the reel is stuck for good",
                left,
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
