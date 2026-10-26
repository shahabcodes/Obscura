package com.shahaabapps.obscura.ui.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules for coming back to where the user was once the vault is open again. */
class LastPlaceTest {

    @After
    fun tidy() = LastPlace.forget()

    @Test
    fun theLastScreenIsWhereUnlockingGoes() {
        LastPlace.remember("${Routes.VIEWER}/abc123", isDecoy = false)
        assertEquals("${Routes.VIEWER}/abc123", LastPlace.take(isDecoy = false))
    }

    /** Taking it clears it, so a later unlock does not jump somewhere unexpected. */
    @Test
    fun aPlaceIsOnlyComeBackToOnce() {
        LastPlace.remember(Routes.GALLERY, isDecoy = false)
        assertEquals(Routes.GALLERY, LastPlace.take(isDecoy = false))
        assertNull(LastPlace.take(isDecoy = false))
    }

    /**
     * The whole point of the decoy is that it shows no sign of the other vault. Landing on the
     * screen the real vault was last on would be exactly such a sign.
     */
    @Test
    fun aPlaceFromTheRealVaultIsNeverRestoredIntoTheDecoy() {
        LastPlace.remember("${Routes.VIEWER}/secret", isDecoy = false)
        assertNull("the decoy was sent to the real vault's screen", LastPlace.take(isDecoy = true))
        // And it is gone afterwards, rather than waiting to surface on the next unlock.
        assertNull(LastPlace.take(isDecoy = false))
    }

    @Test
    fun aPlaceFromTheDecoyIsNeverRestoredIntoTheRealVault() {
        LastPlace.remember("${Routes.ALBUM}/holiday", isDecoy = true)
        assertNull(LastPlace.take(isDecoy = false))
    }

    /**
     * The lock screens are passed through on the way out and back in. They must leave what is
     * remembered alone, or unlocking would clear the place a moment before returning to it.
     */
    @Test
    fun theLockScreensLeaveTheRememberedPlaceAlone() {
        LastPlace.remember(Routes.GALLERY, isDecoy = false)
        Routes.lockedSafe.forEach { LastPlace.remember(it, isDecoy = false) }
        assertEquals(Routes.GALLERY, LastPlace.take(isDecoy = false))
    }

    /** Home is where unlocking goes anyway, so there is nothing to come back to. */
    @Test
    fun beingAtHomeMeansThereIsNowhereToComeBackTo() {
        LastPlace.remember(Routes.GALLERY, isDecoy = false)
        LastPlace.remember(Routes.HOME, isDecoy = false)
        assertNull(LastPlace.take(isDecoy = false))
    }

    @Test
    fun nothingIsRememberedFromNowhere() {
        LastPlace.remember(Routes.VIDEOS, isDecoy = false)
        LastPlace.remember(null, isDecoy = false)
        assertEquals("a null route should change nothing", Routes.VIDEOS, LastPlace.take(isDecoy = false))
    }
}
