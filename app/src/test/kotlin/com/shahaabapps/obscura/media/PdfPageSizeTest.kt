package com.shahaabapps.obscura.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page bitmaps must never be big enough to exhaust a phone's memory. */
class PdfPageSizeTest {

    private fun bytes(size: Pair<Int, Int>) = size.first.toLong() * size.second * 4

    @Test
    fun anOrdinaryPageIsRenderedAtTheAskedWidth() {
        assertEquals(1344 to 1739, SealedPdf.fit(612, 792, 1344))
    }

    @Test
    fun aVeryTallPageIsScaledDownInstead() {
        val size = SealedPdf.fit(612, 60_000, 1344)
        assertTrue("$size", bytes(size) <= SealedPdf.MAX_PAGE_BYTES)
        assertTrue("$size", size.second <= SealedPdf.MAX_SIDE)
        // Still the same shape, about 98 times taller than wide.
        assertEquals(98.0, size.second.toDouble() / size.first, 1.0)
    }

    @Test
    fun aHugeSquarePageIsCappedByTotalPixels() {
        val size = SealedPdf.fit(20_000, 20_000, 8_000)
        assertTrue("$size", bytes(size) <= SealedPdf.MAX_PAGE_BYTES)
        assertEquals(size.first, size.second)
    }

    @Test
    fun nonsensePageSizesStillGiveSomethingDrawable() {
        for (size in listOf(SealedPdf.fit(0, 0, 1000), SealedPdf.fit(-5, 10, 1000), SealedPdf.fit(10, 10, 0))) {
            assertTrue("$size", size.first >= 1 && size.second >= 1)
            assertTrue("$size", bytes(size) <= SealedPdf.MAX_PAGE_BYTES)
        }
    }
}
