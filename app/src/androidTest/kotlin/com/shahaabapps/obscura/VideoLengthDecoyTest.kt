package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.DurationLimit
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.vault.UnlockResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the decoy vault can see of the real vault's video length limit.
 *
 * The gallery's settings live in one `obscura.gallery` prefs file for the whole app, with no
 * decoy of their own -- so the sort, the filter and the column count already cross over. This
 * pins down whether the new limit crosses with them, and how loudly, so the answer is on
 * record rather than assumed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoLengthDecoyTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = VideoLengthFixture.entry
    private val real = VideoLengthFixture.PASSPHRASE
    private val decoy = "harbour lantern thicket pebble"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        TestScreen.wake()
        VideoLengthFixture.wipe()
        runBlocking {
            VideoLengthFixture.create()
            entry.session().removeDecoy()
            assertEquals(UnlockResult.Success, entry.session().createDecoy(decoy.toCharArray(), null))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        // Removing the decoy needs the real vault open, so it cannot happen inside wipe().
        runBlocking {
            if (entry.session().openVault?.isDecoy != false) {
                instrumentation.runOnMainSync { entry.session().lock() }
                entry.session().unlockWithPassphrase(real.toCharArray())
            }
            entry.session().removeDecoy()
        }
        VideoLengthFixture.wipe()
        TestScreen.wake()
    }

    /**
     * A limit set in the real vault, then a decoy session. Whatever the decoy shows is what an
     * onlooker holding the decoy passphrase would see.
     */
    @Test
    fun whatTheDecoySeesOfTheRealVaultsLengthLimit() {
        // The real vault narrows to videos under 45 seconds.
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, duration = DurationLimit(45, true)) }
        }
        assertEquals(45, context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).getInt("durationSeconds", 0))

        // Into the decoy.
        instrumentation.runOnMainSync { entry.session().lock() }
        assertEquals(UnlockResult.Success, runBlocking { entry.session().unlockWithPassphrase(decoy.toCharArray()) })
        assertTrue("should be in the decoy", entry.session().openVault!!.isDecoy)

        // The settings store is one singleton over one prefs file, so the decoy session starts
        // on whatever the real one left. This assertion records the behaviour as it is.
        val inDecoy = entry.gallerySettings().settings.value
        assertEquals(GalleryFilter.VIDEOS, inDecoy.filter)
        assertEquals(DurationLimit(45, true), inDecoy.duration)

        // On screen. Opening the gallery from the home tile resets the filter to All, so the
        // pill is not there to begin with -- but the length behind it was not reset with the
        // filter, and one tap on Videos brings it back out.
        openTheGallery()
        compose.onAllNodesWithTag("Video length").assertCountEquals(0)
        compose.onNodeWithText("Videos").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video length"), 20_000)
        assertTrue(
            "the decoy shows a length only the real vault's owner ever chose",
            compose.onAllNodesWithText("Over 45s").fetchSemanticsNodes().isNotEmpty(),
        )
        // Nothing of the real vault's files, at least.
        compose.onAllNodesWithContentDescription("Video, feature.mp4").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("JPG, beach.jpg").assertCountEquals(0)
    }

    private fun openTheGallery() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Videos"), 20_000)
    }

    /** And the other way: a limit set inside the decoy follows the user back into the real vault. */
    @Test
    fun aLimitSetInsideTheDecoyFollowsTheUserBackIntoTheRealVault() {
        instrumentation.runOnMainSync { entry.session().lock() }
        assertEquals(UnlockResult.Success, runBlocking { entry.session().unlockWithPassphrase(decoy.toCharArray()) })
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, duration = DurationLimit(7, false)) }
        }

        instrumentation.runOnMainSync { entry.session().lock() }
        assertEquals(UnlockResult.Success, runBlocking { entry.session().unlockWithPassphrase(real.toCharArray()) })
        assertEquals(DurationLimit(7, false), entry.gallerySettings().settings.value.duration)

        // Which means the real vault's own videos filter opens narrowed by a limit the user set
        // in the other vault, with no sign of where it came from beyond the pill itself.
        openTheGallery()
        compose.onNodeWithText("Videos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, quick.mp4"), 20_000)
        compose.waitUntilDoesNotExist(hasContentDescription("Video, feature.mp4"), 15_000)
        compose.onAllNodesWithTag("Video length").assertCountEquals(1)
        assertTrue(
            "the pill at least names the limit that is hiding a file",
            compose.onAllNodesWithText("Under 7s").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
