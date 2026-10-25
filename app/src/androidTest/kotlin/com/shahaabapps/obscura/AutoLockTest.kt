package com.shahaabapps.obscura

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The vault must lock when the screen turns off and when the app goes to the background. */
@RunWith(AndroidJUnit4::class)
class AutoLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    private fun waitForLock(timeoutMs: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (entry.session().state.value is VaultState.Locked) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun unlockedVaultInForeground(): ActivityScenario<MainActivity> {
        wipe()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
        assertTrue(entry.session().state.value is VaultState.Unlocked)
        return ActivityScenario.launch(MainActivity::class.java)
    }

    @Before
    fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    /**
     * These tests turn the screen off on purpose. Left that way, nothing after them can bring an
     * activity up at all, so the device is handed back awake.
     */
    @After
    fun wakeAgain() = wake()

    @After
    fun tearDown() {
        entry.autoLock().delayMs = AutoLock.DEFAULT_DELAY_MS
        wake()
        wipe()
    }

    @Test
    fun screenOffLocksImmediately() {
        unlockedVaultInForeground().use {
            shell("input keyevent KEYCODE_SLEEP")
            assertTrue("Vault should lock when the screen turns off", waitForLock())
        }
    }

    @Test
    fun leavingTheAppLocksAfterTheDelay() {
        entry.autoLock().delayMs = 500
        unlockedVaultInForeground().use {
            shell("input keyevent KEYCODE_HOME")
            assertTrue("Vault should lock after leaving the app", waitForLock())
        }
    }

    /** Activity first, then unlock: so the app's own onStart can't race the test's. */
    private fun foregroundThenUnlock(): ActivityScenario<MainActivity> {
        wipe()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
        assertTrue(entry.session().state.value is VaultState.Unlocked)
        return scenario
    }

    /**
     * A lock that came due while long work held the vault open must survive the user coming
     * back. This is the system-picker case: the picker holds the vault open, the lock falls due
     * behind it, and returning used to wipe it, leaving the vault unlocked for good.
     */
    @Test
    fun aLockThatFellDueWhileHeldSurvivesComingBack() {
        val autoLock = entry.autoLock()
        autoLock.delayMs = 500
        foregroundThenUnlock().use {
            autoLock.hold() // what the file picker and a running import do
            try {
                instrumentation.runOnMainSync { autoLock.onStop(FakeOwner) }
                Thread.sleep(1_200) // the lock falls due behind the picker
                instrumentation.runOnMainSync { autoLock.onStart(FakeOwner) } // the user comes back
                assertTrue("still unlocked while the work runs", entry.session().state.value is VaultState.Unlocked)
                assertTrue("but a lock is still owed", autoLock.isLockDeferred)
            } finally {
                autoLock.release() // the picker closed, or the import finished
            }
            assertTrue("locks as soon as the work ends", waitForLock(3_000))
        }
    }

    /** Screen off while work holds the vault open: it locks the moment that work ends. */
    @Test
    fun screenOffWhileHeldLocksWhenTheWorkEnds() {
        val autoLock = entry.autoLock()
        autoLock.delayMs = 60_000
        foregroundThenUnlock().use {
            autoLock.hold()
            try {
                shell("input keyevent KEYCODE_SLEEP")
                Thread.sleep(1_000)
                assertTrue("held open while the work runs", entry.session().state.value is VaultState.Unlocked)
                wake()
                instrumentation.runOnMainSync { autoLock.onStart(FakeOwner) }
            } finally {
                autoLock.release()
            }
            assertTrue("the screen went off, so it still locks", waitForLock(3_000))
        }
    }

    /** Stands in for the process lifecycle owner, so leaving and returning can be driven exactly. */
    private object FakeOwner : androidx.lifecycle.LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
    }
}
