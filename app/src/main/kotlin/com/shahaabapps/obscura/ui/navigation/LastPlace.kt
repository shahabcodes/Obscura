package com.shahaabapps.obscura.ui.navigation

/**
 * Where the user was when the vault locked, so unlocking puts them back rather than at the top.
 *
 * Kept only in memory, and only for as long as the app is running. Writing it down would mean
 * leaving a note outside the vault saying which file was being looked at, which is exactly the
 * sort of thing this app exists to avoid — so after the app is closed and opened again, or after
 * the phone restarts, unlocking starts at home.
 *
 * It is also tied to the vault it came from: a place remembered in the real vault is never
 * restored into the decoy, or the other way round.
 */
object LastPlace {

    private var route: String? = null
    private var fromDecoy: Boolean = false

    /** Remembers [route], unless it is a screen that means nothing to come back to. */
    fun remember(route: String?, isDecoy: Boolean) {
        // The lock screens are passed through on the way in and out; they must leave what is
        // remembered alone, or unlocking would wipe the place a moment before going back to it.
        if (route == null || route in Routes.lockedSafe) return
        // Home is where unlocking goes anyway, so there is nothing to come back to.
        if (route == Routes.HOME) {
            forget()
            return
        }
        this.route = route
        this.fromDecoy = isDecoy
    }

    /**
     * The place to return to now that [isDecoy] vault is open, or null to start at home. Taking
     * it clears it, so a later unlock does not jump somewhere unexpected.
     */
    fun take(isDecoy: Boolean): String? {
        val place = route?.takeIf { fromDecoy == isDecoy }
        forget()
        return place
    }

    fun forget() {
        route = null
        fromDecoy = false
    }
}
