package com.shahaabapps.obscura.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.KeepVaultOpen
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resume rule under attack: a file is skipped when a file of the same name AND the same size
 * is already in that month's folder. These are the cases where that rule can be made to lose a
 * file or to leave a wrong one in place.
 *
 * A run that was killed outright — the system stopping the process, `am force-stop`, the battery
 * going — gets no chance to tidy up, so the destination is left exactly as it stood mid-run. That
 * state is set up here directly rather than by racing a cancel, so the cases are deterministic.
 */
@RunWith(AndroidJUnit4::class)
class ExportResumeEdgesTest {

    @get:Rule
    val keepOpen = KeepVaultOpen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private lateinit var drive: File

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
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
        drive = File(context.cacheDir, "export-resume").apply { deleteRecursively(); mkdirs() }
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

    private fun exportAndWait(): ExportProgress {
        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        return awaitExport()
    }

    /** The folder the export makes for itself inside the one that was picked. */
    private val exported get() = File(drive, ExportManager.FOLDER)

    private fun monthDir(month: String) = File(exported, month)

    private fun contentsOf(month: String): List<List<Byte>> =
        monthDir(month).listFiles().orEmpty().filter { it.isFile }.map { it.readBytes().toList() }

    /**
     * The one that matters most. Two different files share a name and a size. A first run copies
     * the first of them and is then killed before it reaches the second. Carrying on must notice
     * that only one of the two copies is there and write the other.
     *
     * The destination entry that was matched has already stood in for the first file, so it must
     * not stand in for the second as well.
     */
    @Test
    fun carryingOnStillCopiesTheSecondOfTwoFilesSharingANameAndSize() {
        val first = Random(11).nextBytes(64_000)
        val second = Random(22).nextBytes(64_000) // same length, different content
        seal("IMG_0001.jpg", first, millis(2026, 6, 4))
        seal("IMG_0001.jpg", second, millis(2026, 6, 9))

        // A complete run, to learn the two names it uses.
        assertEquals(2, exportAndWait().exported)
        val month = monthDir("2026-06")
        val names = month.listFiles()!!.map { it.name }.sorted()
        assertEquals("two copies, under two names: $names", 2, names.size)

        // Now stand the destination where a killed run would have left it: the first copy
        // written, the second never reached.
        val secondCopy = month.listFiles()!!.first { it.name.contains("(2)") }
        val lost = secondCopy.readBytes().toList()
        assertTrue(secondCopy.delete())
        assertEquals(1, month.listFiles()!!.size)

        // Carry on.
        val resumed = exportAndWait()

        assertEquals("both files must be accounted for", 2, resumed.done)
        assertEquals(0, resumed.failed)
        val after = month.listFiles()!!.filter { it.isFile }
        assertEquals(
            "carrying on left only ${after.map { it.name }} — a file was lost " +
                "(skipped=${resumed.skipped}, exported=${resumed.exported})",
            2,
            after.size,
        )
        val got = contentsOf("2026-06")
        assertTrue("the first file is missing", got.contains(first.toList()))
        assertTrue("the file the killed run never reached is missing", got.contains(lost))
    }

    /**
     * Something unrelated is already at the destination under a name a vault file wants, and by
     * coincidence it is the same size. The vault file must still come out: the export is not
     * allowed to decide a stranger's file is its own work and quietly drop one of the user's.
     */
    @Test
    fun anUnrelatedFileOfTheSameNameAndSizeDoesNotSwallowAVaultFile() {
        val mine = Random(44).nextBytes(10_000)
        seal("notes.bin", mine, millis(2026, 3, 7))

        // Somewhere of the user's own, in the folder they picked rather than the one Obscura
        // makes for itself — which is the realistic case: an export into Downloads.
        val month = File(drive, "2026-03").apply { mkdirs() }
        val stranger = Random(55).nextBytes(10_000) // same size, nothing to do with the vault
        File(month, "notes.bin").writeBytes(stranger)

        val result = exportAndWait()

        assertEquals(1, result.done)
        assertEquals(0, result.failed)
        val got = contentsOf("2026-03")
        assertTrue(
            "the vault's notes.bin never came out — the export counted a stranger's file as " +
                "its own (skipped=${result.skipped}, exported=${result.exported}, " +
                "files=${monthDir("2026-03").listFiles().orEmpty().map { "${it.name}:${it.length()}" }})",
            got.contains(mine.toList()),
        )
        assertEquals(
            "the file that was already there was touched",
            stranger.toList(),
            File(month, "notes.bin").readBytes().toList(),
        )
    }

    /**
     * A truncated copy left by a run that was killed outright. The full file must come out, and
     * the half-written one must not be left sitting at the destination under a real file name,
     * where it is a plaintext fragment of the vault that looks like the finished article.
     */
    @Test
    fun aTruncatedLeftoverIsNotMistakenForAFinishedCopy() {
        val whole = Random(66).nextBytes(200_000)
        seal("clip.bin", whole, millis(2026, 8, 12))

        val month = File(drive, "2026-08").apply { mkdirs() }
        File(month, "clip.bin").writeBytes(whole.copyOf(50_000)) // killed part way through

        val result = exportAndWait()

        assertEquals(0, result.failed)
        val got = contentsOf("2026-08")
        assertTrue("the whole file never came out (skipped=${result.skipped})", got.contains(whole.toList()))
        val files = month.listFiles()!!.filter { it.isFile }
        assertEquals(
            "a truncated plaintext fragment was left behind as well: " +
                files.map { "${it.name}:${it.length()}" },
            1,
            files.size,
        )
    }

    /**
     * Two files sharing a name and a size, where the destination already holds both copies from a
     * finished run. Nothing may be copied again and nothing may be duplicated.
     */
    @Test
    fun aFinishedRunOfTwoFilesSharingANameIsLeftAloneOnARerun() {
        seal("same.bin", Random(77).nextBytes(32_000), millis(2026, 9, 1))
        seal("same.bin", Random(88).nextBytes(32_000), millis(2026, 9, 2))

        assertEquals(2, exportAndWait().exported)
        val month = monthDir("2026-09")
        val before = month.listFiles()!!.associate { it.name to it.length() }

        val second = exportAndWait()

        assertEquals("nothing should have been copied again", 0, second.exported)
        assertEquals(2, second.done)
        assertEquals("a third copy appeared", before, month.listFiles()!!.associate { it.name to it.length() })
    }

    /**
     * The destination taken away in the middle of a run: a drive unplugged, or access to the
     * folder revoked. Writes start failing part way through a file. Nothing may be left at the
     * destination under a real file name, the vault must be untouched, and once the folder is
     * back, carrying on must finish the job byte for byte and copy nothing twice.
     */
    @Test
    fun theDestinationFailingMidRunLeavesNoPartFileAndCanBeCarriedOn() {
        val originals = (0 until 6).associate { i ->
            "v$i.bin" to Random(100 + i).nextBytes(1024 * 1024)
        }
        originals.forEach { (name, bytes) -> seal(name, bytes, millis(2026, 5, 6)) }

        val gone = java.util.concurrent.atomic.AtomicBoolean(false)
        val writtenBytes = java.util.concurrent.atomic.AtomicLong(0)
        // Wrapped all the way down: the export makes a folder of its own inside the one it is
        // handed, so a wrapper that only fails at the top level would never be reached.
        fun failing(real: com.shahaabapps.obscura.backup.BackupDir): com.shahaabapps.obscura.backup.BackupDir =
            object : com.shahaabapps.obscura.backup.BackupDir {
                override val name get() = real.name
                override fun list() = if (gone.get()) throw java.io.IOException("gone") else real.list()
                override fun directory(n: String) = failing(real.directory(n))
                override fun delete(n: String) = real.delete(n)
                override fun write(n: String, mimeType: String): java.io.OutputStream {
                    if (gone.get()) throw java.io.FileNotFoundException("the folder is gone")
                    val out = real.write(n, mimeType)
                    return object : java.io.OutputStream() {
                        override fun write(b: Int) = out.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            // Once the folder is gone, a write in flight fails like a detached
                            // drive rather than quietly succeeding.
                            if (gone.get()) throw java.io.IOException("the folder is gone")
                            out.write(b, off, len)
                            writtenBytes.addAndGet(len.toLong())
                        }
                        override fun flush() = out.flush()
                        override fun close() = out.close()
                    }
                }
            }
        val flaky = failing(FileBackupDir(drive))

        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(flaky, startService = false))
        val until = System.currentTimeMillis() + 60_000
        while (writtenBytes.get() < 1_200_000 && System.currentTimeMillis() < until) Thread.sleep(2)
        gone.set(true)
        val stopped = awaitExport()

        assertTrue(
            "it should have reported trouble: $stopped",
            stopped.failed > 0 || stopped.error != null || stopped.cancelled,
        )
        // The vault is untouched.
        assertTrue("the vault closed", entry.session().openVault != null)
        assertEquals(originals.size, runBlocking { entry.session().openVault!!.database.files().all().size })
        // Nothing half-written is sitting there under a real file name.
        val leftovers = drive.walkTopDown().filter { it.isFile }
            .filter { file -> originals.values.none { it.contentEquals(file.readBytes()) } }
            .map { "${it.name}:${it.length()}" }
            .toList()
        assertEquals("a part file was left at the destination: $leftovers", emptyList<String>(), leftovers)

        // The folder comes back; carrying on must finish the job.
        gone.set(false)
        val finished = exportAndWait()
        assertEquals("the second run reported failures: ${finished.error}", 0, finished.failed)
        val copies = drive.walkTopDown().filter { it.isFile }.toList()
        assertEquals("expected one copy of each, got ${copies.map { it.name }}", originals.size, copies.size)
        originals.forEach { (name, bytes) ->
            assertTrue(
                "$name did not come out byte for byte",
                copies.any { it.readBytes().contentEquals(bytes) },
            )
        }
    }
}
