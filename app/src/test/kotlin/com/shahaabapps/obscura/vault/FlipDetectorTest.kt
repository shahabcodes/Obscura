package com.shahaabapps.obscura.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipDetectorTest {

    private fun FlipDetector.feed(x: Float, y: Float, z: Float, from: Long, to: Long, step: Long = 50): List<Long> =
        (from..to step step).filter { onReading(x, y, z, it) }

    @Test
    fun faceDownForLongEnoughFiresOnce() {
        val d = FlipDetector()
        val fired = d.feed(0.2f, -0.4f, -9.7f, 0, 2_000)
        assertEquals(listOf(400L), fired)
    }

    @Test
    fun aQuickTipDoesNotFire() {
        val d = FlipDetector()
        assertTrue(d.feed(0f, 0f, -9.8f, 0, 300).isEmpty())
        assertTrue(d.feed(0f, 0f, 9.8f, 350, 1_000).isEmpty())
    }

    @Test
    fun faceUpOrHeldUprightNeverFires() {
        val d = FlipDetector()
        assertTrue(d.feed(0f, 0f, 9.8f, 0, 3_000).isEmpty())
        assertTrue(d.feed(0f, 9.8f, 0f, 3_050, 6_000).isEmpty())
    }

    @Test
    fun tiltedFaceDownIsNotFlat() {
        assertTrue(FlipDetector().feed(6f, 0f, -7.5f, 0, 2_000).isEmpty())
    }

    @Test
    fun firesAgainAfterBeingPickedUp() {
        val d = FlipDetector()
        assertEquals(1, d.feed(0f, 0f, -9.8f, 0, 1_000).size)
        assertFalse(d.onReading(0f, 0f, 9.8f, 1_050))
        assertEquals(1, d.feed(0f, 0f, -9.8f, 1_100, 2_000).size)
    }
}
