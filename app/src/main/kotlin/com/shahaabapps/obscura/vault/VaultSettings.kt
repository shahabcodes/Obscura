package com.shahaabapps.obscura.vault

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Security switches that must be readable while the vault is locked. */
interface VaultSettings {
    /** Destroy everything after this many wrong attempts in a row (off by default). */
    var wipeAfterTen: Boolean

    /** Lock the moment the phone is laid face-down (on by default). */
    var panicLock: Boolean

    companion object {
        const val WIPE_THRESHOLD = 10
    }
}

class MemoryVaultSettings(override var wipeAfterTen: Boolean = false, override var panicLock: Boolean = true) : VaultSettings

@Singleton
class PrefsVaultSettings @Inject constructor(@ApplicationContext context: Context) : VaultSettings {
    private val prefs = context.getSharedPreferences("obscura.settings", Context.MODE_PRIVATE)
    override var wipeAfterTen: Boolean
        get() = prefs.getBoolean("wipeAfterTen", false)
        set(value) = prefs.edit().putBoolean("wipeAfterTen", value).apply()
    override var panicLock: Boolean
        get() = prefs.getBoolean("panicLock", true)
        set(value) = prefs.edit().putBoolean("panicLock", value).apply()
}
