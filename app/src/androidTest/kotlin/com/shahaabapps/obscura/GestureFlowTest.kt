package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.ColumnRange
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

/** Pinching the grid, and tapping a picture to get everything else out of the way. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GestureFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 80, it)
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

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

    private val columns get() = entry.gallerySettings().settings.value.columns

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        wipe()
        entry.gallerySettings().update { GallerySettings() }
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
            colors.forEachIndexed { i, c ->
                files.put(jpeg(c).inputStream(), IncomingFile("photo${i + 1}.jpg", "image/jpeg", 1_700_000_000_000 - i * 60_000L))
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("SEALED ON THIS PHONE")
        compose.onNodeWithText("Photos").performClick()
        waitForText("6 ITEMS")
    }

    @After
    fun tearDown() {
        scenario?.close()
        entry.gallerySettings().update { GallerySettings() }
        wipe()
    }

    @Test
    fun pinchingTheGridChangesHowManyFitAcross() {
        val grid = compose.onNode(hasScrollToIndexAction())
        val before = columns
        assertEquals(3, before)

        // Fingers together: more, smaller items.
        grid.performTouchInput {
            pinch(
                start0 = center + androidx.compose.ui.geometry.Offset(-260f, 0f),
                end0 = center + androidx.compose.ui.geometry.Offset(-40f, 0f),
                start1 = center + androidx.compose.ui.geometry.Offset(260f, 0f),
                end1 = center + androidx.compose.ui.geometry.Offset(40f, 0f),
            )
        }
        compose.waitUntil(5_000) { columns > before }
        val squeezed = columns

        // Fingers apart: back to fewer, bigger ones.
        grid.performTouchInput {
            pinch(
                start0 = center + androidx.compose.ui.geometry.Offset(-40f, 0f),
                end0 = center + androidx.compose.ui.geometry.Offset(-300f, 0f),
                start1 = center + androidx.compose.ui.geometry.Offset(40f, 0f),
                end1 = center + androidx.compose.ui.geometry.Offset(300f, 0f),
            )
        }
        compose.waitUntil(5_000) { columns < squeezed }
        assertTrue("$columns within ${ColumnRange.MIN}..${ColumnRange.MAX}", columns in ColumnRange.MIN..ColumnRange.MAX)
    }

    @Test
    fun tappingAPictureHidesEverythingElse() {
        compose.onNode(hasContentDescription("photo1.jpg", substring = true)).performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Back"), 20_000)
        waitForText("1 OF 6")

        // Tap the picture: the chrome goes.
        compose.onNodeWithContentDescription("photo1.jpg", substring = true).performClick()
        compose.waitUntilDoesNotExist(hasContentDescription("Back"), 10_000)

        // Tap again: it comes back.
        compose.onNodeWithContentDescription("photo1.jpg", substring = true).performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Back"), 10_000)
    }

    @Test
    fun tappingInTheReelHidesTheControls() {
        // The reel animates without pause, so the clock is driven by hand: left to itself,
        // Compose never reports the screen idle.
        compose.mainClock.autoAdvance = false
        fun settle(ms: Long = 400) {
            compose.mainClock.advanceTimeBy(ms)
            compose.mainClock.advanceTimeByFrame()
        }
        fun waitFor(label: String, present: Boolean) {
            val deadline = System.currentTimeMillis() + 20_000
            while (compose.onAllNodes(hasContentDescription(label, substring = true)).fetchSemanticsNodes().isNotEmpty() != present) {
                check(System.currentTimeMillis() < deadline) { "\"$label\" ${if (present) "never appeared" else "never went away"}" }
                settle()
            }
        }
        try {
            compose.onNodeWithText("Reel mode").performClick()
            waitFor("Close reel", present = true)

            compose.onAllNodes(hasContentDescription("photo", substring = true))[0].performClick()
            waitFor("Close reel", present = false)

            compose.onAllNodes(hasContentDescription("photo", substring = true))[0].performClick()
            waitFor("Close reel", present = true)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
