package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.GalleryEntry
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.gallery.GalleryLogic
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The month sheet when the ground shifts under it: the vault locks, the filter changes, files
 * go away. The sheet holds each month's place in a grid that was built when it opened, so a
 * grid that has since changed is exactly where this could come apart.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JumpToMonthInterruptionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(color) }
            .compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun millis(y: Int, m: Int, d: Int) =
        LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

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
            // Eighteen months of photos, with one GIF in the oldest month only, so a filter
            // change shrinks the grid from eighteen months to one.
            var year = 2024
            var month = 1
            repeat(18) { index ->
                repeat(3) { within ->
                    files.put(
                        jpeg(Color.rgb(40 + index * 8, 90, 200 - index * 6)).inputStream(),
                        IncomingFile("p$year-$month-$within.jpg", "image/jpeg", millis(year, month, 2 + within * 6), 48, 48, null),
                    )
                }
                month++
                if (month > 12) { month = 1; year++ }
            }
            // A single GIF, in the oldest month, so the GIFs filter leaves exactly one month.
            files.put(
                jpeg(Color.GREEN).inputStream(),
                IncomingFile("one.gif", "image/gif", millis(2024, 1, 4), 48, 48, null),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    private fun intoTheGallery() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 25_000)
        compose.onAllNodesWithText("Photos")[0].performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 20_000)
    }

    private fun openTheSheet() {
        compose.onNodeWithContentDescription("Jump to a month").performClick()
        compose.waitUntilAtLeastOneExists(hasText("months in this view", substring = true), 15_000)
    }

    /**
     * Every month the sheet offers must be a month the grid really holds, with the same count:
     * the months come from the grid itself, and this is the check that they still do under each
     * filter.
     */
    @Test
    fun everyMonthOfferedIsAMonthTheGridHolds(): Unit = runBlocking {
        val all = entry.session().openVault!!.database.files().all()
        for (filter in GalleryFilter.entries) {
            val settings = com.shahaabapps.obscura.ui.gallery.GallerySettings(filter = filter)
            val entries = GalleryLogic.build(all, settings)
            val months = GalleryLogic.months(entries)
            // Each month's heading really sits at the index it claims, and its count is the
            // number of items that follow it before the next heading.
            months.forEach { mark ->
                val entry = entries[mark.entryIndex]
                assertTrue("entry ${mark.entryIndex} is a heading for ${mark.label}", entry is GalleryEntry.Header)
                val header = entry as GalleryEntry.Header
                assertEquals("${mark.label} heading matches", header.month to header.year, mark.month to mark.year)
                val following = entries.drop(mark.entryIndex + 1).takeWhile { it is GalleryEntry.Item }.size
                assertEquals("${mark.label} count under $filter", following, mark.count)
            }
            assertEquals(
                "no month is offered twice under $filter",
                months.map { it.label }.distinct().size,
                months.size,
            )
        }
    }

    /** The filter changing under an open sheet leaves stale places in it; a jump must still be safe. */
    @Test
    fun aStalePlaceFromBeforeTheFilterChangedDoesNotCrash() {
        intoTheGallery()
        openTheSheet()
        // Eighteen months are listed, the last of them far down a grid of seventy-odd entries.
        compose.onAllNodesWithText("December").assertCountEquals(1)

        // The filter changes without a tap on the sheet, as a background change would do it.
        // The GIFs filter leaves one month and four entries, so every place the sheet is
        // holding is now past the end of the grid.
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.GIFS) }
        }
        compose.waitForIdle()

        // Jumping to a month that is no longer in the grid must not bring the app down.
        compose.onAllNodesWithText("December").let { if (it.fetchSemanticsNodes().isNotEmpty()) it[0].performClick() }
        compose.waitForIdle()
        Thread.sleep(1_500)

        // Still alive, and showing the one month the GIFs filter leaves.
        compose.onAllNodesWithText("Reel mode").assertCountEquals(1)
    }

    /** Locking the vault with the sheet open must take the screen away, not leave months on it. */
    @Test
    fun lockingWithTheSheetOpenTakesTheScreenAway() {
        intoTheGallery()
        openTheSheet()

        instrumentation.runOnMainSync { entry.session().lock() }
        // With no PIN set this vault asks for the passphrase; with one it says "Locked."
        compose.waitUntilAtLeastOneExists(hasText("Enter your passphrase.", substring = true), 15_000)

        // No month, and no month sheet, is left on the locked screen.
        compose.onAllNodesWithText("months in this view", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("December").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Jump to a month").assertCountEquals(0)
    }

    /** Files going away under an open sheet: the places it holds no longer point anywhere. */
    @Test
    fun filesVanishingUnderTheSheetDoesNotCrashAJump(): Unit = runBlocking {
        intoTheGallery()
        openTheSheet()

        val vault = entry.session().openVault!!
        // Everything but the newest three months goes.
        val doomed = vault.database.files().all().sortedByDescending { it.takenAt }.drop(9)
        doomed.forEach { vault.shred(it) }
        compose.waitForIdle()
        Thread.sleep(1_500)

        compose.onAllNodesWithText("January").let { if (it.fetchSemanticsNodes().isNotEmpty()) it[0].performClick() }
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.onAllNodesWithText("Reel mode").assertCountEquals(1)
    }
}
