package com.shahaabapps.obscura.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** A real vault is backed up, destroyed, restored from the backup and unlocked again. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val session get() = entry.session()
    private val backups get() = entry.backupManager()
    private val drive = File(context.cacheDir, "usb-drive")
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private fun wipe() = instrumentation.runOnMainSync {
        session.lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        session.reload()
    }

    @Before
    fun setUp() {
        wipe()
        drive.deleteRecursively()
        backups.dismiss()
    }

    @After
    fun tearDown() {
        wipe()
        drive.deleteRecursively()
    }

    private fun await(): BackupOutcome {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val s = backups.state.value
            if (s.running == null && s.outcome != null) return s.outcome!!
            Thread.sleep(50)
        }
        throw AssertionError("Backup did not finish: ${backups.state.value}")
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Creates a vault with a few files (and their thumbnails) and returns name → contents. */
    private fun fillVault(): Map<String, ByteArray> = runBlocking {
        session.create(passphrase.toCharArray()).wipe()
        val vault = session.openVault!!
        val contents = mapOf(
            "blue.jpg" to jpeg(Color.BLUE),
            "red.jpg" to jpeg(Color.RED),
            "notes.txt" to "meet at noon".toByteArray(),
        )
        for ((name, bytes) in contents) {
            val file = vault.files.put(bytes.inputStream(), IncomingFile(name, if (name.endsWith(".jpg")) "image/jpeg" else "text/plain", 1_700_000_000_000))
            if (name.endsWith(".jpg")) assertNotNull(vault.thumbnails.thumbnail(file))
        }
        contents
    }

    private fun readAll(): Map<String, ByteArray> = runBlocking {
        val vault = session.openVault!!
        vault.database.files().all().associate { f -> f.name to vault.files.open(f).use { it.readBytes() } }
    }

    @Test
    fun backupDestroyRestoreUnlock() {
        val original = fillVault()
        runBlocking { session.setPin("246810".toCharArray()) }

        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        val outcome = await() as BackupOutcome.BackedUp
        assertTrue(outcome.verify.ok)
        assertEquals(5, outcome.result.copied) // 3 blobs + 2 thumbnails
        val folder = File(drive, BackupEngine.FOLDER)
        assertFalse("device-bound PIN data stays on the phone", File(folder, "pin.bin").exists())
        assertFalse(File(folder, "index.db-wal").exists())

        // Nothing readable on the drive: no file name or content appears in plain text.
        val everything = folder.walkTopDown().filter { it.isFile && it.name != BackupEngine.MANIFEST }.map { it.readBytes() }.toList()
        for (needle in listOf("notes.txt", "meet at noon", "blue.jpg")) {
            assertFalse("\"$needle\" leaked", everything.any { String(it, Charsets.ISO_8859_1).contains(needle) })
        }

        // A second run copies nothing new.
        backups.dismiss()
        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        val again = await() as BackupOutcome.BackedUp
        assertEquals(0, again.result.copied)
        assertEquals(5, again.result.unchanged)

        // The phone is lost: the vault is gone.
        wipe()
        assertEquals(VaultState.NoVault, session.state.value)

        backups.dismiss()
        assertTrue(backups.restore(FileBackupDir(drive), startService = false))
        assertEquals(BackupOutcome.Restored, await())
        assertEquals(VaultState.Locked, session.state.value)
        assertFalse("PIN must be set up again", session.hasPin())

        assertEquals(UnlockResult.Success, runBlocking { session.unlockWithPassphrase(passphrase.toCharArray()) })
        val restored = readAll()
        assertEquals(original.keys, restored.keys)
        for ((name, bytes) in original) assertArrayEquals(name, bytes, restored[name])
        val vault = session.openVault!!
        runBlocking {
            for (f in vault.database.files().all().filter { it.name.endsWith(".jpg") }) assertNotNull(vault.thumbnails.thumbnail(f))
        }
    }

    @Test
    fun restoreNeverOverwritesAVault() {
        fillVault()
        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        await()
        backups.dismiss()
        assertFalse(backups.restore(FileBackupDir(drive), startService = false))
    }

    @Test
    fun anotherVaultsBackupIsOnlyReplacedAfterAsking() {
        fillVault()
        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        await()

        wipe()
        runBlocking { session.create("quartz meadow harbor signal ember".toCharArray()).wipe() }
        backups.dismiss()
        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        assertEquals(BackupOutcome.OtherVaultThere, await())

        backups.dismiss()
        assertTrue(backups.backup(FileBackupDir(drive), replaceOtherVault = true, startService = false))
        val outcome = await() as BackupOutcome.BackedUp
        assertTrue(outcome.verify.ok)
        assertEquals(5, outcome.result.removed) // the old vault's 3 blobs and 2 thumbnails
    }

    @Test
    fun aBrokenBackupLeavesNothingBehind() {
        fillVault()
        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        await()
        File(drive, "${BackupEngine.FOLDER}/${BackupEngine.MANIFEST}").delete() // interrupted
        wipe()
        backups.dismiss()
        assertTrue(backups.restore(FileBackupDir(drive), startService = false))
        assertTrue(await() is BackupOutcome.Failed)
        assertEquals(VaultState.NoVault, session.state.value)
        assertFalse(File(context.filesDir, "vault.restore").exists())
        assertFalse(entry.store().exists())
    }
}
