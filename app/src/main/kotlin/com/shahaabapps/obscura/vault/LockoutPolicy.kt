package com.shahaabapps.obscura.vault

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where the lockout counters live. Plain storage: they are not secret, only tamper-annoying. */
interface LockoutStorage {
    var failures: Int
    var lockedUntilElapsed: Long
    var bootCount: Int
}

/** Monotonic time and boot identity, so changing the wall clock cannot skip a lockout. */
interface DeviceClock {
    fun elapsedRealtime(): Long
    fun bootCount(): Int
}

/**
 * Increasing delays after wrong passphrases, PINs or recovery keys:
 * 4 free attempts, then 1 min, 5 min, 15 min and 60 min for every attempt after that.
 * The timer runs on the boot-relative clock; after a reboot the current delay restarts in full.
 */
class LockoutPolicy(private val storage: LockoutStorage, private val clock: DeviceClock) {

    val failures: Int get() = storage.failures

    /** Milliseconds until another attempt is allowed, or 0. */
    fun remainingMs(): Long {
        if (storage.lockedUntilElapsed == 0L) return 0
        if (storage.bootCount != clock.bootCount()) {
            storage.bootCount = clock.bootCount()
            storage.lockedUntilElapsed = clock.elapsedRealtime() + delayFor(storage.failures)
        }
        return (storage.lockedUntilElapsed - clock.elapsedRealtime()).coerceAtLeast(0)
    }

    /** Attempts left before the next delay starts (0 when already in delay territory). */
    fun freeAttemptsLeft(): Int = (FREE_ATTEMPTS - storage.failures).coerceAtLeast(0)

    fun recordFailure() {
        storage.failures += 1
        val delay = delayFor(storage.failures)
        storage.bootCount = clock.bootCount()
        storage.lockedUntilElapsed = if (delay > 0) clock.elapsedRealtime() + delay else 0
    }

    fun recordSuccess() {
        storage.failures = 0
        storage.lockedUntilElapsed = 0
    }

    companion object {
        const val FREE_ATTEMPTS = 4
        private const val MINUTE = 60_000L

        fun delayFor(failures: Int): Long = when {
            failures <= FREE_ATTEMPTS -> 0
            failures == 5 -> 1 * MINUTE
            failures == 6 -> 5 * MINUTE
            failures == 7 -> 15 * MINUTE
            else -> 60 * MINUTE
        }
    }
}

@Singleton
class PrefsLockoutStorage @Inject constructor(@ApplicationContext context: Context) : LockoutStorage {
    private val prefs = context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE)
    override var failures: Int
        get() = prefs.getInt("failures", 0)
        set(value) = prefs.edit().putInt("failures", value).apply()
    override var lockedUntilElapsed: Long
        get() = prefs.getLong("lockedUntil", 0)
        set(value) = prefs.edit().putLong("lockedUntil", value).apply()
    override var bootCount: Int
        get() = prefs.getInt("bootCount", -1)
        set(value) = prefs.edit().putInt("bootCount", value).apply()
}

@Singleton
class SystemDeviceClock @Inject constructor(@ApplicationContext private val context: Context) : DeviceClock {
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    override fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
}
