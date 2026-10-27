package com.shahaabapps.obscura

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.DurationLimit
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.gallery.GalleryLogic
import com.shahaabapps.obscura.ui.gallery.GallerySettingsStore
import com.shahaabapps.obscura.vault.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two halves of a real process death, which cannot happen inside one instrumentation run:
 * `am force-stop` takes the test runner down with the app. Neither half is part of the suite;
 * they are driven from a shell, in order, with the kill in between:
 *
 *   adb shell am instrument -w -e videoLengthPhase seed \
 *     -e class com.shahaabapps.obscura.VideoLengthProcessDeathTest#seedALimitAndLeaveIt \
 *     com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *   adb shell am force-stop com.shahaabapps.Obscura
 *   adb shell am start -n com.shahaabapps.Obscura/com.shahaabapps.obscura.MainActivity
 *   adb shell am instrument -w -e videoLengthPhase check \
 *     -e class com.shahaabapps.obscura.VideoLengthProcessDeathTest#checkTheLimitCameBackAndTheVaultDidNot \
 *     com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *
 * A third phase stands on its own: write a prefs file no version of the sheet could have
 * produced, then run `theGalleryOpensOnAPrefsFileThatCouldNotHaveComeFromTheSheet` with
 * `-e videoLengthPhase corrupt`. It is the file the next process really reads, rather than a
 * store built beside the one the app is using.
 *
 * Without the phase argument every half skips itself, so a whole-suite run is not left holding
 * a vault that half of a journey walked away from.
 */
@RunWith(AndroidJUnit4::class)
class VideoLengthProcessDeathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = VideoLengthFixture.entry

    private fun phase(): String? = InstrumentationRegistry.getArguments().getString("videoLengthPhase")

    /** The limit this pair is about: over forty-five seconds, on the videos filter. */
    private val limit = DurationLimit(45, true)

    /** Half one: a vault with two clips and a limit in force, left exactly as it is. */
    @Test
    fun seedALimitAndLeaveIt() {
        assumeTrue("run with -e videoLengthPhase seed", phase() == "seed")
        VideoLengthFixture.wipe()
        runBlocking { VideoLengthFixture.create() }
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, duration = limit) }
        }
        val prefs = context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE)
        assertEquals(45, prefs.getInt("durationSeconds", 0))
        assertTrue(prefs.getBoolean("durationLonger", false))
        assertTrue("seeded unlocked", entry.session().state.value is VaultState.Unlocked)
        println("SEEDED limit=$limit files=${runBlocking { entry.session().openVault!!.database.files().all() }.size}")
    }

    /**
     * Half two, in the process the relaunch made: the vault must be shut, and the limit must be
     * the one that was set -- not gone, not widened, not a different number.
     */
    @Test
    fun checkTheLimitCameBackAndTheVaultDidNot() {
        assumeTrue("run with -e videoLengthPhase check", phase() == "check")
        assertFalse(
            "a killed and relaunched process must come back locked, not unlocked",
            entry.session().state.value is VaultState.Unlocked,
        )
        assertTrue("the vault is still there", entry.session().state.value is VaultState.Locked)

        val settings = GallerySettingsStore(context).settings.value
        assertEquals("the limit the user set", limit, settings.duration)
        assertEquals(GalleryFilter.VIDEOS, settings.filter)
        // And the app's own singleton, built on the way to the first screen, agrees.
        assertEquals(limit, entry.gallerySettings().settings.value.duration)
        // Nothing of the vault leaks out of a locked session, so the grid is empty either way.
        assertEquals(0, GalleryLogic.build(emptyList(), settings).size)
        println("AFTER KILL state=${entry.session().state.value::class.simpleName} duration=${settings.duration}")
    }

    /**
     * A prefs file no version of the sheet could have written, read by the process that is
     * really going to use it. The gallery is built on the way to the first screen, so a value
     * of the wrong type here has to end as no limit rather than as a crash with no way back.
     *
     * Write the file first, with the app stopped:
     *
     *   adb shell am force-stop com.shahaabapps.Obscura
     *   adb shell "run-as com.shahaabapps.Obscura sh -c 'cat > ./shared_prefs/obscura.gallery.xml'" <<XML
     *   ... a map with <string name="durationSeconds">boom</string> ...
     *   XML
     */
    @Test
    fun theGalleryOpensOnAPrefsFileThatCouldNotHaveComeFromTheSheet() {
        assumeTrue("run with -e videoLengthPhase corrupt", phase() == "corrupt")
        // The singleton was built from that file when this process started.
        val live = entry.gallerySettings().settings.value
        assertEquals("nonsense seconds must read as no limit", null, live.duration)
        println("CORRUPT FILE read as filter=${live.filter} duration=${live.duration} columns=${live.columns}")

        // And the gallery really builds from it, rather than throwing on the way to the screen.
        val built = GalleryLogic.build(emptyList(), live)
        assertEquals(0, built.size)
        // Writing anything puts the file back into a shape the next process can read.
        instrumentation.runOnMainSync { entry.gallerySettings().update { it } }
        val prefs = context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE)
        assertEquals("the bad value is replaced, not left to be read again", 0, prefs.getInt("durationSeconds", -1))
    }
}
