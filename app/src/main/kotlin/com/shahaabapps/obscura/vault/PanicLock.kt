package com.shahaabapps.obscura.vault

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.shahaabapps.obscura.importer.ImportManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Decides when the phone has been laid face-down: gravity points out of the back of the screen
 * (z ≈ −9.8 m/s²) with the phone roughly flat, held for [HOLD_MS]. Pure logic for unit tests.
 */
class FlipDetector {
    private var faceDownSince: Long? = null
    private var fired = false

    /** Feed accelerometer readings; returns true exactly once per face-down event. */
    fun onReading(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val faceDown = z < -FACE_DOWN_Z && abs(x) < FLAT_TOLERANCE && abs(y) < FLAT_TOLERANCE
        if (!faceDown) {
            faceDownSince = null
            fired = false
            return false
        }
        val since = faceDownSince ?: timeMs.also { faceDownSince = it }
        if (!fired && timeMs - since >= HOLD_MS) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        const val FACE_DOWN_Z = 8.0f
        const val FLAT_TOLERANCE = 3.0f
        const val HOLD_MS = 400L
    }
}

/**
 * Panic lock: while the app is on screen and the switch is on, laying the phone face-down locks
 * the vault at once, cancelling any import (which would otherwise hold the vault open).
 * The sensor is only listened to while Obscura is in the foreground.
 */
@Singleton
class PanicLock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val settings: VaultSettings,
    private val importManager: ImportManager,
) : DefaultLifecycleObserver, SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = FlipDetector()
    private val main = Handler(Looper.getMainLooper())
    private var listening = false

    val available: Boolean get() = accelerometer != null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = refresh()

    override fun onStop(owner: LifecycleOwner) = stopListening()

    /** Re-reads the switch; call after changing it in settings. */
    fun refresh() {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (foreground && settings.panicLock && accelerometer != null) startListening() else stopListening()
    }

    private fun startListening() {
        if (listening) return
        listening = sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI) == true
    }

    private fun stopListening() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (detector.onReading(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000)) trigger()
    }

    /** Locks immediately. Exposed so tests can simulate the flip. */
    fun trigger() = main.post {
        if (session.openVault == null) return@post
        importManager.cancel()
        session.lock()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
