package com.shahaabapps.obscura.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.importer.ImportOptions
import com.shahaabapps.obscura.importer.SourceResolver
import com.shahaabapps.obscura.testing.LocalFilesProvider
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole way out and back: files taken in from a folder the user picked, and then written
 * back into that same folder.
 *
 * This goes through the Storage Access Framework at both ends, as the app really does, rather
 * than standing a plain `java.io.File` in for the folder — the point being that what comes back
 * out is byte for byte what went in.
 */
@RunWith(AndroidJUnit4::class)
class FolderRoundTripTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private lateinit var folder: File

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        // Closing the index leaves work queued on the main thread. It has to run before the
        // files it points at are deleted, or Room reopens a vault that is no longer there and
        // brings the whole process down with it.
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        wipe()
        folder = File(context.cacheDir, "round-trip").apply {
            deleteRecursively()
            mkdirs()
        }
        LocalFilesProvider.root = folder
    }

    @After
    fun tearDown() {
        LocalFilesProvider.root = null
        folder.deleteRecursively()
        wipe()
    }

    private fun tree() = LocalFilesProvider.treeUri(context.packageName)

    private fun awaitImport(timeoutMs: Long = 120_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val progress = entry.importManager().progress.value
            if (progress.finished && !progress.running) return
            Thread.sleep(50)
        }
        throw AssertionError("the import did not finish: ${entry.importManager().progress.value}")
    }

    private fun awaitExport(timeoutMs: Long = 120_000): ExportProgress {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val progress = entry.exportManager().progress.value
            if (progress.finished && !progress.running) return progress
            Thread.sleep(50)
        }
        throw AssertionError("the export did not finish: ${entry.exportManager().progress.value}")
    }

    @Test
    fun filesComeBackOutOfTheVaultByteForByte() {
        // A spread of sizes, including one that spans several encryption segments.
        val written = mapOf(
            "harbour.jpg" to Random(1).nextBytes(400_000),
            "notes.txt" to "meet at noon".toByteArray(),
            "clip.mp4" to Random(2).nextBytes(3 * 1024 * 1024 + 17),
            "tiny.bin" to ByteArray(1) { 7 },
        )
        written.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }

        runBlocking { entry.session().create(passphrase.toCharArray()) }

        // In, through the folder the picker would have handed over, originals removed.
        val sources = SourceResolver.fromTree(context, tree())
        assertEquals("every file in the folder was offered", written.size, sources.size)
        assertTrue(entry.importManager().start(sources, ImportOptions(), startService = false))
        awaitImport()
        // The app offers to remove the originals once everything is sealed; take it up.
        val (removed, _) = runBlocking { entry.importManager().deleteOriginals() }
        assertEquals("every original was removed", written.size, removed)

        val vault = entry.session().openVault!!
        val sealed = runBlocking { vault.database.files().all() }
        assertEquals(written.size, sealed.size)
        // The folder really is empty now, which is what makes getting them back out matter.
        assertEquals(emptyList<String>(), folder.listFiles()!!.map { it.name }.sorted())

        // And back out, into that same folder.
        assertTrue(entry.exportManager().exportToFolder(tree(), sealed, startService = false))
        val result = awaitExport()
        assertEquals("every file came back", written.size, result.exported)
        assertEquals(0, result.failed)

        val returned = folder.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals("the same names came back", written.keys.sorted(), returned.keys.sorted())
        written.forEach { (name, bytes) ->
            assertArrayEquals("$name came back changed", bytes, returned.getValue(name))
        }
        // Exporting is a copy: the vault still holds everything.
        assertEquals(written.size, runBlocking { vault.database.files().all().size })
    }

    /** Exporting twice into the same folder must not quietly write over the first copies. */
    @Test
    fun exportingAgainKeepsWhatIsAlreadyThere() {
        val original = Random(3).nextBytes(120_000)
        File(folder, "beach.jpg").writeBytes(original)

        runBlocking { entry.session().create(passphrase.toCharArray()) }
        entry.importManager().start(SourceResolver.fromTree(context, tree()), ImportOptions(), startService = false)
        awaitImport()
        val sealed = runBlocking { entry.session().openVault!!.database.files().all() }

        assertTrue(entry.exportManager().exportToFolder(tree(), sealed, startService = false))
        assertEquals(1, awaitExport().exported)

        val names = folder.listFiles()!!.map { it.name }.sorted()
        assertEquals("the first copy is untouched and the new one sits beside it", 2, names.size)
        assertArrayEquals("the original was overwritten", original, File(folder, "beach.jpg").readBytes())
        val copy = folder.listFiles()!!.single { it.name != "beach.jpg" }
        assertArrayEquals("the exported copy differs from what was sealed", original, copy.readBytes())
    }

    /** A folder that has gone away mid-export is reported, not crashed on. */
    @Test
    fun exportingIntoAFolderThatVanishedFails() {
        File(folder, "one.txt").writeText("one")
        runBlocking { entry.session().create(passphrase.toCharArray()) }
        entry.importManager().start(SourceResolver.fromTree(context, tree()), ImportOptions(), startService = false)
        awaitImport()
        val sealed = runBlocking { entry.session().openVault!!.database.files().all() }

        // The drive is pulled out, as far as the provider is concerned.
        LocalFilesProvider.root = null

        val started = entry.exportManager().exportToFolder(tree(), sealed, startService = false)
        if (started) {
            val result = awaitExport()
            assertEquals("nothing can have been written", 0, result.exported)
            assertTrue("it should say it failed", result.failed > 0 || result.error != null)
        }
        // Either way the vault is untouched and still open.
        assertFalse(entry.session().openVault == null)
        assertEquals(1, runBlocking { entry.session().openVault!!.database.files().all().size })
    }
}
