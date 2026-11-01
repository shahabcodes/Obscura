package com.shahaabapps.obscura

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.navigation.LastPlace
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.IncomingFile
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
 * 0.16.0 E2E journeys for the paged video player and the Favourites album: the things a hand
 * does that the existing swipe tests do not, with one extra question asked every time -- is the
 * page the user is looking at the video the player is playing? The playing page is the only one
 * carrying the "Video stage" tag; the pages either side are stills. A screen whose heading and
 * player agree but whose visible page is a still is showing one video's picture while playing
 * another's sound.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoPagerJourneyTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry get() = VideoLengthFixture.entry
    private var scenario: ActivityScenario<MainActivity>? = null
    private var rotated = false
    private var decoyMade = false
    private val decoy = "harbour lantern thicket pebble"
    private val seconds = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        TestScreen.wake()
        TestScreen.shell("settings put secure immersive_mode_confirmations confirmed")
        LastPlace.forget()
        VideoLengthFixture.wipe()
    }

    @After
    fun tearDown() {
        if (rotated) {
            TestScreen.shell("settings put system user_rotation 0")
            TestScreen.shell("settings put system accelerometer_rotation 1")
        }
        compose.mainClock.autoAdvance = true
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

    /** Clips by name and length in seconds, oldest first, plus one photo; encoded before the vault exists. */
    private fun seed(vararg clips: Pair<String, Int>, favourite: Set<String> = emptySet()) {
        val encoded = clips.associate { (name, secs) -> name to VideoLengthFixture.encodeMp4(secs, "pj-$name") }
        runBlocking {
            entry.session().create(VideoLengthFixture.PASSPHRASE.toCharArray())
            entry.session().openVault!!.files.put(
                VideoLengthFixture.jpeg().inputStream(),
                IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000),
            )
            clips.forEachIndexed { i, (name, _) ->
                seconds[name] = (VideoLengthFixture.seal(encoded.getValue(name), name, 1_700_000_100_000 + i * 50_000L) / 1000).toInt()
            }
            val dao = entry.session().openVault!!.database.files()
            dao.observeAll().first().filter { it.name in favourite }.forEach { dao.setFavorite(it.id, true) }
        }
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, descending = false) }
        }
    }

    private object FakeOwner : androidx.lifecycle.LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
    }

    private fun launch() {
        instrumentation.runOnMainSync { entry.autoLock().onStop(FakeOwner) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun play(name: String, items: Int) {
        launch()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("$items ITEMS", substring = true), 20_000)
        compose.onNodeWithText("Videos").performClick()
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

    private fun title(): String? = texts().filter { it.text().endsWith(".mp4") }.minByOrNull { it.positionInRoot.y }?.text()

    private fun playerSeconds(): Int? = texts()
        .filter { it.text().matches(Regex("\\d+:\\d\\d")) }
        .maxByOrNull { it.positionInRoot.x }
        ?.text()?.split(':')?.let { (m, s) -> m.toInt() * 60 + s.toInt() }

    /**
     * Where the playing page is, if anywhere: null when no page carries the player (the visible
     * page is a still), else the stage's left and right edges in the window.
     */
    private fun stageSpan(): Pair<Float, Float>? = compose.onAllNodesWithTag("Video stage")
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .firstOrNull()?.boundsInRoot?.let { it.left to it.right }

    private fun rootWidth(): Float = compose.onRoot().fetchSemanticsNode().size.width.toFloat()

    private fun describe() = "title=${title()} playerLength=${playerSeconds()}s stage=${stageSpan()} rootWidth=${rootWidth()}"

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

    /** The page on screen is the one the player is drawing into, sitting squarely in view. */
    private fun assertPlayingPageIsTheVisibleOne(what: String, timeoutMs: Long = 6_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            val span = stageSpan()
            val w = rootWidth()
            if (span != null && span.first >= -2f && span.second <= w + 2f) return
            Thread.sleep(250)
        }
        throw AssertionError("$what: the page in view is not the one playing -- ${describe()}")
    }

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

    private fun stageCentreOnScreen(): Offset {
        val n = compose.onNodeWithTag("Video stage").fetchSemanticsNode()
        return n.positionOnScreen + Offset(n.size.width / 2f, n.size.height / 2f)
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

    private fun unlockThroughTheScreen(passphrase: String = VideoLengthFixture.PASSPHRASE) {
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 20_000)
        compose.onNode(hasSetTextAction()).performTextInput(passphrase)
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(20_000) { entry.session().state.value is VaultState.Unlocked }
    }

    private fun log(msg: String) = Log.i("PagerE2E", msg)

    // ---- the player moving on by itself while a finger is on the picture ----

    /**
     * A short clip ends while the user's finger is resting mid-drag on it. The player moves on
     * to the next clip by itself; the finger then lets go short of half way, so the pages spring
     * back. The page left in view must be the one playing, not the still of the clip that ended.
     */
    @Test
    fun aClipEndingUnderAHeldFingerThatLetsGoShortStillShowsWhatPlays() {
        seed("quick.mp4" to 4, "middle.mp4" to 40, "feature.mp4" to 50)
        play("quick.mp4", items = 4)
        val st = compose.onNodeWithTag("Video stage")
        st.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.08f, 0f))
            moveBy(Offset(-width * 0.08f, 0f))
        }
        // Hold until the clip has run out and the player has moved on beneath the finger.
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end && title() != "middle.mp4") {
            compose.waitForIdle()
            Thread.sleep(250)
        }
        log("finger held, clip ended: ${describe()}")
        assertEquals("the player should have moved on to the next clip", "middle.mp4", title())
        st.performTouchInput { up() }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        log("after letting go: ${describe()}")
        labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("clip ended under a held finger, which then let go short")
    }

    /**
     * Up next tapped while the page is still springing back from a short drag: the player moves,
     * the pages are mid-animation. When the animation finishes the page in view must be the one
     * playing. The rule's clock is held so the tap really lands mid-settle.
     */
    @Test
    fun upNextTappedWhileThePageSpringsBackShowsWhatPlays() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, "feature.mp4" to 50)
        play("quick.mp4", items = 4)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("Video stage").performTouchInput {
            // Slowly, so it is let go with no speed and springs back rather than flinging on.
            down(center)
            moveBy(Offset(-width * 0.1f, 0f), delayMillis = 300)
            moveBy(Offset(-width * 0.1f, 0f), delayMillis = 300)
            moveBy(Offset(0f, 0f), delayMillis = 300)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("feature.mp4", useUnmergedTree = true).performTouchInput { click() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()
        log("after Up next mid-settle: ${describe()}")
        assertEquals("the drag sprang back, so the Up next tap should stand", "feature.mp4", labelMatchesPlayer())
        assertPlayingPageIsTheVisibleOne("Up next tapped while the page sprang back")
    }

    // ---- rotation mid-drag ----

    private fun rotateMidDrag(start: String) {
        play(start, items = 4)
        val c = stageCentreOnScreen()
        // Past half way, held: the pages' "current page" is already the next one.
        TestScreen.shell("input motionevent DOWN ${c.x.toInt()} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() - 200} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() - 500} ${c.y.toInt()}")
        TestScreen.shell("input motionevent MOVE ${c.x.toInt() - 800} ${c.y.toInt()}")
        rotate(landscape = true)
        TestScreen.shell("input motionevent UP ${c.x.toInt() - 800} ${c.y.toInt()}")
        compose.waitUntilAtLeastOneExists(hasText(".mp4", substring = true), 15_000)
        Thread.sleep(1_500)
        compose.waitForIdle()
        log("rotated mid-drag from $start: ${describe()}")
        labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("turned the phone mid-drag from $start")
    }

    @Test
    fun turningThePhoneMidDragOffTheFirstVideoKeepsPageAndPlayerTogether() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, "feature.mp4" to 50)
        rotateMidDrag("quick.mp4")
    }

    @Test
    fun turningThePhoneMidDragOffTheMiddleVideoKeepsPageAndPlayerTogether() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, "feature.mp4" to 50)
        rotateMidDrag("middle.mp4")
    }

    // ---- shredding the last video ----

    @Test
    fun shreddingTheLastVideoThenSwipingBackStaysHonest() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, "feature.mp4" to 50)
        play("feature.mp4", items = 4)
        compose.onNodeWithContentDescription("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred this file?"), 5_000)
        compose.onNode(hasText("Shred") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) {
            runBlocking { entry.session().openVault!!.database.files().observeAll().first().none { it.name == "feature.mp4" } }
        }
        Thread.sleep(1_500)
        compose.waitForIdle()
        log("after shredding the last: ${describe()}")
        compose.onAllNodesWithText("feature.mp4", substring = true).assertCountEquals(0)
        labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("after shredding the last video")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
        Thread.sleep(1_200)
        compose.waitForIdle()
        log("after swiping back: ${describe()}")
        labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("swiping back after shredding the last video")
    }

    // ---- full screen, swipe, screen off before the page has settled ----

    @Test
    fun fullScreenSwipeThenScreenOffBeforeTheSettleLocksAndComesBackWhole() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, "feature.mp4" to 50)
        play("quick.mp4", items = 4)
        compose.onNodeWithTag("Video stage").performTouchInput { click(Offset(width * 0.2f, height * 0.2f)) }
        compose.mainClock.advanceTimeBy(600)
        Thread.sleep(400)
        compose.waitForIdle()
        compose.waitUntil(5_000) { title() == null }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeByFrame()
        TestScreen.sleep()
        compose.mainClock.autoAdvance = true
        assertTrue("the vault must lock when the screen goes off", waitForLock())
        assertFalse(entry.autoLock().isLockDeferred)
        TestScreen.wake()
        compose.waitUntilAtLeastOneExists(hasText("passphrase", substring = true), 20_000)
        compose.onAllNodesWithTag("Video stage").assertCountEquals(0)
        unlockThroughTheScreen()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        Thread.sleep(1_000)
        // Full screen comes back without the heading: tap for it.
        if (title() == null) {
            compose.onNodeWithTag("Video stage").performTouchInput { click(Offset(width * 0.2f, height * 0.2f)) }
            compose.mainClock.advanceTimeBy(600)
            Thread.sleep(400)
            compose.waitForIdle()
        }
        log("after unlock: ${describe()}")
        labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("full-screen swipe interrupted by screen off")
    }

    // ---- Favourites ----

    private fun openFavourites(count: Int) {
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Album Favourites, $count items"), 10_000)
        compose.onNodeWithContentDescription("Album Favourites, $count items").performClick()
        compose.waitUntilAtLeastOneExists(hasText("$count ITEMS"), 10_000)
    }

    /** From the Favourites album into a favourite video, swipe, unheart from the gallery, come back. */
    @Test
    fun aVideoOpenedFromFavouritesSwipesHonestlyAndTheAlbumFollowsTheHeart() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, "feature.mp4" to 50, favourite = setOf("middle.mp4", "beach.jpg"))
        launch()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        openFavourites(2)
        compose.waitUntilAtLeastOneExists(hasContentDescription("middle.mp4", substring = true), 10_000)
        compose.onAllNodesWithContentDescription("quick.mp4", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("middle.mp4", substring = true).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        showing("middle.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        Thread.sleep(1_200)
        val a = labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("swipe from a favourite")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
        Thread.sleep(1_200)
        val b = labelMatchesPlayer()
        assertPlayingPageIsTheVisibleOne("swipe back from a favourite")
        log("favourites swipe: $a then $b")

        // Unheart middle under the open album (from the database, as the gallery's heart does).
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("2 ITEMS"), 10_000)
        runBlocking {
            val dao = entry.session().openVault!!.database.files()
            dao.observeAll().first().first { it.name == "middle.mp4" }.let { dao.setFavorite(it.id, false) }
        }
        compose.waitUntilAtLeastOneExists(hasText("1 ITEMS"), 10_000)
        compose.onAllNodesWithContentDescription("middle.mp4", substring = true).assertCountEquals(0)
        runBlocking {
            val dao = entry.session().openVault!!.database.files()
            dao.observeAll().first().filter { it.kind != FileKind.PHOTO }.forEach { dao.setFavorite(it.id, true) }
        }
        compose.waitUntilAtLeastOneExists(hasText("4 ITEMS"), 10_000)
        compose.onAllNodesWithContentDescription("Edit album").assertCountEquals(0)
    }

    /** Locked inside Favourites, unlocked: back in Favourites. The decoy never sees the real hearts. */
    @Test
    fun favouritesComeBackAfterALockAndTheDecoyHasNoneOfTheRealOnes() {
        seed("quick.mp4" to 30, "middle.mp4" to 40, favourite = setOf("quick.mp4", "middle.mp4", "beach.jpg"))
        launch()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        openFavourites(3)
        TestScreen.sleep()
        assertTrue(waitForLock())
        TestScreen.wake()
        unlockThroughTheScreen()
        compose.waitUntilAtLeastOneExists(hasText("3 ITEMS"), 15_000)
        compose.onAllNodesWithText("Favourites").assertCountEquals(1)

        // The decoy: its own Favourites, empty, and no route back into the real ones.
        runBlocking {
            entry.session().removeDecoy()
            assertEquals(UnlockResult.Success, entry.session().createDecoy(decoy.toCharArray(), null))
        }
        decoyMade = true
        instrumentation.runOnMainSync { entry.session().lock() }
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 20_000)
        compose.onAllNodesWithText("3 ITEMS").assertCountEquals(0)
        unlockThroughTheScreen(decoy)
        assertTrue(entry.session().openVault!!.isDecoy)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 20_000)
        compose.onAllNodesWithText("3 ITEMS").assertCountEquals(0)
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Album Favourites, 0 items"), 10_000)
        compose.onNodeWithContentDescription("Album Favourites, 0 items").performClick()
        compose.waitUntilAtLeastOneExists(hasText("No favourites yet."), 10_000)
        listOf("quick.mp4", "middle.mp4", "beach.jpg").forEach {
            compose.onAllNodesWithContentDescription(it, substring = true).assertCountEquals(0)
            compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        // Lock in the decoy's Favourites and go back to the real vault: no jump into the decoy's place.
        instrumentation.runOnMainSync { entry.session().lock() }
        unlockThroughTheScreen()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 20_000)
        compose.onAllNodesWithText("No favourites yet.").assertCountEquals(0)
    }
}
