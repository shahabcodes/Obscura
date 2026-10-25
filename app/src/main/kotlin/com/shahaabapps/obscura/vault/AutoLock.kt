package com.shahaabapps.obscura.vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the vault when the app has been in the background for the configured delay,
 * and immediately when the screen turns off.
 *
 * Long-running work that needs the keys (an import of thousands of files) can [hold] the
 * vault open; a lock requested meanwhile happens as soon as the last hold is released.
 * All methods run on the main thread.
 */
@Singleton
class AutoLock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("obscura.settings", Context.MODE_PRIVATE)
    private var holds = 0
    private var lockPending = false
    private val lockNow = Runnable { requestLock() }

    /** When the app last left the screen, on the clock that keeps running in deep sleep. */
    private var awaySince = 0L

    /** The same moment on the clock that stops in deep sleep, so sleep can be told apart. */
    private var awaySinceAwake = 0L

    /** Set when the screen turned off while the app was away, even if the lock had to wait. */
    private var screenWentOff = false

    /** Background delay before locking; 0 locks as soon as the app leaves the screen. */
    var delayMs: Long
        get() = prefs.getLong("autoLockMs", DEFAULT_DELAY_MS)
        set(value) = prefs.edit().putLong("autoLockMs", value).apply()

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenWentOff = true
            requestLock()
        }
    }

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        ContextCompat.registerReceiver(
            context,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Keeps the vault unlocked until [release]. */
    fun hold() = onMain { holds++ }

    fun release() = onMain {
        holds = (holds - 1).coerceAtLeast(0)
        if (holds == 0 && lockPending) lockAndClear()
    }

    /** True while a lock was requested but is waiting for work to finish. */
    val isLockDeferred: Boolean get() = lockPending

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(lockNow)
        val away = if (awaySince == 0L) 0L else SystemClock.elapsedRealtime() - awaySince
        val awake = if (awaySince == 0L) 0L else SystemClock.uptimeMillis() - awaySinceAwake
        // The phone only sleeps with its screen off, so time that passed while it slept means
        // the screen went off even if the broadcast never reached a frozen process.
        val slept = away - awake > SLEEP_SLACK_MS
        awaySince = 0L
        // Coming back must not cancel a lock that was already due. While another app is on top
        // (a system picker, say) Android can freeze this process, so neither the timer above nor
        // the screen-off broadcast is guaranteed to have run: check the clock on the way back.
        // A lock that is only waiting for work to finish (lockPending) is left alone.
        if (screenWentOff || slept || away >= delayMs) {
            screenWentOff = false
            requestLock()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        awaySince = SystemClock.elapsedRealtime()
        awaySinceAwake = SystemClock.uptimeMillis()
        handler.removeCallbacks(lockNow)
        handler.postDelayed(lockNow, delayMs)
    }

    private fun requestLock() {
        if (holds > 0) lockPending = true else lockAndClear()
    }

    private fun lockAndClear() {
        lockPending = false
        screenWentOff = false
        session.lock()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 30_000L

        /** Below this, the gap between the two clocks is noise rather than real sleep. */
        private const val SLEEP_SLACK_MS = 1_000L
    }
}
