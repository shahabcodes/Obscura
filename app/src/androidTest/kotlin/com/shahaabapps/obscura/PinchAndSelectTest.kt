package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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

/**
 * Pinching the grid is a new gesture on top of two that were already there. These check it did
 * not take them over: a one-finger flick still scrolls, and a long press still starts a
 * drag-select, with the column count left alone in both cases.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PinchAndSelectTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    // Enough that the grid still overflows the screen at the smallest tile size, so "did it
    // scroll?" stays a fair question after a pinch.
    private val count = 120

    private fun jpeg(i: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF000000.toInt() or (i * 5_000_017)) }
            .compress(Bitmap.CompressFormat.JPEG, 70, it)
    }.toByteArray()

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    private val columns get() = entry.gallerySettings().settings.value.columns

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        wipe()
        entry.gallerySettings().update { GallerySettings() }
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            repeat(count) { i ->
                files.put(
                    jpeg(i).inputStream(),
                    IncomingFile("photo%02d.jpg".format(i), "image/jpeg", 1_700_000_000_000L - i * 60_000L),
                )
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("$count ITEMS", substring = true), 30_000)
    }

    @After
    fun tearDown() {
        scenario?.close()
        entry.gallerySettings().update { GallerySettings() }
        wipe()
    }

    private fun shown(name: String) =
        compose.onAllNodes(hasContentDescription(name, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** A plain one-finger flick still scrolls the grid, and leaves the density alone. */
    @Test
    fun oneFingerScrollingStillWorksAndDoesNotChangeTheColumns() {
        val before = columns
        assertTrue("the first item should be on screen to start with", shown("photo00.jpg"))

        repeat(3) {
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertTrue("the grid never scrolled: photo00 is still on screen", !shown("photo00.jpg"))
        assertEquals("a scroll changed the column count", before, columns)

        repeat(4) {
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        assertTrue("scrolling back up did not work", shown("photo00.jpg"))
        assertEquals("scrolling back changed the column count", before, columns)
    }

    /** A long press still starts a selection, and does not step the density. */
    @Test
    fun aLongPressStillStartsADragSelectAndDoesNotChangeTheColumns() {
        val before = columns
        val grid = compose.onNode(hasScrollToIndexAction())
        val tile = compose.onNode(hasContentDescription("photo00.jpg", substring = true))
        val gridTopLeft = grid.fetchSemanticsNode().positionInRoot
        val tileNode = tile.fetchSemanticsNode()
        val from = androidx.compose.ui.geometry.Offset(
            tileNode.positionInRoot.x - gridTopLeft.x + tileNode.size.width / 2f,
            tileNode.positionInRoot.y - gridTopLeft.y + tileNode.size.height / 2f,
        )
        // Hold still past the long-press timeout the way a finger does, then drag one tile over.
        grid.performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(1_000)
        grid.performTouchInput {
            moveTo(androidx.compose.ui.geometry.Offset(from.x + tileNode.size.width, from.y))
            up()
        }
        compose.waitUntilAtLeastOneExists(hasText("selected", substring = true), 10_000)
        assertEquals("a long press changed the column count", before, columns)
    }

    /** And the pinch itself still works, in both directions, within its limits. */
    @Test
    fun pinchingStepsTheColumnsAndStopsAtTheLimits() {
        val grid = compose.onNode(hasScrollToIndexAction())
        fun squeeze() = grid.performTouchInput {
            pinch(
                start0 = center + Offset(-300f, 0f), end0 = center + Offset(-40f, 0f),
                start1 = center + Offset(300f, 0f), end1 = center + Offset(40f, 0f),
            )
        }
        fun spread() = grid.performTouchInput {
            pinch(
                start0 = center + Offset(-40f, 0f), end0 = center + Offset(-320f, 0f),
                start1 = center + Offset(40f, 0f), end1 = center + Offset(320f, 0f),
            )
        }

        repeat(8) { squeeze(); compose.waitForIdle() }
        assertEquals("squeezing should reach the most columns", ColumnRange.MAX, columns)
        repeat(10) { spread(); compose.waitForIdle() }
        assertEquals("spreading should reach the fewest columns", ColumnRange.MIN, columns)
    }

    /** After a pinch the grid still scrolls: the gesture must not leave the pointer stream stuck. */
    @Test
    fun scrollingStillWorksAfterAPinch()  {
        val grid = compose.onNode(hasScrollToIndexAction())
        grid.performTouchInput {
            pinch(
                start0 = center + Offset(-300f, 0f), end0 = center + Offset(-40f, 0f),
                start1 = center + Offset(300f, 0f), end1 = center + Offset(40f, 0f),
            )
        }
        compose.waitForIdle()
        println("PINCH columns after squeeze = $columns")
        repeat(6) { grid.performTouchInput { swipeUp() }; compose.waitForIdle() }
        println("PINCH photo00 still shown after 6 swipes = ${shown("photo00.jpg")}, columns = $columns")
        assertTrue("the grid would not scroll after a pinch", !shown("photo00.jpg"))
    }
}
