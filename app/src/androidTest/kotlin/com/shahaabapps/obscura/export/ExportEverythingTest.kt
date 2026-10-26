package com.shahaabapps.obscura.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.KeepVaultOpen
import com.shahaabapps.obscura.backup.BackupDir
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
import org.junit.Rule
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

    /** The vault stays open while this runs, as it does in the app during long work. */
    @get:Rule
    val keepOpen = KeepVaultOpen()


    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private lateinit var drive: File

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
        drive = File(context.cacheDir, "export-all").apply { deleteRecursively(); mkdirs() }
        runBlocking { entry.session().create(passphrase.toCharArray()) }
    }

    @After
    fun tearDown() {
        entry.exportManager().cancel()
        drive.deleteRecursively()
        wipe()
    }

    /** The folder the export makes for itself inside the one that was picked. */
    private val exported get() = File(drive, ExportManager.FOLDER)

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
        assertEquals(setOf("2024-12", "2025-03", "2026-10"), exported.listFiles()!!.map { it.name }.toSet())
        written.forEach { (name, it) ->
            val month = "%04d-%02d".format(it.second, it.third)
            val copy = File(File(exported, month), name)
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
        val copiedFirstTime = exported.walkTopDown().count { it.isFile }
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
        val names = exported.walkTopDown().filter { it.isFile }.map { it.name }.toList()
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
        assertEquals(many, exported.walkTopDown().count { it.isFile })
    }

    /**
     * What decides whether a 200 GB vault can be exported at all: memory must not grow with the
     * number of files. The rows are walked a page at a time and each file streamed, so exporting
     * many should cost little more than exporting a few.
     *
     * Both halves are measured in this same process, because the figure that matters is the
     * difference between them. An absolute number would mostly reflect whatever else the suite
     * had left on the heap.
     */
    @Test
    fun memoryDoesNotGrowWithTheNumberOfFiles() {
        val runtime = Runtime.getRuntime()
        fun settledUse(): Long {
            repeat(3) {
                runtime.gc()
                Thread.sleep(80)
            }
            return runtime.totalMemory() - runtime.freeMemory()
        }

        fun exportInto(folder: File): Long {
            val before = settledUse()
            assertTrue(entry.exportManager().exportEverything(FileBackupDir(folder), startService = false))
            awaitExport()
            return settledUse() - before
        }

        // A small vault first, to see what one export costs at all.
        repeat(80) { i -> seal("s$i.bin", ByteArray(8 * 1024) { (i + it).toByte() }, millis(2026, 1 + i % 12, 3)) }
        val small = exportInto(File(drive, "small").apply { mkdirs() })
        val afterSmall = runBlocking { entry.session().openVault!!.database.files().all().size }
        assertEquals(80, afterSmall)

        // Then fifteen times as many files, which should not cost fifteen times the memory.
        entry.exportManager().reset()
        repeat(1_120) { i -> seal("m$i.bin", ByteArray(8 * 1024) { (i + it).toByte() }, millis(2026, 1 + i % 12, 4)) }
        val large = exportInto(File(drive, "large").apply { mkdirs() })

        val many = File(File(drive, "large"), ExportManager.FOLDER).walkTopDown().count { it.isFile }
        assertEquals("every file was exported", 1_200, many)
        // Twelve month folders, so no single directory holds the lot.
        assertEquals(12, File(File(drive, "large"), ExportManager.FOLDER).listFiles()!!.count { it.isDirectory })

        val grewBy = (large - small) / (1024 * 1024)
        assertTrue(
            "fifteen times the files cost ${grewBy}MB more (small ${small / (1024 * 1024)}MB, " +
                "large ${large / (1024 * 1024)}MB)",
            grewBy < 32,
        )
    }

    /**
     * Two different files can easily share a name and a size — the same photo from two cameras,
     * or a file and a re-saved copy of it. Both must come out. Treating the second as "already
     * there" because the first was just written would lose it silently, which is the worst way
     * for an export to fail: the user is told it worked.
     */
    @Test
    fun twoDifferentFilesWithTheSameNameAndSizeBothComeOut() {
        val first = Random(11).nextBytes(64_000)
        val second = Random(22).nextBytes(64_000) // same length, different content
        seal("IMG_0001.jpg", first, millis(2026, 6, 4))
        seal("IMG_0001.jpg", second, millis(2026, 6, 9))

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val result = awaitExport()

        assertEquals("both should have been copied", 2, result.exported)
        assertEquals("neither was already there", 0, result.skipped)
        val copies = File(exported, "2026-06").listFiles()!!.sortedBy { it.name }
        assertEquals("two files, under two names", 2, copies.size)
        val contents = copies.map { it.readBytes().toList() }
        assertTrue("the first file is missing", contents.contains(first.toList()))
        assertTrue("the second file is missing", contents.contains(second.toList()))
    }

    /** A file already in the folder from an earlier run is still skipped, which is the point. */
    @Test
    fun aWholeCopyFromAnEarlierRunIsStillLeftAlone() {
        val bytes = Random(33).nextBytes(40_000)
        seal("holiday.bin", bytes, millis(2026, 4, 2))

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        assertEquals(1, awaitExport().exported)
        val copy = File(File(exported, "2026-04"), "holiday.bin")
        val stamp = copy.lastModified()

        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val second = awaitExport()

        assertEquals("it should have been left alone", 1, second.skipped)
        assertEquals(0, second.exported)
        assertEquals("only the one copy", 1, File(exported, "2026-04").listFiles()!!.size)
        assertEquals("the copy was rewritten", stamp, copy.lastModified())
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
        val copies = exported.walkTopDown().filter { it.isFile }.toList()
        assertTrue("got ${copies.size} copies", copies.size <= 6)
        copies.forEach { file ->
            assertEquals("${file.name} is a part file", 4 * 1024 * 1024L, file.length())
        }
    }

    /**
     * A destination without the room must say so before anything is copied, rather than failing
     * file after file for hours. What is already there counts towards it, so carrying on into a
     * part-finished folder is measured by what is left rather than by the whole vault.
     */
    @Test
    fun aDestinationWithoutTheRoomIsRefusedUpFront() {
        repeat(4) { i -> seal("big$i.bin", Random(i).nextBytes(512 * 1024), millis(2026, 3, 2)) }
        val tiny = object : BackupDir by FileBackupDir(drive) {
            override fun availableBytes(): Long = 64 * 1024 // nowhere near enough
        }

        assertTrue(entry.exportManager().exportEverything(tiny, startService = false))
        val result = awaitExport()

        assertEquals("nothing should have been copied", 0, result.exported)
        assertTrue("it should say what is wrong: $result", result.error != null)
        assertTrue("it should say how much more is needed", result.shortBy > 0)
        assertEquals("nothing was written", 0, exported.walkTopDown().count { it.isFile })
    }

    /** With room to spare, the check lets it through. */
    @Test
    fun aDestinationWithRoomIsNotRefused() {
        repeat(3) { i -> seal("ok$i.bin", Random(i).nextBytes(100_000), millis(2026, 3, 2)) }
        val roomy = object : BackupDir by FileBackupDir(drive) {
            override fun availableBytes(): Long = 500L * 1024 * 1024
        }

        assertTrue(entry.exportManager().exportEverything(roomy, startService = false))
        val result = awaitExport()

        assertEquals(3, result.exported)
        assertEquals(0, result.shortBy)
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
