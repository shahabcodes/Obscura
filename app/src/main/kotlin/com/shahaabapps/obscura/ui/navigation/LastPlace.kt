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
        if (route == null) return
        // Home is where unlocking goes anyway, so there is nothing to come back to.
        if (route == Routes.HOME) {
            forget()
            return
        }
        // Only screens that show the vault's own contents. Everything else — the lock screens
        // passed through on the way out and back, and the steps of setting a vault up — leaves
        // what is remembered alone, rather than dropping the user somewhere odd on the way back.
        if (!worthReturningTo(route)) return
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

    /** The screens that show what is in the vault, and so are worth coming back to. */
    private fun worthReturningTo(route: String) = route.substringBefore('/') in setOf(
        Routes.GALLERY,
        Routes.VIEWER,
        Routes.VIDEO,
        Routes.DOCUMENT,
        Routes.REEL,
        Routes.ALBUM,
        Routes.ALBUMS,
        Routes.VIDEOS,
        Routes.GIFS,
        Routes.AUDIO,
        Routes.DOCS,
        Routes.SEARCH,
        Routes.DUPLICATES,
    )
}
