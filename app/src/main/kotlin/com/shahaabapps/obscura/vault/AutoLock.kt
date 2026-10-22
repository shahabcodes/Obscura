package com.shahaabapps.obscura.vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
 */
@Singleton
class AutoLock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val lockNow = Runnable { session.lock() }
    private val prefs = context.getSharedPreferences("obscura.settings", Context.MODE_PRIVATE)

    /** Background delay before locking; 0 locks as soon as the app leaves the screen. */
    var delayMs: Long
        get() = prefs.getLong("autoLockMs", DEFAULT_DELAY_MS)
        set(value) = prefs.edit().putLong("autoLockMs", value).apply()

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = session.lock()
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

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(lockNow)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(lockNow)
        handler.postDelayed(lockNow, delayMs)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 30_000L
    }
}
