package com.shahaabapps.obscura.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import com.shahaabapps.obscura.crypto.RecoveryKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DecoyWipeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val clock = FakeClock()
    private val storage = MemoryStorage()
    private val settings = MemoryVaultSettings()
    private val pin = KeystoreBinding("obscura.test.decoy.real")
    private val decoyPin = KeystoreBinding("obscura.test.decoy.decoy")

    private class MemoryStorage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private class FakeClock(var now: Long = 1_000) : DeviceClock {
        override fun elapsedRealtime() = now
        override fun bootCount() = 1
    }

    private fun session() = VaultSession(
        context, VaultStore(root), LockoutPolicy(storage, clock), pin, KeystoreGate("obscura.test.decoy.bio"), KdfParams(1024, 1, 1),
        decoyStore = VaultStore(File(root.path + "-decoy")), decoyPinBinding = decoyPin, settings = settings,
    )

    @Before
    fun setUp() {
        root = File(context.cacheDir, "decoy-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        File(root.path + "-decoy").deleteRecursively()
        pin.delete()
        decoyPin.delete()
    }

    private fun VaultSession.fileNames() = runBlocking { openVault!!.database.files().observeAll().first().map { it.name } }

    private fun VaultSession.add(name: String) = runBlocking {
        openVault!!.files.put(name.toByteArray().inputStream(), IncomingFile(name, "text/plain", 0))
    }

    @Test
    fun decoySecretsOpenASeparateVault() = runBlocking {
        val s = session()
        val recovery = RecoveryKey.parse(s.create("real passphrase words".toCharArray()).format())
        s.setPin("111111".toCharArray())
        s.add("real-secret.txt")
        assertEquals(UnlockResult.Success, s.createDecoy("decoy passphrase words".toCharArray(), "222222".toCharArray()))
        assertTrue(s.hasDecoy())
        s.lock()

        assertEquals(UnlockResult.Success, s.unlockWithPassphrase("decoy passphrase words".toCharArray()))
        assertTrue(s.openVault!!.isDecoy)
        assertTrue("decoy starts empty", s.fileNames().isEmpty())
        s.add("recipes.txt")
        s.lock()

        assertEquals(UnlockResult.Success, s.unlockWithPin("111111".toCharArray()))
        assertFalse(s.openVault!!.isDecoy)
        assertEquals(listOf("real-secret.txt"), s.fileNames())
        s.lock()

        assertEquals(UnlockResult.Success, s.unlockWithPin("222222".toCharArray()))
        assertTrue(s.openVault!!.isDecoy)
        assertEquals(listOf("recipes.txt"), s.fileNames())
        s.lock()

        assertEquals("a wrong PIN misses both", UnlockResult.Wrong(3), s.unlockWithPin("999999".toCharArray()))
        assertEquals(UnlockResult.Success, s.unlockWithRecoveryKey(recovery.format()))
        assertFalse("recovery key always means the real vault", s.openVault!!.isDecoy)
        s.lock()
    }

    @Test
    fun decoySecretsMustDifferFromTheRealOnes() = runBlocking {
        val s = session()
        s.create("real passphrase words".toCharArray())
        s.setPin("111111".toCharArray())
        assertTrue(s.createDecoy("real passphrase words".toCharArray(), null) is UnlockResult.Failed)
        assertTrue(s.createDecoy("decoy passphrase words".toCharArray(), "111111".toCharArray()) is UnlockResult.Failed)
        assertFalse(s.hasDecoy())
        s.lock()
    }

    @Test
    fun theDecoyCannotManageDecoysAndHidesFingerprint() = runBlocking {
        val s = session()
        s.create("real passphrase words".toCharArray())
        s.createDecoy("decoy passphrase words".toCharArray(), null)
        s.lock()
        s.unlockWithPassphrase("decoy passphrase words".toCharArray())
        assertTrue(s.createDecoy("another one here".toCharArray(), null) is UnlockResult.Failed)
        s.removeDecoy()
        assertTrue("removing from inside the decoy does nothing", s.hasDecoy())
        assertFalse(s.isBiometricEnrolled())
        s.lock()
    }

    @Test
    fun removingTheDecoyStopsItsPassphrase() = runBlocking {
        val s = session()
        s.create("real passphrase words".toCharArray())
        s.createDecoy("decoy passphrase words".toCharArray(), null)
        s.removeDecoy()
        s.lock()
        assertEquals(UnlockResult.Wrong(3), s.unlockWithPassphrase("decoy passphrase words".toCharArray()))
    }

    @Test
    fun wipeAfterTenDestroysEverything() = runBlocking {
        settings.wipeAfterTen = true
        val s = session()
        s.create("real passphrase words".toCharArray())
        s.createDecoy("decoy passphrase words".toCharArray(), null)
        s.lock()
        var last: UnlockResult? = null
        repeat(9) {
            clock.now += 3_600_001 // skip any lockout wait
            last = s.unlockWithPassphrase("wrong guess".toCharArray())
        }
        // From the 5th failure on, each one also starts a wait; the warning travels with it.
        assertEquals(1, (last as UnlockResult.LockedOut).attemptsBeforeWipe)
        assertEquals(1, s.attemptsBeforeWipe())
        clock.now += 3_600_001
        assertEquals(UnlockResult.Wiped, s.unlockWithPassphrase("wrong guess".toCharArray()))
        assertTrue(s.state.value is VaultState.NoVault)
        assertFalse(VaultStore(root).exists())
        assertFalse(VaultStore(File(root.path + "-decoy")).exists())
    }

    @Test
    fun withoutTheSettingTenFailuresOnlyDelay() = runBlocking {
        val s = session()
        s.create("real passphrase words".toCharArray())
        s.lock()
        repeat(12) {
            clock.now += 3_600_001
            s.unlockWithPassphrase("wrong guess".toCharArray())
        }
        assertTrue(VaultStore(root).exists())
        clock.now += 3_600_001
        assertEquals(UnlockResult.Success, s.unlockWithPassphrase("real passphrase words".toCharArray()))
        s.lock()
    }
}
