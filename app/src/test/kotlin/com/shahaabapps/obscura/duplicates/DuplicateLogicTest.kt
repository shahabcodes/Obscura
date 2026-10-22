package com.shahaabapps.obscura.duplicates

import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateLogicTest {

    private fun photo(id: String, sha: String = id, phash: Long?, taken: Long = 0, size: Long = 100, w: Int = 100, h: Int = 100, sealed: Long = 0) =
        VaultFile(id, "$id.jpg", "image/jpeg", FileKind.PHOTO, size, taken, sealed, ByteArray(0), sha, w, h, phash = phash)

    private fun doc(id: String, sha: String, sealed: Long) =
        VaultFile(id, "$id.pdf", "application/pdf", FileKind.DOCUMENT, 500, 0, sealed, ByteArray(0), sha)

    @Test
    fun dHashComparesNeighbours() {
        // Brightness rising left to right everywhere: no pixel is brighter than its right neighbour.
        assertEquals(0L, DuplicateLogic.dHash(IntArray(72) { it % 9 }))
        // Falling everywhere: all 64 bits set.
        assertEquals(-1L, DuplicateLogic.dHash(IntArray(72) { 8 - it % 9 }))
    }

    @Test
    fun exactCopiesKeepTheFirstSealed() {
        val groups = DuplicateLogic.find(listOf(doc("b", "same", sealed = 20), doc("a", "same", sealed = 10), doc("c", "other", 5)))
        val g = groups.single()
        assertEquals(DuplicateKind.EXACT, g.kind)
        assertEquals("a", g.best.id)
        assertEquals(listOf("b"), g.extras.map { it.id })
        assertEquals(500L, g.reclaimableBytes)
    }

    @Test
    fun lookAlikesGroupAndKeepTheSharpest() {
        val base = 0x0F0F_F0F0_1234_5678L
        val small = photo("small", phash = base, w = 800, h = 600, size = 90)
        val large = photo("large", phash = base xor 0b111, w = 4000, h = 3000, size = 900) // 3 bits apart
        val other = photo("other", phash = base.inv(), w = 4000, h = 3000)
        val g = DuplicateLogic.find(listOf(small, large, other)).single()
        assertEquals(DuplicateKind.LOOK_ALIKE, g.kind)
        assertEquals("large", g.best.id)
        assertEquals(listOf("small"), g.extras.map { it.id })
    }

    @Test
    fun burstsNeedTimeAndSimilarity() {
        val base = 0x5555_AAAA_5555_AAAAL
        val twelveBits = (1L shl 12) - 1
        val shot1 = photo("s1", phash = base, taken = 1_000)
        val shot2 = photo("s2", phash = base xor twelveBits, taken = 3_000) // 12 bits apart, 2 s later
        // 12 bits from s1 (and 24 from s2) but a minute later: similar, yet not part of the burst.
        val later = photo("late", phash = base xor (twelveBits shl 20), taken = 60_000)
        val g = DuplicateLogic.find(listOf(shot1, shot2, later)).single()
        assertEquals(DuplicateKind.BURST, g.kind)
        assertEquals(setOf("s1", "s2"), g.files.map { it.id }.toSet())
    }

    @Test
    fun exactExtrasAreNotCountedTwice() {
        val h = 0x1111_2222_3333_4444L
        val a = photo("a", sha = "x", phash = h, sealed = 1)
        val b = photo("b", sha = "x", phash = h, sealed = 2)
        val groups = DuplicateLogic.find(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(DuplicateKind.EXACT, groups.single().kind)
    }

    @Test
    fun photosWithoutFingerprintsAreIgnoredVisually() {
        assertTrue(DuplicateLogic.find(listOf(photo("a", phash = null), photo("b", phash = null))).isEmpty())
    }

    @Test
    fun chainsOfLookAlikesFormOneGroup() {
        val h = 0L
        val list = listOf(photo("1", phash = h), photo("2", phash = h xor 0b11111), photo("3", phash = h xor (0b11111L shl 5)))
        // 1–2 and 1–3 are 5 bits apart; 2–3 is 10. All three end up together.
        assertEquals(3, DuplicateLogic.find(list).single().files.size)
    }
}
