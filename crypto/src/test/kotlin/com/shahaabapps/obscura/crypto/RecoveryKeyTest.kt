package com.shahaabapps.obscura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryKeyTest {

    @Test
    fun formatsAsEightGroupsOfFour() {
        val text = RecoveryKey.generate().format()
        val groups = text.split("-")
        assertEquals(8, groups.size)
        assertTrue(groups.all { it.length == 4 })
        assertTrue(text.none { it in "ILOU" })
    }

    @Test
    fun parseRoundTrips() {
        val key = RecoveryKey.generate()
        val parsed = RecoveryKey.parse(key.format())
        assertArrayEquals(key.secretBytes(), parsed.secretBytes())
    }

    @Test
    fun parseIsForgivingAboutCaseSpacingAndLookAlikes() {
        val key = RecoveryKey.generate()
        val messy = key.format().lowercase().replace("-", "  ").replace('0', 'o').replace('1', 'l')
        assertArrayEquals(key.secretBytes(), RecoveryKey.parse(messy).secretBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun typoFailsChecksum() {
        val text = RecoveryKey.generate().format()
        val replacement = if (text[0] == 'A') 'B' else 'A'
        RecoveryKey.parse(replacement + text.substring(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongLengthIsRejected() {
        RecoveryKey.parse("ABCD-EFGH")
    }

    @Test
    fun toStringDoesNotLeakTheSecret() {
        assertEquals("RecoveryKey(****)", RecoveryKey.generate().toString())
    }
}
