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
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.GallerySettings
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * What interrupting a playing reel video does: the vault locking, the screen going away, and
 * leaving the reel by hand. None of them may leave the player running or the screen stuck.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReelVideoInterruptionTest {

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
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        wipe()
        entry.gallerySettings().update { GallerySettings() }
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            val f = File(dir, "long.mp4")
            org.junit.Assume.assumeTrue("fixture long.mp4 at $f is not on this device; push it to run this", f.exists())
            files.put(f.inputStream(), IncomingFile("long.mp4", "video/mp4", 1_700_000_000_000L))
            files.put(jpeg(Color.RED).inputStream(), IncomingFile("after.jpg", "image/jpeg", 1_699_999_000_000L))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("2 ITEMS", substring = true), 20_000)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Reel mode").performClick()
        waitUntil("the reel never opened", 20_000) { shows("1 OF 2") }
        waitUntil("the video never started", 30_000) { exists("Playing long.mp4") }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        scenario?.close()
        entry.gallerySettings().update { GallerySettings() }
        wipe()
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

    /** Locking mid-playback takes the reel away and asks for the secret again. */
    @Test
    fun lockingWhileAVideoPlaysLeavesTheReelAndAsksToUnlock() {
        instrumentation.runOnMainSync { entry.session().lockNowFromTest() }
        waitUntil("the reel stayed up after the vault locked", 20_000) { !exists("Playing long.mp4") }
        assertTrue("the vault should be locked", entry.session().state.value is VaultState.Locked)
        waitUntil("the unlock screen never appeared", 20_000) { shows("Locked") || shows("PIN") || shows("passphrase") }
    }

    /** The screen going away stops playback instead of letting it run on unseen. */
    @Test
    fun theVideoDoesNotKeepPlayingWhenTheScreenGoesAway() {
        compose.mainClock.autoAdvance = true
        scenario!!.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1_500)
        // Coming back must not crash and must still be the reel on the same item.
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.autoAdvance = false
        waitUntil("the reel did not come back", 25_000) { shows("OF 2") }
    }

    /** Closing the reel by hand releases the player and goes back to the gallery. */
    @Test
    fun closingTheReelStopsTheVideo() {
        compose.onNodeWithContentDescription("Close reel").performClick()
        waitUntil("the reel never closed", 20_000) { !exists("Playing long.mp4") }
        compose.mainClock.autoAdvance = true
        compose.waitUntilAtLeastOneExists(hasText("Reel mode", substring = true), 20_000)
    }
}

/** Locking from a test without reaching for the UI. */
private fun com.shahaabapps.obscura.vault.VaultSession.lockNowFromTest() = lock()
