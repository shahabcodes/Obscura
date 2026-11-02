package com.shahaabapps.obscura

import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.navigation.LastPlace
import com.shahaabapps.obscura.vault.AlbumSummary
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultState
import kotlinx.coroutines.flow.first
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
 * The video player's heart and Album button when a real person interrupts them: the player
 * moving on by itself while the album sheet is open, a turn of the phone, the app going to the
 * background, the screen going off, a panic flip, swiping after a heart, and shredding a file
 * that was only just filed.
 *
 * Every journey ends with the same bookkeeping: as many files in the index, and as many sealed
 * blobs on disk, as there should be -- a heart or an album only ever marks the file where it is.
 *
 * The vault: a photo, then three clips oldest first -- "quick" (3s, so the player moves on from
 * it by itself), "middle" (30s) and "feature" (40s).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoAlbumInterruptionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry get() = VideoLengthFixture.entry
    private var scenario: ActivityScenario<MainActivity>? = null
    private var rotated = false
    private val names = listOf("quick.mp4", "middle.mp4", "feature.mp4")

    @Before
    fun setUp() {
        TestScreen.wake()
        TestScreen.shell("settings put secure immersive_mode_confirmations confirmed")
        LastPlace.forget()
        VideoLengthFixture.wipe()
        val clips = listOf(3 to "quick.mp4", 30 to "middle.mp4", 40 to "feature.mp4")
            .map { (secs, name) -> name to VideoLengthFixture.encodeMp4(secs, "fa-$name") }
        runBlocking {
            entry.session().create(VideoLengthFixture.PASSPHRASE.toCharArray())
            entry.session().openVault!!.files.put(
                VideoLengthFixture.jpeg().inputStream(),
                IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000),
            )
            clips.forEachIndexed { i, (name, file) -> VideoLengthFixture.seal(file, name, 1_700_000_100_000 + i * 50_000L) }
        }
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, descending = false) }
        }
    }

    @After
    fun tearDown() {
        if (rotated) {
            TestScreen.shell("settings put system user_rotation 0")
            TestScreen.shell("settings put system accelerometer_rotation 1")
        }
        entry.autoLock().delayMs = AutoLock.DEFAULT_DELAY_MS
        TestScreen.wake()
        scenario?.close()
        scenario = null
        LastPlace.forget()
        VideoLengthFixture.wipe()
        TestScreen.wake()
    }

    // ---- the vault's own bookkeeping ----

    private fun files(): List<VaultFile> = runBlocking { entry.session().openVault!!.database.files().observeAll().first() }
    private fun named(name: String) = files().single { it.name == name }
    private fun blobs() = entry.store().blobDir.walk().count { it.isFile }
    private fun albums(): List<AlbumSummary> = runBlocking { entry.session().openVault!!.database.albums().observeSummaries().first() }
    private fun favourites(): List<String> = runBlocking { entry.session().openVault!!.database.files().observeFavorites().first() }.map { it.name }

    private data class Tally(val files: Int, val blobs: Int)
    private fun tally() = Tally(files().size, blobs())

    /** No file twice in the index, and exactly one sealed blob per indexed file. */
    private fun assertNoDuplicates() {
        val all = files()
        assertEquals("a name appears twice: ${all.map { it.name }}", all.size, all.map { it.name }.toSet().size)
        assertEquals("a content hash appears twice", all.size, all.map { it.sha256 }.toSet().size)
        assertEquals("one sealed blob per file", all.size, blobs())
        all.forEach { assertTrue("blob missing for ${it.name}", entry.session().openVault!!.files.blobFile(it.id).isFile) }
    }

    // ---- getting there ----

    private object FakeOwner : androidx.lifecycle.LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
    }

    private fun play(name: String) {
        instrumentation.runOnMainSync { entry.autoLock().onStop(FakeOwner) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, $name"), 20_000)
        compose.onNodeWithContentDescription("Video, $name").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
        compose.waitUntil(10_000) { title() == name }
    }

    private fun texts(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** The heading: the top-most text that is a clip name, outside any sheet. */
    private fun title(): String? = texts().filter { it.text() in names }.minByOrNull { it.positionInRoot.y }?.text()

    private fun openAlbumSheet() {
        compose.onNodeWithContentDescription("Album").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Add to album"), 5_000)
    }

    private fun sheetOpen() = compose.onAllNodesWithText("Add to album").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun createAlbumInSheet(album: String) {
        compose.onNodeWithText("New album").performClick()
        compose.onNodeWithContentDescription("Album name").performTextInput(album)
        compose.onNodeWithText("Create & add").performClick()
        compose.waitUntil(5_000) { !sheetOpen() }
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

    /** The lock screen, and nothing of the player or its album sheet anywhere behind or above it. */
    private fun assertLockedWithNothingShowing(albumNames: List<String> = emptyList()) {
        compose.waitUntilAtLeastOneExists(hasText("passphrase", substring = true), 20_000)
        compose.onAllNodesWithTag("Video stage").assertCountEquals(0)
        compose.onAllNodesWithText("Add to album").assertCountEquals(0)
        compose.onAllNodesWithText("New album").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Album").assertCountEquals(0)
        (names + albumNames).forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
    }

    private fun unlockThroughTheScreen() {
        if (entry.panicLock().panicked.value) {
            Thread.sleep(1_500)
            if (compose.onAllNodesWithText("Locked instantly.", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Locked instantly.", substring = true).performClick()
            }
            compose.waitUntil(10_000) { !entry.panicLock().panicked.value }
        }
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 20_000)
        compose.onNode(hasSetTextAction()).performTextInput(VideoLengthFixture.PASSPHRASE)
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(20_000) { entry.session().state.value is VaultState.Unlocked }
    }

    private fun log(msg: String) = Log.i("AlbumE2E", msg)

    // ---- the journeys ----

    /**
     * The 3-second clip ends while its album sheet is open and the player moves on to the next.
     * The album must go to the clip the sheet was opened for, not the one now playing.
     */
    @Test
    fun theAlbumGoesToTheClipTheSheetWasOpenedForAfterThePlayerMovesOn() {
        play("quick.mp4")
        val before = tally()
        openAlbumSheet()
        // Let quick end and middle start underneath the sheet.
        compose.waitUntil(15_000) { title() == "middle.mp4" }
        log("player moved on under the sheet; sheet open=${sheetOpen()}")
        assertTrue("the sheet must stay open while the player moves on", sheetOpen())
        createAlbumInSheet("Pinned")

        compose.waitUntil(5_000) { named("quick.mp4").albumId != null }
        assertNull("the clip playing now must not be filed", named("middle.mp4").albumId)
        assertEquals("Pinned", albums().single { it.id == named("quick.mp4").albumId }.name)
        assertEquals(1, albums().single().count)
        assertEquals(before, tally())
        assertNoDuplicates()
    }

    /** A turn of the phone with the sheet open keeps it, for the same clip, and filing still works once. */
    @Test
    fun turningThePhoneWithTheSheetOpenKeepsItForTheSameClip() {
        play("middle.mp4")
        val before = tally()
        openAlbumSheet()
        rotate(landscape = true)
        compose.waitUntil(8_000) { sheetOpen() }
        rotate(landscape = false)
        compose.waitUntil(8_000) { sheetOpen() }
        createAlbumInSheet("Turned")
        compose.waitUntil(5_000) { named("middle.mp4").albumId != null }
        assertEquals(listOf("Turned"), albums().map { it.name })
        assertNull(named("quick.mp4").albumId)
        assertNull(named("feature.mp4").albumId)
        assertEquals(before, tally())
        assertNoDuplicates()
        // Gone for good once used: a further turn does not bring it back.
        rotate(landscape = true)
        assertFalse("the sheet came back after it was used", sheetOpen())
    }

    /** A short trip to another app with the sheet open, under the auto-lock delay: back to the sheet. */
    @Test
    fun aShortTripAwayWithTheSheetOpenComesBackToIt() {
        entry.autoLock().delayMs = 120_000
        play("middle.mp4")
        val before = tally()
        openAlbumSheet()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(2_000)
        assertTrue(entry.session().state.value is VaultState.Unlocked)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(8_000) { sheetOpen() }
        createAlbumInSheet("Away")
        compose.waitUntil(5_000) { named("middle.mp4").albumId != null }
        assertEquals(before, tally())
        assertNoDuplicates()
    }

    /** Away past the auto-lock delay with the sheet open: locked, and nothing of the sheet left. */
    @Test
    fun goingAwayPastTheDelayWithTheSheetOpenLocksAndTakesTheSheet() {
        play("middle.mp4")
        // An album already there, so its name is on the sheet and can be looked for afterwards.
        runBlocking {
            entry.session().openVault!!.database.albums()
                .insert(com.shahaabapps.obscura.vault.Album("ab12cd34", "Secret trip", 0xFF3B5BFF, 1L))
        }
        val before = tally()
        openAlbumSheet()
        compose.waitUntilAtLeastOneExists(hasText("Secret trip"), 5_000)
        entry.autoLock().delayMs = 0
        scenario!!.moveToState(Lifecycle.State.CREATED)
        assertTrue("going away with no delay must lock", waitForLock())
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertLockedWithNothingShowing(listOf("Secret trip"))
        entry.autoLock().delayMs = AutoLock.DEFAULT_DELAY_MS
        unlockThroughTheScreen()
        Thread.sleep(2_000)
        compose.waitForIdle()
        log("after unlock (away): sheet=${sheetOpen()} title=${title()}")
        assertFalse("the sheet must not come back after a lock", sheetOpen())
        assertEquals(before, tally())
        assertNull(named("middle.mp4").albumId)
        assertEquals(0, albums().single().count)
        assertNoDuplicates()
    }

    /** The screen going off with the sheet open locks at once and takes the sheet with it. */
    @Test
    fun theScreenGoingOffWithTheSheetOpenLocks() {
        play("middle.mp4")
        val before = tally()
        openAlbumSheet()
        TestScreen.sleep()
        assertTrue("the screen going off must lock", waitForLock())
        assertFalse(entry.autoLock().isLockDeferred)
        TestScreen.wake()
        assertLockedWithNothingShowing()
        unlockThroughTheScreen()
        Thread.sleep(2_000)
        assertFalse(sheetOpen())
        assertEquals(before, tally())
        assertNoDuplicates()
    }

    /** A panic flip half way through naming a new album: locked, nothing created, nothing filed. */
    @Test
    fun aPanicFlipWhileNamingANewAlbumLocksAndCreatesNothing() {
        play("middle.mp4")
        val before = tally()
        openAlbumSheet()
        compose.onNodeWithText("New album").performClick()
        compose.onNodeWithContentDescription("Album name").performTextInput("Half typed")
        entry.panicLock().trigger()
        assertTrue("a panic flip must lock", waitForLock())
        assertLockedWithNothingShowing(listOf("Half typed"))
        unlockThroughTheScreen()
        Thread.sleep(2_000)
        assertFalse(sheetOpen())
        assertTrue("nothing may have been created: ${albums()}", albums().isEmpty())
        assertEquals(before, tally())
        assertNoDuplicates()
    }

    /**
     * Heart one clip, swipe to the next: the heart there is that clip's own (empty), hearting it
     * marks it and not the first, and swiping back shows the first still hearted. The Favourites
     * album holds exactly the two.
     */
    @Test
    fun heartingThenSwipingMarksEachClipItselfAndFavouritesFollow() {
        play("middle.mp4")
        val before = tally()
        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntil(5_000) { named("middle.mp4").favorite }

        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { title() == "feature.mp4" }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Add to favourites"), 5_000)
        compose.onAllNodesWithContentDescription("Remove from favourites").assertCountEquals(0)
        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntil(5_000) { named("feature.mp4").favorite }
        assertTrue("hearting feature must not touch middle", named("middle.mp4").favorite)

        compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { title() == "middle.mp4" }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Remove from favourites"), 5_000)

        // Unheart middle: Favourites drops it and keeps feature.
        compose.onNodeWithContentDescription("Remove from favourites").performClick()
        compose.waitUntil(5_000) { !named("middle.mp4").favorite }
        assertEquals(listOf("feature.mp4"), favourites())
        assertFalse(named("quick.mp4").favorite)

        // The Favourites album on the Albums screen says the same.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Videos"), 10_000)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 10_000)
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Album Favourites, 1 items"), 10_000)
        compose.onNodeWithContentDescription("Album Favourites, 1 items").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("feature.mp4", substring = true), 10_000)
        compose.onAllNodesWithContentDescription("middle.mp4", substring = true).assertCountEquals(0)
        assertEquals(before, tally())
        assertNoDuplicates()
    }

    /**
     * File a clip in a new album, heart it, then shred it from the player. The album is left
     * empty (not broken), Favourites forgets it, and exactly one blob goes.
     */
    @Test
    fun shreddingAClipJustFiledLeavesTheAlbumEmptyAndConsistent() {
        play("middle.mp4")
        val before = tally()
        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntil(5_000) { named("middle.mp4").favorite }
        openAlbumSheet()
        createAlbumInSheet("Doomed")
        compose.waitUntil(5_000) { named("middle.mp4").albumId != null }
        assertEquals(1, albums().single().count)

        compose.onNodeWithContentDescription("Shred").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Shred this file?"), 5_000)
        compose.onNode(hasText("Shred") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { files().none { it.name == "middle.mp4" } }
        Thread.sleep(1_000)
        compose.waitForIdle()
        log("after shred: title=${title()} sheet=${sheetOpen()}")

        assertEquals(Tally(before.files - 1, before.blobs - 1), tally())
        val doomed = albums().single()
        assertEquals("Doomed", doomed.name)
        assertEquals(0, doomed.count)
        assertNull("the cover must not point at a shredded file", doomed.coverId)
        assertTrue(favourites().isEmpty())
        assertNoDuplicates()

        // The Album button now belongs to whatever is playing, which is in no album.
        compose.waitUntil(10_000) { title() in listOf("quick.mp4", "feature.mp4") }
        openAlbumSheet()
        compose.onNodeWithText("Doomed").performClick()
        compose.waitUntil(5_000) { !sheetOpen() }
        val filed = files().filter { it.albumId == doomed.id }.map { it.name }
        log("filed into Doomed after the shred: $filed (title was ${title()})")
        assertEquals(1, filed.size)
        assertEquals(Tally(before.files - 1, before.blobs - 1), tally())
        assertNoDuplicates()
    }

    private fun centreOnScreen(node: SemanticsNode) =
        node.positionOnScreen.let { (it.x + node.size.width / 2f).toInt() to (it.y + node.size.height / 2f).toInt() }

    /**
     * A real swipe to the next clip and a tap on [button] as the finger lifts, in one shell so
     * nothing waits for the page to settle in between -- a person tapping while the next clip
     * is still sliding in.
     */
    private fun swipeThenTapAtOnce(button: String) {
        val stage = compose.onNodeWithTag("Video stage").fetchSemanticsNode()
        val (cx, cy) = centreOnScreen(stage)
        val half = (stage.size.width * 0.4f).toInt()
        val (bx, by) = centreOnScreen(compose.onNodeWithContentDescription(button).fetchSemanticsNode())
        // The shell here runs one command, no `;`: the tap goes the moment the swipe's command
        // returns, which is as the finger lifts, and lands while the page is still settling.
        TestScreen.shell("input swipe ${cx + half} $cy ${cx - half} $cy 150")
        TestScreen.shell("input tap $bx $by")
        Thread.sleep(1_500)
        compose.waitForIdle()
    }

    /** The heart tapped while the next clip slides in belongs to the clip sliding in. */
    @Test
    fun aHeartTappedAsTheNextClipSlidesInMarksThatClip() {
        play("middle.mp4")
        swipeThenTapAtOnce("Add to favourites")
        compose.waitUntil(10_000) { title() == "feature.mp4" }
        Thread.sleep(1_000)
        log("heart mid-slide: middle=${named("middle.mp4").favorite} feature=${named("feature.mp4").favorite}")
        assertFalse("the clip slid away from was hearted instead", named("middle.mp4").favorite)
        assertTrue("the clip on screen was not hearted", named("feature.mp4").favorite)
    }

    /** Album tapped while the next clip slides in files the clip sliding in, not the one leaving. */
    @Test
    fun albumTappedAsTheNextClipSlidesInFilesThatClip() {
        play("middle.mp4")
        swipeThenTapAtOnce("Album")
        compose.waitUntil(5_000) { sheetOpen() }
        createAlbumInSheet("Race")
        compose.waitUntil(5_000) { files().any { it.albumId != null } }
        val filed = files().filter { it.albumId != null }.map { it.name }
        log("album mid-slide filed: $filed, title now ${title()}")
        assertEquals("the clip slid away from was filed instead", listOf("feature.mp4"), filed)
    }

    /** Tapping an existing album then the same again ("Remove") files and unfiles without copies. */
    @Test
    fun filingIntoAnExistingAlbumAndBackOutMakesNoCopies() {
        runBlocking {
            entry.session().openVault!!.database.albums()
                .insert(com.shahaabapps.obscura.vault.Album("ab12cd34", "Clips", 0xFF3B5BFF, 1L))
        }
        play("middle.mp4")
        val before = tally()
        openAlbumSheet()
        compose.waitUntilAtLeastOneExists(hasText("Clips"), 5_000)
        compose.onNodeWithText("Clips").performClick()
        compose.waitUntil(5_000) { named("middle.mp4").albumId == "ab12cd34" }
        openAlbumSheet()
        compose.waitUntilAtLeastOneExists(hasText("Remove"), 5_000)
        compose.onNodeWithText("Clips").performClick()
        compose.waitUntil(5_000) { named("middle.mp4").albumId == null }
        assertEquals(before, tally())
        assertNoDuplicates()
    }
}
