package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId

/** Gallery grouped by month, sort sheet, viewer, favourite and shred, on the real app. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GalleryFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun millis(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

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
        wipe()
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            files.put(jpeg(Color.BLUE).inputStream(), IncomingFile("harbour.jpg", "image/jpeg", millis(2026, 10, 12)))
            files.put(jpeg(Color.RED).inputStream(), IncomingFile("sunset.jpg", "image/jpeg", millis(2026, 10, 3)))
            files.put(jpeg(Color.GREEN).inputStream(), IncomingFile("meadow.jpg", "image/jpeg", millis(2026, 8, 21)))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    /** Regression: reel mode used to stay on its first item forever. */
    @Test
    fun reelAdvancesOnItsOwnAndWraps() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 10_000)

        // A playing reel animates forever, so from here on the clock is driven by hand.
        compose.mainClock.autoAdvance = false
        fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)
        fun showing(n: Int) {
            val seen = compose.onAllNodesWithText("OF 3", substring = true).fetchSemanticsNodes()
                .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }
                .map { it.text }
            if (seen.none { it.startsWith("$n OF 3") }) throw AssertionError("expected item $n, screen shows $seen")
        }

        compose.onNodeWithText("Reel mode").performClick()
        // Loading the list happens on a real background thread: step the clock until it shows.
        val deadline = System.currentTimeMillis() + 15_000
        while (compose.onAllNodesWithText("3s").fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "Reel did not open" }
            advance(16)
            Thread.sleep(20)
        }
        compose.onNodeWithText("3s").performClick()
        advance(200); showing(1)
        advance(3_000); showing(2)
        advance(3_500); showing(3)
        advance(3_500); showing(1) // wraps around

        // Paused reels stay put well past the interval.
        compose.onNodeWithContentDescription("Pause reel").performClick()
        advance(15_000); showing(1)
        compose.onNodeWithContentDescription("Resume reel").performClick()
        advance(4_000); showing(2)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun browseSortViewFavouriteAndShred() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()

        // Grouped by month by default, newest month first.
        compose.waitUntilAtLeastOneExists(hasText("October"), 10_000)
        compose.onNodeWithText("August").assertExists()
        compose.onNodeWithText("3 ITEMS · SEALED").assertExists()

        // Sort by size: tiles get size badges.
        compose.onNodeWithText("Date ↓").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sort & group"), 5_000)
        compose.onNodeWithText("Size").performClick()
        compose.onNodeWithText("Show 3 items").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Size ↓"), 5_000)

        // Open a photo, favourite it, then shred it.
        compose.onNodeWithContentDescription("JPG, harbour.jpg").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Add to favourites"), 10_000)
        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Remove from favourites"), 5_000)
        val favourite = runBlocking { entry.session().openVault!!.database.files().observeAll().first().single { it.name == "harbour.jpg" } }
        assertTrue(favourite.favorite)

        compose.onNodeWithText("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred this file?"), 5_000)
        compose.onAllNodesWithText("Shred")[1].performClick()
        compose.waitUntil(10_000) {
            runBlocking { entry.session().openVault!!.database.files().observeAll().first().none { it.name == "harbour.jpg" } }
        }
    }
}
