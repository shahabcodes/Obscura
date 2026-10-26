package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** iOS-style multi-select in the gallery, driven with real touch gestures. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MultiSelectFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 80, it)
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

    /** Photo i of 9, sealed so that photo1 is newest and shows first. */
    private fun tile(i: Int): SemanticsNodeInteraction = compose.onNode(hasContentDescription("photo$i.jpg", substring = true))

    private fun centre(node: SemanticsNodeInteraction, grid: SemanticsNodeInteraction): Offset {
        val tile = node.fetchSemanticsNode().boundsInRoot
        val origin = grid.fetchSemanticsNode().boundsInRoot
        return Offset(tile.center.x - origin.left, tile.center.y - origin.top)
    }

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        // Other tests change sort, filter and columns; start from the defaults.
        entry.gallerySettings().update { com.shahaabapps.obscura.ui.gallery.GallerySettings() }
        wipe()
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.GRAY, Color.WHITE, Color.BLACK)
            colors.forEachIndexed { i, c ->
                files.put(jpeg(c).inputStream(), IncomingFile("photo${i + 1}.jpg", "image/jpeg", 1_700_000_000_000 - i * 60_000L))
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("SEALED ON THIS PHONE")
        compose.onNodeWithText("Photos").performClick()
        waitForText("9 ITEMS")
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun longPressAndDragSelectsARange() {
        val grid = compose.onNode(hasScrollToIndexAction())
        val from = centre(tile(1), grid)
        val to = centre(tile(5), grid)
        // Hold still past the long-press timeout (as a finger does), then drag.
        grid.performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(1_000)
        grid.performTouchInput {
            moveTo(Offset((from.x + to.x) / 2, (from.y + to.y) / 2))
            moveTo(to)
            up()
        }
        waitForText("5 selected")

        // Tap toggles single tiles while selecting.
        tile(3).performClick()
        waitForText("4 selected")
        tile(9).performClick()
        waitForText("5 selected")

        // Favourite them all from the action bar.
        compose.onNodeWithText("Favourite").performClick()
        compose.waitUntil(10_000) {
            runBlocking { entry.session().openVault!!.database.files().all().count { it.favorite } } == 5
        }
    }

    @Test
    fun sidewaysSwipeWhileSelectingAddsARowAndShredRemovesThem() {
        compose.onNodeWithContentDescription("Select").performClick()
        waitForText("Select items")
        val grid = compose.onNode(hasScrollToIndexAction())
        val from = centre(tile(1), grid)
        val to = centre(tile(3), grid)
        grid.performTouchInput {
            down(from)
            moveTo(Offset(from.x + viewConfiguration.touchSlop * 2, from.y))
            moveTo(to)
            up()
        }
        waitForText("3 selected")

        // Swiping again over the same tiles, starting on a selected one, deselects them.
        grid.performTouchInput {
            down(from)
            moveTo(Offset(from.x + viewConfiguration.touchSlop * 2, from.y))
            moveTo(centre(tile(2), grid))
            up()
        }
        waitForText("1 selected")

        compose.onNodeWithText("Shred").performClick()
        waitForText("Shred 1 file?")
        compose.onNode(hasText("Shred") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { runBlocking { entry.session().openVault!!.database.files().all().size } == 8 }
        assertTrue(runBlocking { entry.session().openVault!!.database.files().all().none { it.name == "photo3.jpg" } })
    }

    @Test
    fun selectAllAndBackLeavesSelection() {
        compose.onNodeWithContentDescription("Select").performClick()
        compose.onNodeWithText("Select all").performClick()
        waitForText("9 selected")
        compose.onNodeWithText("Deselect all").performClick()
        waitForText("Select items")
        compose.onNodeWithContentDescription("Stop selecting").performClick()
        waitForText("9 ITEMS")
        assertEquals(0, compose.onAllNodes(hasText("Shred")).fetchSemanticsNodes().size)
    }
}
