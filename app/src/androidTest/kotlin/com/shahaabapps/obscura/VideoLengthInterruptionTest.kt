package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.DurationLimit
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.gallery.GalleryLogic
import com.shahaabapps.obscura.ui.gallery.GallerySettings
import com.shahaabapps.obscura.ui.gallery.GallerySettingsStore
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video length filter when a real person interrupts it: the vault locks under the open
 * sheet, the phone goes face-down mid-typing, the app is sent away and rotated, the limit
 * empties the grid, and a selection outlives the limit that made it.
 *
 * What is being hunted here is not the filter's arithmetic (unit tests cover that) but the
 * things around it: a hold on the vault that is never released, a sheet left over a locked
 * screen, a grid that claims more than it shows, and a bulk action that reaches a file the user
 * can no longer see.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoLengthInterruptionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = VideoLengthFixture.entry
    private var scenario: ActivityScenario<MainActivity>? = null
    private var rotated = false

    private fun prefs() = context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        TestScreen.wake()
        VideoLengthFixture.wipe()
        runBlocking {
            val (quick, feature) = VideoLengthFixture.create()
            // The journeys rest on these two sitting either side of five seconds.
            assertTrue("short clip is ${quick}ms", quick in 1_000..4_000)
            assertTrue("long clip is ${feature}ms", feature in 6_000..20_000)
        }
    }

    @After
    fun tearDown() {
        if (rotated) {
            TestScreen.shell("settings put system user_rotation 0")
            TestScreen.shell("settings put system accelerometer_rotation 1")
            rotated = false
        }
        entry.autoLock().delayMs = AutoLock.DEFAULT_DELAY_MS
        TestScreen.wake()
        scenario?.close()
        scenario = null
        VideoLengthFixture.wipe()
        // The screen must be left on and awake for whatever runs next.
        TestScreen.wake()
    }

    // ---- getting there ----

    private fun launch() {
        // A test with no screen of its own leaves the app "away" for as long as the test before
        // it took. Auto-lock reads that clock on the way in and would lock the vault the moment
        // the activity appears, which is the harness's doing and not the app's. Restarting the
        // clock here puts the app where a user who has just opened it would be.
        instrumentation.runOnMainSync { entry.autoLock().onStop(FakeOwner) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Stands in for the process lifecycle owner, so "away since" can be restarted exactly. */
    private object FakeOwner : androidx.lifecycle.LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
    }

    private fun openVideos() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("3 ITEMS", substring = true), 20_000)
        compose.onNodeWithText("Videos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, feature.mp4"), 15_000)
    }

    private fun openTheLengthSheet() {
        compose.onNodeWithTag("Video length").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Show only the videos on one side of a length.", substring = true), 10_000)
    }

    private fun sheetGone() {
        compose.waitUntilDoesNotExist(
            hasText("Show only the videos on one side of a length.", substring = true),
            10_000,
        )
        // The sheet's window is still on its way out for a moment after its content goes.
        Thread.sleep(400)
    }

    private fun limitShows(label: String) =
        compose.waitUntilAtLeastOneExists(hasTestTag("Video length") and hasText(label), 10_000)

    private fun shownVideos() = listOf("quick.mp4", "feature.mp4")
        .filter { compose.onAllNodesWithContentDescription("Video, $it").fetchSemanticsNodes().isNotEmpty() }

    /** Types [seconds] into the sheet with the field focused, so the keyboard really comes up. */
    private fun typeLength(seconds: String) {
        compose.onNodeWithTag("Seconds").performClick()
        compose.onNodeWithTag("Seconds").performTextInput(seconds)
        compose.waitForIdle()
    }

    /**
     * Whether a keyboard is really on screen, as the input method manager sees it. Focusing the
     * field is not the same thing, and "with the keyboard up" is half of what these journeys
     * are about -- the sheet, the IME and the vault are three windows that have to go together.
     */
    private fun keyboardIsUp(): Boolean {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            val shown = TestScreen.shell("dumpsys input_method").lineSequence()
                .firstOrNull { it.contains("mInputShown=") }
                ?.substringAfter("mInputShown=")
                ?.take(4)
            if (shown?.startsWith("true") == true) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun applyTyped(seconds: String) {
        typeLength(seconds)
        compose.onNodeWithTag("Seconds").performImeAction()
        sheetGone()
    }

    private fun waitForLock(timeoutMs: Long = 8_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (entry.session().state.value is VaultState.Locked) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Waits until Obscura is the activity on top, as the window manager sees it. Asked of the
     * activity scenario instead, a stopped activity that the shell brought back reads as merely
     * created for a while, which says nothing about whether the app is on screen.
     */
    private fun waitForObscuraOnTop() {
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) {
            val top = TestScreen.shell("dumpsys activity activities").lineSequence()
                .firstOrNull { it.contains("ResumedActivity:") }
                .orEmpty()
            if (top.contains("com.shahaabapps.obscura.MainActivity")) {
                Thread.sleep(1_500) // let the first frame land before anything is read off it
                return
            }
            Thread.sleep(500)
        }
        throw AssertionError("Obscura never came back to the front")
    }

    private fun assertLockScreenWithNothingBehindIt() {
        compose.waitUntilAtLeastOneExists(hasText("Enter your passphrase.", substring = true), 20_000)
        compose.onAllNodesWithText("Show only the videos on one side of a length.", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("Video length").assertCountEquals(0)
        compose.onAllNodesWithTag("Seconds").assertCountEquals(0)
        compose.onAllNodesWithText("Under").assertCountEquals(0)
        compose.onAllNodesWithText("Over").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Video, feature.mp4").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Video, quick.mp4").assertCountEquals(0)
    }

    // ---- 1. locking under the open sheet ----

    /**
     * The screen going off is the one interruption the vault must never survive. With the sheet
     * open, the keyboard up and a half-typed number in the field, it still has to lock, and
     * neither the sheet nor the keyboard may be left over the lock screen.
     */
    @Test
    fun theScreenGoingOffWhileALengthIsBeingTypedLocksTheVault() {
        launch()
        openVideos()
        openTheLengthSheet()
        typeLength("4")
        // Half-typed: the number is on screen, the keyboard is over the sheet, and nothing has
        // been applied yet.
        compose.onNodeWithText("Under 4s").assertIsDisplayed()
        assertTrue("the keyboard should be up over the sheet", keyboardIsUp())
        assertNull("nothing applied yet", entry.gallerySettings().settings.value.duration)

        TestScreen.sleep()
        assertTrue("the vault must lock when the screen goes off", waitForLock())
        assertFalse("no hold may be left owing a lock", entry.autoLock().isLockDeferred)

        TestScreen.wake()
        assertLockScreenWithNothingBehindIt()
    }

    /** The same with a limit already in force: locking must not leave the grid behind either. */
    @Test
    fun theScreenGoingOffWithALimitInForceLocksAndClearsTheGrid() {
        launch()
        openVideos()
        openTheLengthSheet()
        applyTyped("5")
        limitShows("Under 5s")
        compose.waitUntilDoesNotExist(hasContentDescription("Video, feature.mp4"), 10_000)

        TestScreen.sleep()
        assertTrue("the vault must lock when the screen goes off", waitForLock())
        TestScreen.wake()
        assertLockScreenWithNothingBehindIt()
        // And the limit is still the user's when they come back.
        assertEquals(DurationLimit(5, false), entry.gallerySettings().settings.value.duration)
    }

    /** Laying the phone face-down with the keyboard up must lock it then and there. */
    @Test
    fun aPanicFlipWithTheSheetOpenAndTheKeyboardUpLocksAtOnce() {
        launch()
        openVideos()
        openTheLengthSheet()
        typeLength("12")
        assertTrue("the keyboard should be up over the sheet", keyboardIsUp())

        entry.panicLock().trigger()
        assertTrue("a panic flip must lock the vault", waitForLock())
        assertFalse("no hold may be left owing a lock", entry.autoLock().isLockDeferred)
        assertLockScreenWithNothingBehindIt()
    }

    /**
     * Opening and closing the sheet, with and without applying a limit, must not take a hold on
     * the vault: a leaked hold shows up as a vault that will no longer lock at all.
     */
    @Test
    fun usingTheSheetTakesNoHoldSoTheVaultStillLocksAfterwards() {
        launch()
        openVideos()
        // Open it and walk away, apply a preset, open again and lift it: three ways through.
        openTheLengthSheet()
        compose.onNodeWithTag("Over").performClick()
        compose.onNodeWithText("Video length").performClick() // a tap that changes nothing
        TestScreen.shell("input keyevent KEYCODE_BACK")
        sheetGone()

        openTheLengthSheet()
        compose.onNodeWithTag("Length 30s").performClick()
        sheetGone()
        limitShows("Under 30s")

        openTheLengthSheet()
        compose.onNodeWithText("Any length").performClick()
        sheetGone()
        limitShows("Any length")

        assertFalse("no lock should be owed", entry.autoLock().isLockDeferred)
        TestScreen.sleep()
        assertTrue("the vault must still lock on screen off", waitForLock())
        assertFalse(entry.autoLock().isLockDeferred)
        TestScreen.wake()
    }

    // ---- 2. process death ----

    /**
     * What a fresh process reads back. A limit set on screen has to be on disk, in the shape the
     * next process will read, and reading it has to give the same limit back -- not a stale one
     * and not a wider one.
     */
    @Test
    fun theLimitInForceIsOnDiskSoAFreshProcessComesBackToIt() {
        launch()
        openVideos()
        openTheLengthSheet()
        compose.onNodeWithTag("Over").performClick()
        applyTyped("45")
        limitShows("Over 45s")

        // On disk, under the keys a new process reads.
        assertEquals(45, prefs().getInt("durationSeconds", 0))
        assertTrue(prefs().getBoolean("durationLonger", false))
        assertEquals("VIDEOS", prefs().getString("filter", null))

        // A store built from scratch is exactly what the next process does.
        val fresh = GallerySettingsStore(context)
        assertEquals(DurationLimit(45, true), fresh.settings.value.duration)
        assertEquals(GalleryFilter.VIDEOS, fresh.settings.value.filter)

        // Lifting it must be just as durable: no limit left behind on disk.
        openTheLengthSheet()
        compose.onNodeWithText("Any length").performClick()
        sheetGone()
        limitShows("Any length")
        assertEquals(0, prefs().getInt("durationSeconds", -1))
        assertNull(GallerySettingsStore(context).settings.value.duration)
    }

    /**
     * A stored limit that could never have come from the sheet -- the wrong type, zero,
     * negative, past a day -- must read back as no limit. This is built on the way to the first
     * screen, so anything else takes the whole gallery down with no way back.
     */
    @Test
    fun aStoredLimitThatCouldNotHaveComeFromTheSheetFallsBackToAnyLength() {
        val nonsense: List<Pair<String, (android.content.SharedPreferences.Editor) -> Unit>> = listOf(
            "a string where the seconds go" to { e -> e.putString("durationSeconds", "boom") },
            "a float where the seconds go" to { e -> e.putFloat("durationSeconds", 12.5f) },
            "zero seconds" to { e -> e.putInt("durationSeconds", 0) },
            "negative seconds" to { e -> e.putInt("durationSeconds", -5) },
            "longer than a day" to { e -> e.putInt("durationSeconds", DurationLimit.MAX_SECONDS + 1) },
            "Int.MIN_VALUE" to { e -> e.putInt("durationSeconds", Int.MIN_VALUE) },
            "Int.MAX_VALUE" to { e -> e.putInt("durationSeconds", Int.MAX_VALUE) },
        )
        nonsense.forEach { (what, write) ->
            prefs().edit().clear().also(write).commit()
            val store = GallerySettingsStore(context)
            assertNull("$what should read back as no limit", store.settings.value.duration)
            // And the gallery can still be built from it.
            GalleryLogic.build(emptyList(), store.settings.value)
        }

        // A sound length with a nonsense side keeps the length and falls back on the side.
        prefs().edit().clear().putInt("durationSeconds", 30).putString("durationLonger", "yes").commit()
        assertEquals(DurationLimit(30, false), GallerySettingsStore(context).settings.value.duration)

        // The edges that are legal really are kept.
        prefs().edit().clear().putInt("durationSeconds", 1).commit()
        assertEquals(DurationLimit(1, false), GallerySettingsStore(context).settings.value.duration)
        prefs().edit().clear().putInt("durationSeconds", DurationLimit.MAX_SECONDS).putBoolean("durationLonger", true).commit()
        assertEquals(DurationLimit(DurationLimit.MAX_SECONDS, true), GallerySettingsStore(context).settings.value.duration)
    }

    // ---- 3. backgrounding, screen off, rotation ----

    /** Sent away with the sheet open: it locks after the delay and comes back to the lock screen. */
    @Test
    fun goingHomeWithTheSheetOpenLocksAfterTheDelay() {
        launch()
        openVideos()
        openTheLengthSheet()
        typeLength("7")

        // Shortened only now: set before the activity came up, the app is already "away" and
        // locks on the way in, so there would be no gallery to interrupt.
        entry.autoLock().delayMs = 500
        TestScreen.shell("input keyevent KEYCODE_HOME")
        assertTrue("the vault must lock after the app leaves the screen", waitForLock(10_000))

        // Back to the app, and give the activity time to really be on screen again before
        // anything is read off it.
        TestScreen.shell("am start -n com.shahaabapps.Obscura/com.shahaabapps.obscura.MainActivity")
        waitForObscuraOnTop()
        assertLockScreenWithNothingBehindIt()
    }

    /**
     * Rotation with a limit narrowing the grid. Nothing may crash, the limit has to survive the
     * activity being rebuilt, and the grid must not claim items it is not showing: selecting
     * everything has to come to the one video on screen, not the two in the vault.
     */
    @Test
    fun rotatingWithALimitNarrowingTheGridLeavesTheGridHonest() {
        launch()
        openVideos()
        openTheLengthSheet()
        applyTyped("5")
        limitShows("Under 5s")
        compose.waitUntilDoesNotExist(hasContentDescription("Video, feature.mp4"), 10_000)

        rotated = true
        TestScreen.shell("settings put system accelerometer_rotation 0")
        TestScreen.shell("settings put system user_rotation 1") // landscape
        Thread.sleep(2_000)
        compose.waitForIdle()
        TestScreen.shell("settings put system user_rotation 0") // and back
        Thread.sleep(2_000)
        compose.waitForIdle()

        // Alive, still on the videos filter, still holding the limit the user set.
        limitShows("Under 5s")
        assertEquals(DurationLimit(5, false), entry.gallerySettings().settings.value.duration)
        assertEquals(listOf("quick.mp4"), shownVideos())

        // The grid is honest about what it holds: select all comes to one, not two.
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Select all"), 10_000)
        compose.onNodeWithText("Select all").performClick()
        compose.waitUntilAtLeastOneExists(hasText("1 selected"), 10_000)
        compose.onAllNodesWithText("2 selected").assertCountEquals(0)
        compose.onNodeWithContentDescription("Stop selecting").performClick()
    }

    /** The sheet open across a rotation: no crash, and the limit can still be applied after. */
    @Test
    fun rotatingWithTheSheetOpenDoesNotCrashAndTheLimitCanStillBeSet() {
        launch()
        openVideos()
        openTheLengthSheet()
        typeLength("5")

        rotated = true
        TestScreen.shell("settings put system accelerometer_rotation 0")
        TestScreen.shell("settings put system user_rotation 1")
        Thread.sleep(2_500)
        compose.waitForIdle()
        TestScreen.shell("settings put system user_rotation 0")
        Thread.sleep(2_500)
        compose.waitForIdle()

        // Whatever became of the sheet, the gallery is alive and the limit is still reachable.
        compose.waitUntilAtLeastOneExists(hasTestTag("Video length"), 20_000)
        if (compose.onAllNodesWithTag("Seconds").fetchSemanticsNodes().isEmpty()) openTheLengthSheet()
        compose.onNodeWithTag("Length 10s").performClick()
        sheetGone()
        limitShows("Under 10s")
        assertEquals(listOf("quick.mp4"), shownVideos())
    }

    // ---- 4. a limit that empties the grid ----

    /**
     * A limit nothing matches is the one a user has to be able to get out of. The empty state
     * has to say why, and the pill that caused it has to still be on screen and still work.
     */
    @Test
    fun aLimitThatEmptiesTheGridCanStillBeSeenAndLifted() {
        launch()
        openVideos()
        openTheLengthSheet()
        applyTyped("1")
        sheetGone()

        // Nothing matches, and it says so rather than looking like an empty vault.
        compose.waitUntilAtLeastOneExists(hasText("Nothing matches this filter.", substring = true), 15_000)
        compose.onAllNodesWithText("No photos or videos yet.", substring = true).assertCountEquals(0)
        assertEquals(emptyList<String>(), shownVideos())

        // The way out is on screen: the pill still shows the limit and still opens the sheet.
        compose.onNodeWithTag("Video length").assertIsDisplayed()
        limitShows("Under 1s")
        openTheLengthSheet()
        compose.onNodeWithText("Any length").performClick()
        sheetGone()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, feature.mp4"), 15_000)
        assertEquals(listOf("quick.mp4", "feature.mp4"), shownVideos())
    }

    /** An empty grid also must not leave the header or the sheets claiming anything. */
    @Test
    fun anEmptyGridUnderALimitDoesNotClaimItemsItIsNotShowing() {
        launch()
        openVideos()
        openTheLengthSheet()
        applyTyped("1")
        compose.waitUntilAtLeastOneExists(hasText("Nothing matches this filter.", substring = true), 15_000)

        // Select mode over an empty grid: selecting everything selects nothing.
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Select all"), 10_000)
        compose.onNodeWithText("Select all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Select items").assertIsDisplayed()
        compose.onAllNodesWithText("selected", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Stop selecting").performClick()

        // Reel mode and duplicates are offered off the grid, so they are gone with it: a reel
        // over nothing would have nothing to show.
        compose.onAllNodesWithText("Reel mode").assertCountEquals(0)
    }

    // ---- 6. selection against the limit ----

    /**
     * Two videos selected, then the limit narrowed so one of them is no longer on screen. The
     * shred must reach only what the user can see: a file that vanished behind the filter is
     * not a file they asked to destroy.
     */
    @Test
    fun narrowingTheLimitAfterSelectingLeavesWhatVanishedUntouched() {
        launch()
        openVideos()

        // Select both videos.
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Select all"), 10_000)
        compose.onNodeWithText("Select all").performClick()
        compose.waitUntilAtLeastOneExists(hasText("2 selected"), 10_000)

        // Now narrow the limit under the selection, the way a background change would do it:
        // the pill is not reachable in selection mode, so this is the honest way in.
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(duration = DurationLimit(5, true)) }
        }
        compose.waitUntilDoesNotExist(hasContentDescription("Video, quick.mp4"), 10_000)
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals(listOf("feature.mp4"), shownVideos())

        // Shred what is selected.
        compose.onNodeWithText("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred", substring = true) and hasAnyAncestor(isDialog()), 10_000)
        compose.onNode(hasText("Shred") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        Thread.sleep(2_500)

        val left = runBlocking { entry.session().openVault!!.database.files().all() }.map { it.name }.sorted()
        // The one still on screen went. The one the limit had hidden is untouched.
        assertTrue("quick.mp4 was behind the limit and must survive: $left", "quick.mp4" in left)
        assertFalse("feature.mp4 was on screen and selected: $left", "feature.mp4" in left)
        assertTrue("the photo is nothing to do with this: $left", "beach.jpg" in left)
    }

    /** The text really on the matched nodes, for saying in a failure what the screen said. */
    private fun textsOf(nodes: androidx.compose.ui.test.SemanticsNodeInteractionCollection): List<String> =
        nodes.fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text }
        }

    /**
     * The same thing with no back door: the pill sits above the grid in selection mode too, so
     * a user can narrow the length with two videos already picked. Whatever the header and the
     * confirmation then say is what they will act on.
     */
    @Test
    fun narrowingTheLengthByHandWhileSelectingDoesNotOverStateTheShred() {
        launch()
        openVideos()
        compose.onNodeWithContentDescription("Select").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Select all"), 10_000)
        compose.onNodeWithText("Select all").performClick()
        compose.waitUntilAtLeastOneExists(hasText("2 selected"), 10_000)

        // The pill is reachable while selecting, so this is an ordinary thing to do.
        compose.onNodeWithTag("Video length").assertIsDisplayed()
        openTheLengthSheet()
        compose.onNodeWithTag("Over").performClick()
        applyTyped("5")
        limitShows("Over 5s")
        compose.waitUntilDoesNotExist(hasContentDescription("Video, quick.mp4"), 10_000)
        Thread.sleep(500)
        assertEquals(listOf("feature.mp4"), shownVideos())

        val header = textsOf(compose.onAllNodesWithText("selected", substring = true))
        compose.onNodeWithText("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred", substring = true) and hasAnyAncestor(isDialog()), 10_000)
        val title = textsOf(compose.onAllNodes(hasText("file", substring = true) and hasAnyAncestor(isDialog())))
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Stop selecting").performClick()

        assertEquals(
            "one video is on screen, so one is what the user is being asked about",
            listOf("1 selected") to listOf("Shred 1 file?"),
            header to title,
        )
    }

    /**
     * Leaving the gallery and coming back in through the home tile. That tile deliberately
     * resets the filter -- "the whole gallery, whatever filter was used last" -- so what
     * happens to the length behind it matters: a limit that survives a reset meant to clear the
     * view is one the user has to meet again without having asked for it.
     *
     * Whatever it does, the pill must tell them: the limit may not come back silently.
     */
    @Test
    fun aLengthThatSurvivesTheHomeTilesFilterResetIsStillShownOnThePill() {
        launch()
        openVideos()
        openTheLengthSheet()
        applyTyped("5")
        limitShows("Under 5s")
        compose.waitUntilDoesNotExist(hasContentDescription("Video, feature.mp4"), 10_000)

        // Out to the home screen and back in through the Photos tile, which resets the filter.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("3 ITEMS", substring = true), 20_000)

        // The filter really was reset: no pill, and both videos are back in the grid.
        compose.onAllNodesWithTag("Video length").assertCountEquals(0)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, feature.mp4"), 15_000)

        // Back on Videos, whatever the length now is, the pill says so.
        compose.onNodeWithText("Videos").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video length"), 15_000)
        val pill = textsOf(compose.onAllNodesWithTag("Video length"))
        val stillHidden = compose.onAllNodesWithContentDescription("Video, feature.mp4")
            .fetchSemanticsNodes().isEmpty()
        assertEquals(
            "a length still in force must be named on the pill, and no length must read as any length",
            if (stillHidden) listOf("Under 5s") else listOf("Any length"),
            pill,
        )
    }

    /** Settings only the gallery's own code can reach: the limit never applies off the videos filter. */
    @Test
    fun theLimitHasNoSayUnderAnyOtherFilter(): Unit = runBlocking {
        val all = entry.session().openVault!!.database.files().all()
        val limit = DurationLimit(5, false)
        GalleryFilter.entries.forEach { filter ->
            val withLimit = GalleryLogic.media(all, GallerySettings(filter = filter, duration = limit))
            val without = GalleryLogic.media(all, GallerySettings(filter = filter))
            if (filter == GalleryFilter.VIDEOS) {
                assertEquals("the limit must bite on videos", 1, withLimit.size)
            } else {
                assertEquals("the limit must not touch $filter", without.map { it.name }, withLimit.map { it.name })
            }
        }
    }
}
