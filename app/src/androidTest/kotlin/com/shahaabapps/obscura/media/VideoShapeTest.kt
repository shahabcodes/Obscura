package com.shahaabapps.obscura.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.VideoSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The shape a video is drawn at. This is the rule behind the squeezing the player and the reel
 * used to show: whatever space they are given, the picture keeps the video's own proportions.
 */
@RunWith(AndroidJUnit4::class)
class VideoShapeTest {

    @get:Rule
    val compose = createComposeRule()

    /** Measures the picture inside a box of the given shape. */
    private fun drawn(aspect: Float?, boxWidth: Int, boxHeight: Int): Pair<Float, Float> {
        compose.setContent {
            Box(Modifier.size(boxWidth.dp, boxHeight.dp)) {
                FitVideo(aspect, Modifier.size(boxWidth.dp, boxHeight.dp)) { fitted ->
                    Box(fitted.testTag("picture"))
                }
            }
        }
        val bounds = compose.onNodeWithTag("picture").assertHeightIsAtLeast(1.dp).fetchSemanticsNode()
        return bounds.size.width.toFloat() to bounds.size.height.toFloat()
    }

    private fun assertShape(expected: Float, measured: Pair<Float, Float>) {
        val (width, height) = measured
        val got = width / height
        assertTrue("drawn ${width}x$height is $got, not $expected", abs(got - expected) < 0.04f)
    }

    /** A wide video in a tall space is letterboxed, not stretched down to fill it. */
    @Test
    fun aWideVideoKeepsItsShapeInATallSpace() {
        assertShape(16f / 9f, drawn(16f / 9f, boxWidth = 400, boxHeight = 800))
    }

    /** And a tall video in a wide space, which is the landscape player. */
    @Test
    fun aTallVideoKeepsItsShapeInAWideSpace() {
        assertShape(9f / 16f, drawn(9f / 16f, boxWidth = 800, boxHeight = 400))
    }

    /** A 4:3 video is neither squeezed to a square nor pulled out to the screen's shape. */
    @Test
    fun anOldFashionedShapeIsKeptToo() {
        assertShape(4f / 3f, drawn(4f / 3f, boxWidth = 360, boxHeight = 640))
    }

    /** Nothing is known about a video that will not play, so it simply fills what it is given. */
    @Test
    fun anUnknownShapeFillsTheSpaceRatherThanGuessing() {
        val (width, height) = drawn(null, boxWidth = 400, boxHeight = 700)
        assertShape(4f / 7f, width to height)
    }

    /** The player reports nonsense before it has read the file; that must not become a shape. */
    @Test
    fun aVideoSizeIsOnlyAShapeOnceItIsReal() {
        assertNull(VideoSize.UNKNOWN.aspectOrNull())
        assertNull(VideoSize(0, 0).aspectOrNull())
        assertNull(VideoSize(1920, 0).aspectOrNull())
        assertEquals(16f / 9f, VideoSize(1920, 1080).aspectOrNull()!!, 0.001f)
        // Anamorphic video: the pixels are not square, and the shape has to allow for that.
        assertEquals(16f / 9f, VideoSize(1440, 1080, 0, 4f / 3f).aspectOrNull()!!, 0.001f)
    }
}
