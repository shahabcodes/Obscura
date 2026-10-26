package com.shahaabapps.obscura

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * Turning the screen on and off, and making sure it actually happened.
 *
 * Several tests here turn the screen off to prove the vault locks, and the screen is shared
 * state: a test that leaves it off stops every later test from bringing an activity up, and a
 * keypress sent to an already-dark screen produces no broadcast at all, so a test waiting for
 * one fails for a reason that has nothing to do with the app. Both go wrong quietly, which is
 * why these check the result rather than assuming it.
 */
object TestScreen {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Runs a shell command and waits for it to finish, returning what it printed. */
    fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
        }

    /** What the device says its screen is doing, for putting in a failure message. */
    fun state(): String = wakefulness()

    private fun wakefulness(): String =
        shell("dumpsys power").lineSequence()
            .firstOrNull { it.contains("mWakefulness=") }
            ?.substringAfter("mWakefulness=")
            ?.trim()
            ?: "unknown"

    /** Wakes the screen and puts the keyguard away, and waits until it has really happened. */
    fun wake() {
        repeat(ATTEMPTS) {
            if (wakefulness() == "Awake") {
                shell("wm dismiss-keyguard")
                Thread.sleep(SETTLE_MS)
                return
            }
            shell("input keyevent KEYCODE_WAKEUP")
            Thread.sleep(SETTLE_MS)
        }
        throw AssertionError("the screen would not wake; it is ${wakefulness()}")
    }

    /**
     * Turns the screen off and waits for the device to really be asleep, so that whatever the
     * test is watching for has actually been triggered.
     */
    fun sleep() {
        // It can only turn off from being on: a keypress to a dark screen does nothing.
        wake()
        repeat(ATTEMPTS) {
            shell("input keyevent KEYCODE_SLEEP")
            Thread.sleep(SETTLE_MS)
            // Asleep, not merely "not awake": dozing is the always-on display, where the
            // screen-off broadcast an app waits for has not necessarily gone out yet.
            if (wakefulness() == "Asleep") return
        }
        throw AssertionError("the screen would not turn off; it is ${wakefulness()}")
    }

    private const val ATTEMPTS = 5
    private const val SETTLE_MS = 400L
}
