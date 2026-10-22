package com.shahaabapps.obscura.backup

import com.shahaabapps.obscura.crypto.CorruptVaultException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** File-level behaviour on plain folders; the on-device test covers real vaults end to end. */
class BackupEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun header(): ByteArray =
        com.shahaabapps.obscura.crypto.Vault.create("x".toCharArray(), com.shahaabapps.obscura.crypto.KdfParams(1024, 1, 1)).header.toBytes()

    private fun vault(): File = temp.newFolder("vault").apply {
        File(this, "vault.hdr").writeBytes(header())
        File(this, "index.db").writeBytes(ByteArray(4096) { 7 })
        File(this, "pin.bin").writeBytes(byteArrayOf(1, 2, 3))
        blob(this, "ab12", 1000)
        blob(this, "ab34", 2000)
        blob(this, "cd56", 3000)
        File(this, "thumbs/ab").mkdirs()
        File(this, "thumbs/ab/ab12").writeBytes(ByteArray(300))
    }

    private fun blob(vault: File, id: String, size: Int) {
        File(vault, "blobs/${id.take(2)}").mkdirs()
        File(vault, "blobs/${id.take(2)}/$id").writeBytes(ByteArray(size) { (it + size).toByte() })
    }

    @Test
    fun fullBackupThenRestoreIsIdentical() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        var checkpointed = false
        val result = BackupEngine(v).backup(dest, { checkpointed = true }) { }
        assertTrue(checkpointed)
        assertEquals(4, result.copied)

        val backupRoot = File(temp.root, "usb/Obscura Backup")
        assertTrue(File(backupRoot, "obscura-backup.json").isFile)
        assertFalse("device-bound PIN data is not backed up", File(backupRoot, "pin.bin").exists())

        val restored = File(temp.root, "restored")
        BackupEngine(v).restore(dest, restored) { }
        for (path in listOf("vault.hdr", "index.db", "blobs/ab/ab12", "blobs/ab/ab34", "blobs/cd/cd56", "thumbs/ab/ab12")) {
            assertArrayEquals(path, File(v, path).readBytes(), File(restored, path).readBytes())
        }
        assertFalse(File(restored, "pin.bin").exists())
    }

    @Test
    fun updatingCopiesOnlyNewFilesAndRemovesShreddedOnes() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        BackupEngine(v).backup(dest, {}) { }

        File(v, "blobs/cd/cd56").delete() // shredded
        blob(v, "ef78", 500) // newly imported
        val result = BackupEngine(v).backup(dest, {}) { }
        assertEquals(1, result.copied)
        assertEquals(3, result.unchanged)
        assertEquals(1, result.removed)
        assertFalse(File(temp.root, "usb/Obscura Backup/blobs/cd/cd56").exists())
        assertTrue(File(temp.root, "usb/Obscura Backup/blobs/ef/ef78").exists())
        assertTrue(BackupEngine(v).verify(dest).ok)
    }

    @Test
    fun aHalfCopiedFileIsCopiedAgain() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        BackupEngine(v).backup(dest, {}) { }
        File(temp.root, "usb/Obscura Backup/blobs/ab/ab34").writeBytes(ByteArray(10)) // interrupted copy
        assertFalse(BackupEngine(v).verify(dest).ok)
        assertEquals(1, BackupEngine(v).backup(dest, {}) { }.copied)
        assertTrue(BackupEngine(v).verify(dest).ok)
    }

    @Test
    fun verifySpotsMissingFiles() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        BackupEngine(v).backup(dest, {}) { }
        File(temp.root, "usb/Obscura Backup/blobs/ab/ab12").delete()
        val result = BackupEngine(v).verify(dest)
        assertEquals(1, result.missing)
        assertFalse(result.ok)
    }

    @Test(expected = CorruptVaultException::class)
    fun anInterruptedBackupCannotBeRestored() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        BackupEngine(v).backup(dest, {}) { }
        File(temp.root, "usb/Obscura Backup/obscura-backup.json").delete()
        BackupEngine(v).restore(dest, File(temp.root, "restored")) { }
    }

    @Test(expected = CorruptVaultException::class)
    fun aRandomFolderIsNotABackup() {
        val other = FileBackupDir(temp.newFolder("random")).also { it.write("photo.jpg").use { o -> o.write(1) } }
        BackupEngine(temp.newFolder("vault")).restore(other, File(temp.root, "restored")) { }
    }

    @Test
    fun recognisesABackupOfAnotherVault() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        val engine = BackupEngine(v)
        assertFalse("empty drive", engine.holdsOtherVault(dest))
        engine.backup(dest, {}) { }
        assertFalse("our own backup", engine.holdsOtherVault(dest))
        File(v, "vault.hdr").writeBytes(header()) // the vault was recreated
        assertTrue(engine.holdsOtherVault(dest))
    }

    @Test
    fun restoreAcceptsTheBackupFolderOrItsParent() {
        val v = vault()
        val dest = FileBackupDir(temp.newFolder("usb"))
        BackupEngine(v).backup(dest, {}) { }
        BackupEngine(v).restore(FileBackupDir(File(temp.root, "usb/Obscura Backup")), File(temp.root, "r1")) { }
        assertTrue(File(temp.root, "r1/vault.hdr").isFile)
    }
}
