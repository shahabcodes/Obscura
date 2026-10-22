package com.shahaabapps.obscura.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseStrengthTest {

    @Test
    fun emptyIsZero() = assertEquals(0, PassphraseStrength.evaluate("").score)

    @Test
    fun shortPasswordsAreTooWeak() {
        assertEquals(1, PassphraseStrength.evaluate("hunter2").score)
        assertTrue(PassphraseStrength.evaluate("password").score < PassphraseStrength.MIN_SCORE)
    }

    @Test
    fun threeWordsIsWeakFourIsGoodSixIsExcellent() {
        assertEquals(2, PassphraseStrength.evaluate("lantern orbit velvet").score)
        assertEquals(3, PassphraseStrength.evaluate("lantern orbit velvet canyon").score)
        assertEquals(4, PassphraseStrength.evaluate("lantern orbit velvet canyon mosaic harbor").score)
    }

    @Test
    fun longMixedPasswordIsGood() {
        assertTrue(PassphraseStrength.evaluate("Tq9#mX2!vL7@pR4z").score >= PassphraseStrength.MIN_SCORE)
    }

    @Test
    fun generatedPassphrasesPassTheBar() {
        repeat(20) {
            val generated = PassphraseStrength.generate()
            assertEquals(7, generated.split(" ").size)
            assertTrue(PassphraseStrength.evaluate(generated).score >= PassphraseStrength.MIN_SCORE)
        }
    }

    @Test
    fun wordListHas256DistinctWords() {
        assertEquals(256, PassphraseStrength.wordCount)
        assertEquals(256, PassphraseStrength.distinctWordCount)
    }
}
