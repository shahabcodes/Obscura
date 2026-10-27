package com.shahaabapps.obscura

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Filtering videos by how long they are, with the length and the side both chosen on screen.
 *
 * The clips come from [VideoLengthFixture]: really encoded and really imported, so the lengths
 * under test are the ones the vault read off the files for itself, not numbers a test put in.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoLengthFilterTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        VideoLengthFixture.wipe()
        runBlocking {
            val (quick, feature) = VideoLengthFixture.create()
            // The whole test rests on these two sitting either side of five seconds. If the
            // encoder on this device produced something else, say so here rather than let the
            // filter appear to work because nothing was ever excluded.
            assertTrue("short clip is ${quick}ms", quick in 1_000..4_000)
            assertTrue("long clip is ${feature}ms", feature in 6_000..20_000)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        VideoLengthFixture.wipe()
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
        compose.waitUntilAtLeastOneExists(hasText("Video length"), 10_000)
    }

    /** Waits for the sheet to go, so what is asserted next is the grid and not the sheet. */
    private fun sheetGone() {
        compose.waitUntilDoesNotExist(
            hasText("Show only the videos on one side of a length.", substring = true),
            10_000,
        )
        // The sheet's window is still on its way out for a moment after its content goes.
        Thread.sleep(400)
    }

    /** Waits for the pill to show [label], which is the limit the gallery is working to. */
    private fun limitShows(label: String) =
        compose.waitUntilAtLeastOneExists(hasTestTag("Video length") and hasText(label), 10_000)

    private fun shownVideos() = listOf("quick.mp4", "feature.mp4")
        .filter { compose.onAllNodesWithContentDescription("Video, $it").fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aLengthTheUserTypesKeepsOnlyTheVideosUnderIt() {
        openVideos()
        // Both clips are there, and every length is in until the user narrows it.
        assertEquals(listOf("quick.mp4", "feature.mp4"), shownVideos())

        openTheLengthSheet()
        compose.onNodeWithTag("Seconds").performTextInput("5")
        compose.waitUntilAtLeastOneExists(hasText("Under 5s"), 5_000)
        compose.onNodeWithTag("Seconds").performImeAction()
        sheetGone()
        limitShows("Under 5s")

        // The two-second clip stays; the ten-second one does not.
        compose.waitUntilDoesNotExist(hasContentDescription("Video, feature.mp4"), 10_000)
        assertEquals(listOf("quick.mp4"), shownVideos())
    }

    @Test
    fun theSideIsTheUsersToo() {
        openVideos()
        openTheLengthSheet()
        compose.onNodeWithTag("Over").performClick()
        compose.onNodeWithTag("Seconds").performTextInput("5")
        compose.waitUntilAtLeastOneExists(hasText("Over 5s"), 5_000)
        compose.onNodeWithTag("Seconds").performImeAction()
        sheetGone()
        limitShows("Over 5s")

        compose.waitUntilDoesNotExist(hasContentDescription("Video, quick.mp4"), 10_000)
        assertEquals(listOf("feature.mp4"), shownVideos())
    }

    /** A preset is one tap, and the limit lifts again without having to remember a number. */
    @Test
    fun aPresetAppliesAndTheLimitCanBeLifted() {
        openVideos()
        openTheLengthSheet()
        compose.onNodeWithTag("Length 30s").performClick()
        sheetGone()
        limitShows("Under 30s")

        // Under thirty seconds: both clips are well under it, so nothing is hidden.
        assertEquals(listOf("quick.mp4", "feature.mp4"), shownVideos())

        openTheLengthSheet()
        compose.onNodeWithText("Any length").performClick()
        sheetGone()
        limitShows("Any length")
        assertEquals(listOf("quick.mp4", "feature.mp4"), shownVideos())
    }

    /** The limit is a video idea, so it is not offered where there is nothing to apply it to. */
    @Test
    fun theLengthIsOfferedOnlyOnTheVideosFilter() {
        openVideos()
        compose.onNodeWithTag("Video length").assertIsDisplayed()
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("JPG, beach.jpg"), 10_000)
        compose.onAllNodesWithTag("Video length").assertCountEquals(0)
    }
}
