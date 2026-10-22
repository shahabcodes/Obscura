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
}
