package com.shahaabapps.obscura

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shahaabapps.obscura.ui.gallery.DurationLimit
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swiping sideways on a video moves to the next one or back to the last, as it does on a photo,
 * and "next" means the next one on the gallery screen the video was opened from.
 *
 * The vault holds a two-second clip and, taken later, a ten-second one. The library keeps
 * videos newest first; the gallery here is set to show them oldest first, so the two orders
 * disagree and only a player that follows the gallery passes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoSwipeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        VideoLengthFixture.wipe()
        runBlocking { VideoLengthFixture.create() }
        VideoLengthFixture.entry.gallerySettings().update {
            it.copy(filter = GalleryFilter.VIDEOS, descending = false)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        VideoLengthFixture.wipe()
    }

    /** Opens [name] from the Videos filter, as a hand would: gallery, then the tile. */
    private fun play(name: String) {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("3 ITEMS", substring = true), 20_000)
        // The Photos tile opens the whole gallery; the videos are what is under test.
        compose.onNodeWithText("Videos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, $name"), 15_000)
        compose.onNodeWithContentDescription("Video, $name").performClick()
        compose.waitUntilAtLeastOneExists(hasText(name), 15_000)
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)
    }

    private fun showing(name: String) = compose.waitUntilAtLeastOneExists(hasText(name), 10_000)

    @Test
    fun swipingMovesToTheNextVideoOnTheGalleryAndBack() {
        play("quick.mp4")

        // Oldest first: after the two-second clip comes the ten-second one. In the library's
        // own newest-first order there is nothing after it at all.
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        showing("feature.mp4")

        compose.onNodeWithTag("Video stage").performTouchInput { swipeRight() }
        showing("quick.mp4")
    }

    @Test
    fun aSwipePastTheEndStaysPut() {
        play("feature.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        // Nothing comes after the last video on the screen, so the swipe leaves it there
        // rather than wrapping round to the first.
        Thread.sleep(1_500)
        showing("feature.mp4")
        compose.onAllNodesWithText("quick.mp4").assertCountEquals(0)
    }

    @Test
    fun aVideoTheLengthHidesIsNotSwipedTo() {
        // Only the two-second clip is on the screen under this length.
        VideoLengthFixture.entry.gallerySettings().update { it.copy(duration = DurationLimit(5, longer = false)) }
        play("quick.mp4")
        compose.onNodeWithTag("Video stage").performTouchInput { swipeLeft() }
        Thread.sleep(1_500)
        showing("quick.mp4")
        compose.onAllNodesWithText("feature.mp4").assertCountEquals(0)
    }
}
