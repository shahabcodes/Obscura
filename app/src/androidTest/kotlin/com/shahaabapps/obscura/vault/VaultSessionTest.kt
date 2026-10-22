package com.shahaabapps.obscura.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** Runs on a real device: real SQLCipher, real Android Keystore, small Argon2 settings. */
@RunWith(AndroidJUnit4::class)
class VaultSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: VaultStore
    private lateinit var clock: FakeClock
    private val binding = KeystoreBinding("obscura.test.pin")
    private val gate = KeystoreGate("obscura.test.bio")

    private class MemoryStorage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private class FakeClock(var now: Long = 10_000) : DeviceClock {
        override fun elapsedRealtime() = now
        override fun bootCount() = 1
    }

    private fun newSession(): VaultSession =
        VaultSession(context, store, LockoutPolicy(MemoryStorage(), clock), binding, gate, KdfParams(1024, 1, 1))

    @Before
    fun setUp() {
        root = File(context.cacheDir, "vault-test-${System.nanoTime()}")
        store = VaultStore(root)
        clock = FakeClock()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        binding.delete()
        gate.deleteKey()
    }

    @Test
    fun createStartsUnlockedAndWritesHeader() = runTest {
        val session = newSession()
        assertTrue(session.state.value is VaultState.NoVault)
        val recovery = session.create("correct horse battery staple".toCharArray())
        assertTrue(session.state.value is VaultState.Unlocked)
        assertTrue(store.exists())
        assertEquals(8, recovery.format().split("-").size)
    }

    @Test
    fun sealedFileSurvivesLockAndPassphraseUnlock() = runTest {
        val session = newSession()
        session.create("passphrase one two".toCharArray())
        val content = Random(1).nextBytes(2_500_000)
        val sealed = session.openVault!!.files.put(content.inputStream(), IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000))
        assertEquals(FileKind.PHOTO, sealed.kind)
        assertEquals(content.size.toLong(), sealed.sizeBytes)

        // The blob on disk must not contain the plaintext.
        val blob = session.openVault!!.files.blobFile(sealed.id).readBytes()
        assertFalse(blob.copyOfRange(100, 164).contentEquals(content.copyOfRange(100, 164)))

        session.lock()
        assertTrue(session.state.value is VaultState.Locked)

        val reopened = newSession()
        assertTrue(reopened.state.value is VaultState.Locked)
        assertEquals(UnlockResult.Success, reopened.unlockWithPassphrase("passphrase one two".toCharArray()))
        val vault = reopened.openVault!!
        val row = vault.database.files().observeAll().first().single()
        assertEquals("beach.jpg", row.name)
        assertArrayEquals(content, vault.files.open(row).use { it.readBytes() })
        reopened.lock()
    }

    /**
     * Regression: the database key array used to be wiped right after opening, so any extra
     * connection SQLCipher opened for parallel reads failed with "file is not a database".
     */
    @Test
    fun parallelReadsOpenExtraConnectionsSuccessfully() = runTest {
        val session = newSession()
        session.create("passphrase".toCharArray())
        val vault = session.openVault!!
        repeat(5) { vault.files.put(ByteArray(64) { b -> (b * it).toByte() }.inputStream(), IncomingFile("f$it.bin", "application/octet-stream", it.toLong())) }
        val dao = vault.database.files()
        val results: List<Int> = withContext(Dispatchers.IO) {
            List(16) { async { dao.observeAll().first().size + dao.countWithHash("none") } }.awaitAll()
        }
        assertTrue(results.all { it == 5 })
        session.lock()
    }

    @Test
    fun indexDatabaseIsEncryptedOnDisk() = runTest {
        val session = newSession()
        session.create("passphrase".toCharArray())
        session.openVault!!.files.put(ByteArray(10).inputStream(), IncomingFile("secret-diary-name.txt", "text/plain", 0))
        session.lock()
        val raw = store.databaseFile.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("SQLite format 3"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("secret-diary-name"))
    }

    @Test
    fun wrongPassphraseCountsDownThenLocksOut() = runTest {
        newSession().create("right".toCharArray()).also { }
        val session = newSession()
        repeat(4) { i ->
            val result = session.unlockWithPassphrase("wrong".toCharArray())
            assertEquals(UnlockResult.Wrong(3 - i), result)
        }
        val fifth = session.unlockWithPassphrase("wrong".toCharArray())
        assertTrue(fifth is UnlockResult.LockedOut)
        // Even the right passphrase is refused while locked out.
        assertTrue(session.unlockWithPassphrase("right".toCharArray()) is UnlockResult.LockedOut)
        clock.now += 61_000
        assertEquals(UnlockResult.Success, session.unlockWithPassphrase("right".toCharArray()))
        session.lock()
    }

    @Test
    fun pinUnlockUsesTheKeystore() = runTest {
        val session = newSession()
        session.create("passphrase".toCharArray())
        session.setPin("246810".toCharArray())
        assertTrue(session.hasPin())
        session.lock()

        val again = newSession()
        assertEquals(UnlockResult.Wrong(3), again.unlockWithPin("111111".toCharArray()))
        assertEquals(UnlockResult.Success, again.unlockWithPin("246810".toCharArray()))
        again.lock()
    }

    @Test
    fun pinStopsWorkingIfTheDeviceKeyIsGone() = runTest {
        val session = newSession()
        session.create("passphrase".toCharArray())
        session.setPin("246810".toCharArray())
        session.lock()
        binding.delete()
        assertTrue(newSession().unlockWithPin("246810".toCharArray()) is UnlockResult.Failed)
    }

    @Test
    fun recoveryKeyUnlocksAndTyposAreNotCounted() = runTest {
        val recovery = newSession().create("passphrase".toCharArray())
        val session = newSession()
        val typo = recovery.format().replaceRange(0, 1, if (recovery.format()[0] == 'A') "B" else "A")
        assertTrue(session.unlockWithRecoveryKey(typo) is UnlockResult.Failed)
        assertEquals(UnlockResult.Success, session.unlockWithRecoveryKey(recovery.format().lowercase()))
        assertNotNull(session.openVault)
        session.lock()
    }
}
