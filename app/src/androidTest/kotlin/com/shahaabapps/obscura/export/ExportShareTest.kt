package com.shahaabapps.obscura.export

import android.content.Context
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.KeepVaultOpen
import com.shahaabapps.obscura.backup.FileBackupDir
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Rule

/** Saving decrypted copies, and one-time share links read through a real ContentResolver. */
@RunWith(AndroidJUnit4::class)
class ExportShareTest {


    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val folder = File(context.cacheDir, "export-test")

    private fun wipe() = instrumentation.runOnMainSync {
        entry.exportManager().reset()
        entry.shareGrants().revokeAll()
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before
    fun setUp() {
        wipe()
        folder.deleteRecursively()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
    }

    @After
    fun tearDown() {
        wipe()
        folder.deleteRecursively()
    }

    private fun seal(name: String, bytes: ByteArray, mime: String = "application/octet-stream"): VaultFile = runBlocking {
        entry.session().openVault!!.files.put(bytes.inputStream(), IncomingFile(name, mime, 1_700_000_000_000))
    }

    private fun awaitExport(): ExportProgress {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val p = entry.exportManager().progress.value
            if (p.finished && !p.running) return p
            Thread.sleep(50)
        }
        throw AssertionError("Export did not finish: ${entry.exportManager().progress.value}")
    }

    @Test
    fun exportWritesDecryptedCopiesWithoutOverwriting() {
        val big = Random(1).nextBytes(3 * 1024 * 1024 + 17) // spans several 1 MiB segments
        val files = listOf(seal("clip.bin", big), seal("notes.txt", "meet at noon".toByteArray(), "text/plain"), seal("notes.txt", "second".toByteArray(), "text/plain"))
        folder.mkdirs()
        File(folder, "clip.bin").writeText("already here")

        assertTrue(entry.exportManager().exportToFolder(FileBackupDir(folder), files, startService = false))
        val result = awaitExport()
        assertEquals(3, result.exported)
        assertEquals(0, result.failed)
        assertEquals("already here", File(folder, "clip.bin").readText())
        assertArrayEquals(big, File(folder, "clip (2).bin").readBytes())
        assertEquals("meet at noon", File(folder, "notes.txt").readText())
        assertEquals("second", File(folder, "notes (2).txt").readText())
        // The originals are still sealed in the vault.
        assertEquals(3, runBlocking { entry.session().openVault!!.database.files().all().size })
    }

    @Test
    fun aShareLinkStreamsTheFileAndSeeks() {
        val bytes = Random(2).nextBytes(2 * 1024 * 1024 + 333)
        val file = seal("beach.jpg", bytes, "image/jpeg")
        val uri = entry.shareGrants().grant(listOf(file)).single()
        val resolver = context.contentResolver

        assertEquals("image/jpeg", resolver.getType(uri))
        resolver.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("beach.jpg", c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        resolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }

        // Random access, as photo and video apps do.
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            assertEquals(bytes.size.toLong(), pfd.statSize)
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val at = 1_500_000L
                val buffer = ByteBuffer.allocate(4096)
                ch.position(at)
                while (buffer.hasRemaining() && ch.read(buffer) > 0) Unit
                assertArrayEquals(bytes.copyOfRange(at.toInt(), at.toInt() + 4096), buffer.array())
            }
        }
    }

    @Test
    fun linksAreReadOnlyAndDieWhenTheVaultLocks() {
        val file = seal("secret.txt", "top secret".toByteArray(), "text/plain")
        val uri = entry.shareGrants().grant(listOf(file)).single()
        try {
            context.contentResolver.openOutputStream(uri)!!.close()
            fail("a share link must not be writable")
        } catch (expected: Exception) {
        }

        instrumentation.runOnMainSync { entry.session().lock() }
        instrumentation.waitForIdleSync()
        try {
            context.contentResolver.openInputStream(uri)!!.close()
            fail("a share link must stop working once the vault locks")
        } catch (expected: java.io.FileNotFoundException) {
        }
    }

    @Test
    fun anOpenReaderCantReadAgainAfterLockAndUnlock() {
        val bytes = Random(3).nextBytes(256 * 1024)
        val file = seal("video.mp4", bytes, "video/mp4")
        val uri = entry.shareGrants().grant(listOf(file)).single()
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val first = ByteBuffer.allocate(1024)
                ch.read(first)
                assertArrayEquals(bytes.copyOf(1024), first.array())

                // Lock, then open the same vault again: the old reader stays dead.
                instrumentation.runOnMainSync { entry.session().lock() }
                instrumentation.waitForIdleSync()
                assertEquals(com.shahaabapps.obscura.vault.UnlockResult.Success, runBlocking { entry.session().unlockWithPassphrase("lantern orbit velvet canyon mosaic".toCharArray()) })
                try {
                    ch.position(100_000)
                    ch.read(ByteBuffer.allocate(1024))
                    fail("a reader opened before the lock must not read after it")
                } catch (expected: java.io.IOException) {
                }
            }
        }
    }

    @Test
    fun lockingStopsAnExportMidway() {
        val big = Random(4).nextBytes(48 * 1024 * 1024)
        val file = seal("long.mov", big, "video/quicktime")
        folder.mkdirs()
        assertTrue(entry.exportManager().exportToFolder(FileBackupDir(folder), listOf(file), startService = false))
        instrumentation.runOnMainSync { entry.session().lock() }
        val result = awaitExport()
        assertTrue("stopped: $result", result.cancelled)
        assertEquals(0, result.exported)
        assertTrue("no partial copy left", File(folder, "long.mov").let { !it.exists() || it.length() < big.size })
        assertTrue("partial copy removed", !File(folder, "long.mov").exists())
    }

    @Test
    fun unknownOrForgedLinksOpenNothing() {
        seal("a.txt", "a".toByteArray(), "text/plain")
        val forged = android.net.Uri.parse("content://${ShareGrants.AUTHORITY}/00112233445566778899aabbccddeeff/a.txt")
        try {
            context.contentResolver.openInputStream(forged)!!.close()
            fail("opened a forged link")
        } catch (expected: java.io.FileNotFoundException) {
        }
    }

    @Test
    fun expiredLinksAreRefused() {
        val file = seal("old.txt", "old".toByteArray(), "text/plain")
        val past = System.currentTimeMillis() - ShareGrants.LIFETIME_MS - 1_000
        val uri = entry.shareGrants().grant(listOf(file), now = past).single()
        assertEquals(null, entry.shareGrants().lookup(uri.pathSegments[0]))
    }
}
