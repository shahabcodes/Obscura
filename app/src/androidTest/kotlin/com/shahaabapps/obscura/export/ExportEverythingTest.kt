package com.shahaabapps.obscura.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.backup.FileBackupDir
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
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
 * Exporting the whole vault into a folder: the one place where everything leaves at once.
 *
 * The cases that matter are the ones a 200 GB run will actually hit — being stopped partway and
 * picked up again, a vault far larger than one page of rows, and a drive that fills or vanishes.
 */
@RunWith(AndroidJUnit4::class)
class ExportEverythingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private lateinit var drive: File

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        entry.session().reload()
    }

    @Before
    fun setUp() {
        wipe()
        drive = File(context.cacheDir, "export-all").apply { deleteRecursively(); mkdirs() }
        runBlocking { entry.session().create(passphrase.toCharArray()) }
    }

    @After
    fun tearDown() {
        entry.exportManager().cancel()
        drive.deleteRecursively()
        wipe()
    }

    private fun millis(y: Int, m: Int, d: Int) =
        LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seal(name: String, bytes: ByteArray, takenAt: Long) = runBlocking {
        entry.session().openVault!!.files.put(
            bytes.inputStream(),
            IncomingFile(name, "application/octet-stream", takenAt),
        )
    }

    private fun awaitExport(timeoutMs: Long = 180_000): ExportProgress {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val progress = entry.exportManager().progress.value
            if (progress.finished && !progress.running) return progress
            Thread.sleep(25)
        }
        throw AssertionError("the export did not finish: ${entry.exportManager().progress.value}")
    }

    /** Every file in the vault lands in a folder named for its month, byte for byte. */
    @Test
    fun everythingLandsInAFolderPerMonth() {
        val written = mapOf(
            "one.bin" to Triple(Random(1).nextBytes(120_000), 2026, 10),
            "two.bin" to Triple(Random(2).nextBytes(90_000), 2026, 10),
            "three.bin" to Triple(Random(3).nextBytes(2 * 1024 * 1024 + 11), 2025, 3),
            "four.bin" to Triple(Random(4).nextBytes(400), 2024, 12),
        )
        written.forEach { (name, it) -> seal(name, it.first, millis(it.second, it.third, 5)) }

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val result = awaitExport()

        assertEquals(4, result.exported)
        assertEquals(0, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(setOf("2024-12", "2025-03", "2026-10"), drive.listFiles()!!.map { it.name }.toSet())
        written.forEach { (name, it) ->
            val month = "%04d-%02d".format(it.second, it.third)
            val copy = File(File(drive, month), name)
            assertTrue("$name is missing from $month", copy.isFile)
            assertArrayEquals("$name came back changed", it.first, copy.readBytes())
        }
        // Exporting is a copy: everything is still sealed.
        assertEquals(4, runBlocking { entry.session().openVault!!.database.files().all().size })
    }

    /**
     * The case a long run depends on: stopped partway, then run again. What was already copied
     * must be left alone rather than copied a second time under another name.
     */
    @Test
    fun runningItAgainCarriesOnRatherThanStartingOver() {
        repeat(12) { i -> seal("file$i.bin", Random(i).nextBytes(300_000), millis(2026, 1 + i % 3, 4)) }

        // Stop it as soon as a few files are through.
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 60_000
        while (entry.exportManager().progress.value.done < 3 && System.currentTimeMillis() < until) {
            Thread.sleep(10)
        }
        entry.exportManager().cancel()
        awaitExport()
        // Whether the cancel landed before the last file or just after it does not matter here;
        // what matters is that running it again finishes the job without copying anything twice.
        val copiedFirstTime = drive.walkTopDown().count { it.isFile }
        assertTrue("nothing was copied at all", copiedFirstTime >= 1)

        // Again, into the same folder.
        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val second = awaitExport()

        assertEquals("every file is accounted for", 12, second.done)
        assertTrue(
            "the ones already there should have been skipped: ${second.skipped} of $copiedFirstTime",
            second.skipped >= copiedFirstTime - 1,
        )
        assertEquals(0, second.failed)
        // Twelve files, no second copies: nothing named "file0 (2).bin".
        val names = drive.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals("one copy of each", 12, names.size)
        assertTrue("a duplicate was made: $names", names.none { it.contains("(2)") })
    }

    /** A vault larger than one page of rows is walked all the way through. */
    @Test
    fun aVaultBiggerThanOnePageIsFullyExported() {
        val many = 520 // more than the 500-row page the export walks in
        repeat(many) { i -> seal("f$i.bin", ByteArray(64) { (i + it).toByte() }, millis(2026, 7, 1)) }

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val result = awaitExport()

        assertEquals(many, result.exported)
        assertEquals(many, drive.walkTopDown().count { it.isFile })
    }

    /**
     * What decides whether a 200 GB vault can be exported at all: memory must not grow with the
     * number of files. The rows are walked a page at a time and each file is streamed, so a
     * vault of any size should cost about the same as a small one.
     */
    @Test
    fun memoryDoesNotGrowWithTheNumberOfFiles() {
        val many = 1_200
        repeat(many) { i -> seal("m$i.bin", ByteArray(8 * 1024) { (i + it).toByte() }, millis(2026, 1 + i % 12, 3)) }

        val runtime = Runtime.getRuntime()
        fun used(): Long {
            Runtime.getRuntime().gc()
            Thread.sleep(40)
            return runtime.totalMemory() - runtime.freeMemory()
        }
        val before = used()

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until) {
            val progress = entry.exportManager().progress.value
            if (progress.finished && !progress.running) break
            Thread.sleep(20)
        }
        val result = awaitExport()
        val after = used()

        assertEquals(many, result.exported)
        assertEquals(many, drive.walkTopDown().count { it.isFile })
        // Twelve month folders, so no single directory holds the lot.
        assertEquals(12, drive.listFiles()!!.count { it.isDirectory })
        // What matters is what is still held once the run is over: a peak only says when the
        // collector happened to run, which depends on whatever else the device was doing.
        val grew = (after - before) / (1024 * 1024)
        assertTrue("held on to ${grew}MB after exporting $many files", grew < 24)
    }

    /** Locking the vault stops it at once and leaves no half-written copy behind. */
    @Test
    fun lockingStopsItAndLeavesNoPartFile() {
        repeat(6) { i -> seal("big$i.bin", Random(i).nextBytes(4 * 1024 * 1024), millis(2026, 5, 2)) }

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 60_000
        while (entry.exportManager().progress.value.bytesDone < 1_000_000 && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        instrumentation.runOnMainSync { entry.session().lock() }
        val result = awaitExport()

        assertTrue("it should have stopped", result.cancelled)
        assertTrue("the vault is locked", entry.session().openVault == null)
        // Whatever was finished is whole; nothing is left half-written.
        val copies = drive.walkTopDown().filter { it.isFile }.toList()
        assertTrue("got ${copies.size} copies", copies.size <= 6)
        copies.forEach { file ->
            assertEquals("${file.name} is a part file", 4 * 1024 * 1024L, file.length())
        }
    }

    /** A folder that cannot be written reports failures rather than crashing. */
    @Test
    fun aDestinationThatCannotBeWrittenIsReported() {
        seal("one.bin", Random(9).nextBytes(5_000), millis(2026, 2, 2))
        val blocked = File(drive, "blocked")
        blocked.mkdirs()
        blocked.setWritable(false)

        val started = entry.exportManager().exportEverything(FileBackupDir(blocked), startService = false)
        if (started) {
            val result = awaitExport()
            assertTrue("it should report the trouble", result.failed > 0 || result.error != null)
        }
        blocked.setWritable(true)
        // The vault is untouched either way.
        assertFalse(entry.session().openVault == null)
        assertEquals(1, runBlocking { entry.session().openVault!!.database.files().all().size })
    }
}
