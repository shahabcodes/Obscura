package com.shahaabapps.obscura

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.navigation.LastPlace
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swiping between videos when a real person interrupts it: a shred, a file arriving, a turn of
 * the phone, a pinch, a page scroll, the screen going off or a panic flip in the middle of the
 * gesture, a GIF being made, a search, and the decoy vault.
 *
 * The vault holds three clips of distinct lengths -- quick (2s), middle (5s), feature (10s) --
 * so what the player is really playing can be read off the length it reports under the seek
 * bar, independently of the name the screen prints above it. A screen whose name and length
 * disagree is labelling one file while playing another, and its Shred button points at the
 * label.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoSwipeInterruptionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = VideoLengthFixture.entry
    private var scenario: ActivityScenario<MainActivity>? = null
    private var rotated = false
    private var decoyMade = false
    /** Set by the seeding helper, so its vault is left for a hand to drive. */
    private val decoy = "harbour lantern thicket pebble"

    /** Seconds each clip really lasts, as the player will report it. */
    private val seconds = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        TestScreen.wake()
        // The system's one-time "Viewing full screen" cling sits over the app on a fresh device
        // and takes the taps meant for the picture; it is the system's, not the app's.
        TestScreen.shell("settings put secure immersive_mode_confirmations confirmed")
        LastPlace.forget()
        VideoLengthFixture.wipe()
        runBlocking {
            // Long clips, so nothing moves on by itself while a step is being checked.
            entry.session().create(VideoLengthFixture.PASSPHRASE.toCharArray())
            entry.session().openVault!!.files.put(
                VideoLengthFixture.jpeg().inputStream(),
                com.shahaabapps.obscura.vault.IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000),
            )
            seconds["quick.mp4"] = clip(30, "quick.mp4", 1_700_000_100_000)
            seconds["middle.mp4"] = clip(40, "middle.mp4", 1_700_000_150_000)
            seconds["feature.mp4"] = clip(50, "feature.mp4", 1_700_000_200_000)
        }
        // Oldest first: quick, middle, feature.
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, descending = false) }
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
        if (decoyMade) runBlocking {
            if (entry.session().openVault?.isDecoy != false) {
                instrumentation.runOnMainSync { entry.session().lock() }
                entry.session().unlockWithPassphrase(VideoLengthFixture.PASSPHRASE.toCharArray())
            }
            entry.session().removeDecoy()
        }
        LastPlace.forget()
        VideoLengthFixture.wipe()
        TestScreen.wake()
    }

    private suspend fun clip(secs: Int, name: String, takenAt: Long): Int =
        (VideoLengthFixture.seal(VideoLengthFixture.encodeMp4(secs, "fx-$name"), name, takenAt) / 1000).toInt()

    // ---- getting there ----

    private object FakeOwner : androidx.lifecycle.LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
    }

    private fun launch() {
        instrumentation.runOnMainSync { entry.autoLock().onStop(FakeOwner) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun openVideosFilter(items: Int = 4) {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("$items ITEMS", substring = true), 20_000)
        compose.onNodeWithText("Videos").performClick()
    }

    private fun play(name: String, items: Int = 4) {
        launch()
        openVideosFilter(items)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, $name"), 15_000)
        compose.onNodeWithContentDescription("Video, $name").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        showing(name)
    }

    // ---- reading the screen ----

    private fun texts(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** The name in the heading: the top-most text that is exactly a file name. */
    private fun title(): String? = texts()
        .filter { it.text().endsWith(".mp4") }
        .minByOrNull { it.positionInRoot.y }
        ?.text()

    /** The length the player itself reports, the right-hand time under the seek bar. */
    private fun playerSeconds(): Int? = texts()
        .filter { it.text().matches(Regex("\\d+:\\d\\d")) }
        .maxByOrNull { it.positionInRoot.x }
        ?.text()
        ?.split(':')
        ?.let { (m, s) -> m.toInt() * 60 + s.toInt() }

    private fun upNext(): List<String> {
        val heading = texts().firstOrNull { it.text() == "Up next" } ?: return emptyList()
        return texts().filter { it.text().endsWith(".mp4") && it.positionInRoot.y > heading.positionInRoot.y }
            .sortedBy { it.positionInRoot.y }.map { it.text() }
    }

    private fun dump() = texts().filter { it.text().endsWith(".mp4") || it.text().matches(Regex("\\d+:\\d\\d")) || it.text() == "Up next" }
        .sortedBy { it.positionInRoot.y }.joinToString { "${it.text()}@${it.positionInRoot.y.toInt()}" }

    private fun describe() = "[${dump()}] title=${title()} playerLength=${playerSeconds()}s upNext=${upNext()} error=${
        texts().firstOrNull { it.text().contains("couldn't be played") }?.text()
    }"

    /** Waits until the heading names [name] and the player is really playing a clip of its length. */
    private fun showing(name: String, timeoutMs: Long = 10_000) {
        val want = seconds.getValue(name)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            if (title() == name && playerSeconds()?.let { kotlin.math.abs(it - want) <= 1 } == true) return
            Thread.sleep(200)
        }
        throw AssertionError("expected $name (${want}s) on screen; saw ${describe()}")
    }

    /** The heading and the player agree on which file this is, whatever it is. */
    private fun labelMatchesPlayer(timeoutMs: Long = 6_000): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            val t = title()
            val p = playerSeconds()
            if (t != null && p != null && p > 0 && seconds[t]?.let { kotlin.math.abs(it - p) <= 1 } == true) return t
            Thread.sleep(250)
        }
        throw AssertionError("the name on screen and the clip playing disagree: ${describe()}")
    }

    /** A tap away from the Play button that sits in the middle of a paused picture. */
    private fun tapStage() {
        // The tap detector holds a single tap back until the double-tap window has passed, and
        // under the test rule that window runs on the rule's own clock, which does not move by
        // itself while nothing is drawing -- so it is moved on here. By hand there is no such
        // clock and the tap lands as soon as the window passes.
        compose.onNodeWithTag("Video stage").performTouchInput { click(Offset(width * 0.2f, height * 0.2f)) }
        compose.mainClock.advanceTimeBy(600)
        Thread.sleep(400)
        compose.waitForIdle()
    }

    private fun stage(): SemanticsNode = compose.onNodeWithTag("Video stage").fetchSemanticsNode()

    private fun stageCentreOnScreen(): Offset {
        val n = stage()
        return n.positionOnScreen + Offset(n.size.width / 2f, n.size.height / 2f)
    }

    /** A real swipe through the window manager, not one injected under any window above. */
    private fun realSwipe(dx: Int, dy: Int = 0, ms: Int = 250, from: Offset = stageCentreOnScreen()) {
        TestScreen.shell("input swipe ${from.x.toInt()} ${from.y.toInt()} ${(from.x + dx).toInt()} ${(from.y + dy).toInt()} $ms")
        Thread.sleep(400)
    }

    private fun rotate(landscape: Boolean) {
        rotated = true
        TestScreen.shell("settings put system accelerometer_rotation 0")
        TestScreen.shell("settings put system user_rotation ${if (landscape) 1 else 0}")
        Thread.sleep(2_500)
        compose.waitForIdle()
    }

    private fun waitForLock(timeoutMs: Long = 8_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (entry.session().state.value is VaultState.Locked) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun assertLockScreenWithNothingBehindIt() {
        compose.waitUntilAtLeastOneExists(hasText("passphrase", substring = true), 20_000)
        compose.onAllNodesWithTag("Video stage").assertCountEquals(0)
        listOf("quick.mp4", "middle.mp4", "feature.mp4").forEach {
            compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText("Make a GIF").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Pause").assertCountEquals(0)
    }

    private fun unlockThroughTheScreen(passphrase: String = VideoLengthFixture.PASSPHRASE) {
        // The pink panic confirmation swallows touches until it has been seen.
        if (entry.panicLock().panicked.value) {
            Thread.sleep(1_500)
            if (compose.onAllNodesWithText("Locked instantly.", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Locked instantly.", substring = true).performClick()
            }
            compose.waitUntil(10_000) { !entry.panicLock().panicked.value }
        }
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 20_000)
        compose.onNode(hasSetTextAction()).performTextInput(passphrase)
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(20_000) { entry.session().state.value is VaultState.Unlocked }
    }

    private fun videoNames(): List<String> = runBlocking {
        entry.session().openVault!!.database.files().observeAll().first().filter { it.kind == FileKind.VIDEO }.map { it.name }
    }

    /** Opens the shred dialog and says whom it names, then backs out of it. */
    private fun shredWouldTake(): String {
        compose.onNodeWithContentDescription("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred this file?"), 5_000)
        val said = compose.onAllNodes(hasText("will be removed from the vault", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().first().text()
        compose.onNodeWithText("Keep it").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shred this file?").fetchSemanticsNodes().isEmpty() }
        return said.substringAfter('"').substringBefore('"')
    }

    private fun shredWhatIsShowing() {
        compose.onNodeWithContentDescription("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred this file?"), 5_000)
        compose.onNode(hasText("Shred") and hasAnyAncestor(isDialog())).performClick()
    }

    private fun log(msg: String) = Log.i("SwipeE2E", msg)

    // ---- 6. shred, then swipe ----

    /**
     * Swipe onto the middle clip and shred it. The player's queue was set once when the screen
     * opened, but the list the heading is read from shrinks: after the shred, the heading must
     * still name what plays, a swipe must never land on the shredded file, and Shred must
     * never point at a file other than the one playing.
     */
    @Test
    fun shreddingTheVideoSwipedToThenSwipingAgainStaysHonest() {
        play("quick.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        showing("middle.mp4")

        shredWhatIsShowing()
        compose.waitUntil(10_000) { "middle.mp4" !in videoNames() }
        Thread.sleep(1_000)
        log("after shred: ${describe()}")
        // Wherever it lands, the heading must be the clip that is really there.
        labelMatchesPlayer()

        // Onwards: feature is the only thing after.
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        Thread.sleep(1_200)
        log("after swipe left: ${describe()}")
        showing("feature.mp4")
        assertEquals("Shred must name the clip that is playing", "feature.mp4", shredWouldTake())

        // Back: straight to quick, not onto the hole the shred left.
        compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
        Thread.sleep(1_200)
        log("after swipe right: ${describe()}")
        compose.onAllNodesWithText("couldn't be played", substring = true).assertCountEquals(0)
        showing("quick.mp4")
    }

    // ---- an import landing under the open player ----

    /**
     * An import still running when a video is opened adds files under the player. A clip
     * sorting ahead of the one playing must not shift the heading onto another file.
     */
    @Test
    fun aVideoArrivingWhileOneIsPlayingDoesNotRelabelIt() {
        play("middle.mp4")
        runBlocking {
            seconds["early.mp4"] = clip(20, "early.mp4", 1_699_000_000_000)
        }
        compose.waitUntil(10_000) { "early.mp4" in videoNames() }
        Thread.sleep(1_500)
        log("after arrival: ${describe()}")
        showing("middle.mp4")
        assertEquals("Shred must name the clip that is playing", "middle.mp4", shredWouldTake())
        // Up next is what comes after the clip playing: never the clip itself, and a row in it
        // plays the file it names.
        val next = upNext()
        log("up next after arrival: $next")
        val problems = mutableListOf<String>()
        if ("middle.mp4" in next) problems += "Up next lists the video already playing: $next"
        for (row in next.filter { it != "middle.mp4" } + next.filter { it == "middle.mp4" }) {
            val heading = texts().firstOrNull { it.text() == "Up next" } ?: break
            val node = texts().firstOrNull { it.text() == row && it.positionInRoot.y > heading.positionInRoot.y } ?: continue
            compose.onNode(SemanticsMatcher("up next $row") { it.id == node.id }, useUnmergedTree = true).performClick()
            Thread.sleep(1_500)
            compose.waitForIdle()
            val playing = playerSeconds()?.let { secs -> seconds.entries.firstOrNull { kotlin.math.abs(it.value - secs) <= 1 }?.key }
            log("tapped Up next '$row': plays $playing; ${describe()}")
            if (playing != row) problems += "tapping Up next '$row' plays $playing"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    // ---- 2. full screen, rotate, swipe ----

    @Test
    fun swipingFullScreenThenRotatingKeepsTheVideoTheUserWasOn() {
        play("quick.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        showing("middle.mp4")

        // Tap: everything but the picture goes.
        tapStage()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Make a GIF").fetchSemanticsNodes().isEmpty() && title() == null }
        realSwipe(-600)
        // Tap to bring it back and see where the full-screen swipe went.
        tapStage()
        showing("feature.mp4")

        // Full screen again, and turn the phone.
        tapStage()
        compose.waitUntil(5_000) { title() == null }
        rotate(landscape = true)
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        tapStage()
        Thread.sleep(800)
        log("after rotation: ${describe()}")
        showing("feature.mp4")

        // And swipe back, in landscape.
        tapStage()
        compose.waitUntil(5_000) { title() == null }
        realSwipe(600)
        tapStage()
        showing("middle.mp4")
        rotate(landscape = false)
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        labelMatchesPlayer()
    }

    /** Control: tapping the picture hides the controls and tapping again brings them back. */
    @Test
    fun tapHidesAndTapShowsTheControls() {
        play("quick.mp4")
        tapStage()
        compose.waitUntil(5_000) { title() == null }
        tapStage()
        showing("quick.mp4")
    }

    /** Full screen, a swipe, then a tap: the tap must still bring the controls back. */
    @Test
    fun aTapAfterAFullScreenSwipeBringsTheControlsBack() {
        play("quick.mp4")
        tapStage()
        compose.waitUntil(5_000) { title() == null }
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        Thread.sleep(1_000)
        tapStage()
        log("tap-swipe-tap: ${describe()}")
        if (title() == null) { tapStage(); log("second tap: ${describe()}") }
        showing("middle.mp4")
    }

    // ---- 3. zoom, pan, double tap, swipe ----

    @Test
    fun zoomedInADragPansAndOnlyAfterDoubleTapDoesASwipeMoveOn() {
        play("middle.mp4")
        val st = compose.onNodeWithTag("Video stage")
        st.performTouchInput {
            pinch(center + Offset(-40f, 0f), center + Offset(-300f, 0f), center + Offset(40f, 0f), center + Offset(300f, 0f))
        }
        Thread.sleep(500)
        // Zoomed: a long sideways drag pans.
        st.performTouchInput { swipeLeft() }
        Thread.sleep(1_200)
        showing("middle.mp4")
        st.performTouchInput { swipeRight() }
        Thread.sleep(1_200)
        showing("middle.mp4")
        realSwipe(-600)
        Thread.sleep(800)
        showing("middle.mp4")

        // Double tap back to plain, then the same swipe moves on.
        st.performTouchInput { doubleClick() }
        Thread.sleep(800)
        st.performTouchInput { swipeLeft() }
        showing("feature.mp4")
    }

    /** Fingers pinched together on an unzoomed picture is a zoom out, not a swipe. */
    @Test
    fun aPinchInOnAPlainPictureIsNotASwipe() {
        play("middle.mp4")
        val st = compose.onNodeWithTag("Video stage")
        // Right finger travels far left, left finger a little right: fingers together.
        st.performTouchInput {
            pinch(center + Offset(-120f, 0f), center + Offset(-20f, 0f), center + Offset(420f, 0f), center + Offset(20f, 0f))
        }
        Thread.sleep(1_200)
        log("after pinch-in: ${describe()}")
        showing("middle.mp4")
        st.performTouchInput {
            pinch(center + Offset(420f, 0f), center + Offset(20f, 0f), center + Offset(-120f, 0f), center + Offset(-20f, 0f))
        }
        Thread.sleep(1_200)
        log("after pinch-in (other finger first): ${describe()}")
        showing("middle.mp4")
    }

    // ---- 4. a vertical scroll that starts on the picture ----

    @Test
    fun aVerticalScrollStartingOnThePictureScrollsThePage() {
        play("middle.mp4")
        rotate(landscape = true) // the page only overflows in landscape
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        showing("middle.mp4")
        val before = stage().positionOnScreen.y
        // Up the page, with a little sideways drift as a thumb has.
        realSwipe(dx = -90, dy = -500, ms = 400)
        Thread.sleep(600)
        val after = stage().positionOnScreen.y
        log("vertical scroll moved the stage from $before to $after; ${describe()}")
        assertTrue("the page should have scrolled (stage $before -> $after)", after < before - 50)
        showing("middle.mp4")
        realSwipe(dx = 90, dy = 500, ms = 400, from = stageCentreOnScreen())
        Thread.sleep(600)
        showing("middle.mp4")
    }

    // ---- 5. locking mid-swipe and mid-transition ----

    @Test
    fun theScreenGoingOffMidSwipeLocksAndComesBackHonest() {
        play("quick.mp4")
        val c = stageCentreOnScreen()
        TestScreen.shell("input motionevent DOWN ${c.x.toInt()} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() - 150} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() - 350} ${c.y.toInt()}")
        TestScreen.sleep()
        TestScreen.shell("input motionevent UP ${c.x.toInt() - 350} ${c.y.toInt()}")
        assertTrue("the vault must lock when the screen goes off", waitForLock())
        assertFalse("no hold may be left owing a lock", entry.autoLock().isLockDeferred)
        TestScreen.wake()
        assertLockScreenWithNothingBehindIt()

        unlockThroughTheScreen()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        log("after unlock (screen off): ${describe()}")
        labelMatchesPlayer()
    }

    @Test
    fun aPanicFlipJustAfterASwipeLocksAndNothingPlaysBehindIt() {
        play("quick.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        // Mid-transition: the seek has been asked for, the next clip not yet read.
        entry.panicLock().trigger()
        assertTrue("a panic flip must lock the vault", waitForLock())
        assertFalse(entry.autoLock().isLockDeferred)
        assertLockScreenWithNothingBehindIt()
        val codecs = TestScreen.shell("dumpsys media.resource_manager")
        log("codecs while locked:\n$codecs")
        Thread.sleep(2_000)
        assertLockScreenWithNothingBehindIt()

        unlockThroughTheScreen()
        Thread.sleep(1_500)
        log("after unlock (panic): ${describe()}")
        if (compose.onAllNodesWithTag("Video stage").fetchSemanticsNodes().isNotEmpty()) labelMatchesPlayer()
    }

    @Test
    fun aPanicFlipMidDragLocksAndTheDragGoesNowhere() {
        play("middle.mp4")
        val c = stageCentreOnScreen()
        TestScreen.shell("input motionevent DOWN ${c.x.toInt()} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() + 200} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() + 400} ${c.y.toInt()}")
        entry.panicLock().trigger()
        assertTrue(waitForLock())
        TestScreen.shell("input motionevent UP ${c.x.toInt() + 400} ${c.y.toInt()}")
        assertLockScreenWithNothingBehindIt()
        unlockThroughTheScreen()
        Thread.sleep(1_500)
        log("after unlock (panic mid-drag): ${describe()}")
        if (compose.onAllNodesWithTag("Video stage").fetchSemanticsNodes().isNotEmpty()) labelMatchesPlayer()
    }

    // ---- 1. GIF making ----

    @Test
    fun swipingWhileTheGifSheetIsOpenAndWhileTheGifIsMadeChangesNothing() {
        play("quick.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        showing("middle.mp4")
        val stageAt = stageCentreOnScreen()

        compose.onNodeWithContentDescription("Make a GIF").performClick()
        compose.waitUntilAtLeastOneExists(hasText("From \"middle.mp4\"", substring = true), 10_000)
        // A real swipe where the picture is, under the sheet's scrim. Lifting the sheet is fine
        // (it was not making anything yet); moving the video under it is not.
        realSwipe(-600, from = stageAt)
        Thread.sleep(800)
        val sheetStayed = compose.onAllNodesWithText("From \"middle.mp4\"", substring = true).fetchSemanticsNodes().isNotEmpty()
        log("swiped under the open sheet: sheetStayed=$sheetStayed ${describe()}")
        if (!sheetStayed) {
            showing("middle.mp4")
            compose.onNodeWithContentDescription("Make a GIF").performClick()
            compose.waitUntilAtLeastOneExists(hasText("From \"middle.mp4\"", substring = true), 10_000)
        }
        compose.onNodeWithText("Make the GIF").performClick()
        // While it runs.
        repeat(3) { realSwipe(-600, from = stageAt) }
        compose.waitUntilAtLeastOneExists(hasText("Saved as", substring = true), 60_000)
        compose.waitUntilAtLeastOneExists(hasText("middle.gif", substring = true), 5_000)
        compose.onNodeWithText("Done").performClick()
        Thread.sleep(800)
        showing("middle.mp4")
        assertFalse(entry.autoLock().isLockDeferred)
        // And swiping still works once the sheet is gone.
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        showing("feature.mp4")
    }

    @Test
    fun swipingThenLockingWhileTheGifIsMadeLeavesNoHoldAndNoHalfGif() {
        play("quick.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        showing("feature.mp4")
        compose.onNodeWithContentDescription("Make a GIF").performClick()
        compose.waitUntilAtLeastOneExists(hasText("From \"feature.mp4\"", substring = true), 10_000)
        compose.onNodeWithText("Make the GIF").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reading the frames", substring = true), 10_000)
        TestScreen.sleep()
        assertTrue("the screen going off must lock even mid-GIF", waitForLock(15_000))
        assertFalse(entry.autoLock().isLockDeferred)
        TestScreen.wake()
        assertLockScreenWithNothingBehindIt()
        unlockThroughTheScreen()
        Thread.sleep(1_500)
        val gifs = runBlocking { entry.session().openVault!!.database.files().observeAll().first().filter { it.kind == FileKind.GIF } }
        log("gifs after lock mid-GIF: ${gifs.map { it.name to it.sizeBytes }}")
        gifs.forEach { assertTrue("a sealed GIF must be whole", it.sizeBytes > 0) }
        if (compose.onAllNodesWithTag("Video stage").fetchSemanticsNodes().isNotEmpty()) labelMatchesPlayer()
    }

    // ---- 7. opened from search while the gallery shows photos ----

    @Test
    fun openedFromSearchWithTheGalleryOnPhotosSwipesThroughEveryVideo() {
        instrumentation.runOnMainSync { entry.gallerySettings().update { it.copy(filter = GalleryFilter.PHOTOS) } }
        launch()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 10_000)
        compose.onNode(hasSetTextAction()).performTextInput("mp4")
        compose.waitUntilAtLeastOneExists(hasContentDescription("video, quick.mp4"), 10_000)
        compose.onNodeWithContentDescription("video, quick.mp4").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        showing("quick.mp4")
        assertEquals(GalleryFilter.PHOTOS, entry.gallerySettings().settings.value.filter)

        // Every video, newest first, quick the oldest: everything is behind it.
        val seen = mutableListOf("quick.mp4")
        repeat(3) {
            compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
            Thread.sleep(1_200)
            seen += labelMatchesPlayer()
        }
        log("from search, swiping back: $seen")
        assertEquals(setOf("quick.mp4", "middle.mp4", "feature.mp4"), seen.toSet())
        repeat(3) {
            compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
            Thread.sleep(1_200)
        }
        showing("quick.mp4")
    }

    // ---- 8. the decoy ----

    @Test
    fun theDecoysPlayerNeverReachesARealVaultFile() {
        runBlocking {
            entry.session().removeDecoy()
            assertEquals(UnlockResult.Success, entry.session().createDecoy(decoy.toCharArray(), null))
        }
        decoyMade = true
        // Encoded first: the encoding takes long enough for auto-lock to close the vault.
        val a = VideoLengthFixture.encodeMp4(20, "fx-decoy-a.mp4")
        val b = VideoLengthFixture.encodeMp4(35, "fx-decoy-b.mp4")
        instrumentation.runOnMainSync { entry.autoLock().onStop(FakeOwner) }
        instrumentation.runOnMainSync { entry.session().lock() }
        assertEquals(UnlockResult.Success, runBlocking { entry.session().unlockWithPassphrase(decoy.toCharArray()) })
        assertTrue(entry.session().openVault!!.isDecoy)
        runBlocking {
            seconds["decoy-a.mp4"] = (VideoLengthFixture.seal(a, "decoy-a.mp4", 1_700_000_050_000) / 1000).toInt()
            seconds["decoy-b.mp4"] = (VideoLengthFixture.seal(b, "decoy-b.mp4", 1_700_000_300_000) / 1000).toInt()
        }
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, descending = false) }
        }
        val real = listOf("quick.mp4", "middle.mp4", "feature.mp4")

        play("decoy-a.mp4", items = 2)
        fun noReal() = real.forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        noReal()
        repeat(3) {
            compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
            Thread.sleep(1_000)
            noReal()
        }
        showing("decoy-b.mp4")
        repeat(3) {
            compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
            Thread.sleep(1_000)
            noReal()
        }
        showing("decoy-a.mp4")

        // And from the decoy's search, where every video is in reach.
        compose.onNodeWithContentDescription("Back").performClick()
        Thread.sleep(500)
        TestScreen.shell("input keyevent KEYCODE_BACK")
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 15_000)
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("mp4")
        compose.waitUntilAtLeastOneExists(hasContentDescription("video, decoy-b.mp4"), 10_000)
        noReal()
        compose.onNodeWithContentDescription("video, decoy-b.mp4").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        repeat(3) {
            compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
            Thread.sleep(1_000)
            noReal()
            compose.onAllNodesWithText("couldn't be played", substring = true).assertCountEquals(0)
        }
        repeat(3) {
            compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
            Thread.sleep(1_000)
            noReal()
        }
    }
}
