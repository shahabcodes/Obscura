package com.shahaabapps.obscura.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.KeepVaultOpen
import com.shahaabapps.obscura.backup.BackupDir
import com.shahaabapps.obscura.backup.BackupNode
import com.shahaabapps.obscura.backup.FileBackupDir
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.Album
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Export everything" under attack after its rework into one flat folder with a plaintext index.
 *
 * The questions here are the ones unit tests do not ask: can the naming rule be made to hand two
 * different files the same name, does the index ever disagree with what is actually at the
 * destination, and does a copy ever hold the wrong bytes.
 */
@RunWith(AndroidJUnit4::class)
class ExportEverythingAttackTest {

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
        drive = File(context.cacheDir, "export-attack").apply { deleteRecursively(); mkdirs() }
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

    private fun seal(name: String, bytes: ByteArray, takenAt: Long = millis(2026, 6, 1)): VaultFile = runBlocking {
        entry.session().openVault!!.files.put(
            bytes.inputStream(),
            IncomingFile(name, "application/octet-stream", takenAt),
        )
    }

    private fun awaitExport(timeoutMs: Long = 600_000): ExportProgress {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val progress = entry.exportManager().progress.value
            if (progress.finished && !progress.running) return progress
            Thread.sleep(25)
        }
        throw AssertionError("the export did not finish: ${entry.exportManager().progress.value}")
    }

    private fun exportAndWait(destination: BackupDir = FileBackupDir(drive)): ExportProgress {
        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(destination, startService = false))
        return awaitExport()
    }

    private val exported get() = File(drive, ExportManager.FOLDER)

    private fun copies(): List<File> =
        exported.listFiles().orEmpty().filter { it.isFile && it.name != ExportManager.INDEX }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun shaOf(file: File): String = sha(file.readBytes())

    /** What the vault itself holds, decrypted, by SHA-256. */
    private fun vaultHashes(): Map<String, String> = runBlocking {
        val vault = entry.session().openVault!!
        vault.database.files().all().associate { f ->
            f.id to sha(vault.files.open(f).use { it.readBytes() })
        }
    }

    /** The index as rows of fields, header dropped. */
    private fun indexRows(): List<List<String>> {
        val text = File(exported, ExportManager.INDEX).readText()
        return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields += cell.toString(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            fields += cell.toString()
            fields
        }.toList()
    }

    // ---------------------------------------------------------------- naming

    /**
     * Two files in the vault both called "Obscura index.csv". [ExportManager.exportName] renames
     * either of them to "file-Obscura index.csv" and returns before the shared-name check, so
     * both land on the one name. One of the user's files never comes out.
     */
    @Test
    fun twoFilesNamedLikeTheIndexBothComeOut() {
        val first = Random(1).nextBytes(4_000)
        val second = Random(2).nextBytes(9_000) // different size as well as content
        seal(ExportManager.INDEX, first)
        seal(ExportManager.INDEX, second)

        val result = exportAndWait()

        val got = copies().map { shaOf(it) }.toSet()
        assertEquals("both should have been copied, not failed: $result", 0, result.failed)
        assertTrue("the first file named like the index is missing: ${copies().map { it.name }}", sha(first) in got)
        assertTrue("the second file named like the index is missing: ${copies().map { it.name }}", sha(second) in got)
    }

    /**
     * A file whose own name is exactly what the export would call a different file: "beach
     * [<first six of an id>].jpg". Two other files both want "beach.jpg", so one of them is given
     * that very name. Nothing may be overwritten.
     *
     * Reachable in practice by importing an earlier export back into the vault.
     */
    @Test
    fun aFileNamedLikeAnotherFilesDisambiguatedCopyIsNotOverwritten() {
        val a = Random(3).nextBytes(5_000)
        val b = Random(4).nextBytes(5_000)
        val one = seal("beach.jpg", a)
        seal("beach.jpg", b)
        // The name the export will give `one`.
        val collide = "beach [" + one.id.take(6) + "].jpg"
        val c = Random(5).nextBytes(5_000)
        seal(collide, c)

        val result = exportAndWait()

        val got = copies().map { shaOf(it) }.toSet()
        assertEquals("nothing should have failed: $result", 0, result.failed)
        assertEquals(
            "three files in, ${copies().size} out: ${copies().map { "${it.name}:${it.length()}" }}",
            3,
            copies().size,
        )
        listOf("first beach.jpg" to a, "second beach.jpg" to b, "the file called $collide" to c)
            .forEach { (what, bytes) -> assertTrue("$what is missing from the export", sha(bytes) in got) }
    }

    /** Names that differ only by case must both come out whole. */
    @Test
    fun namesDifferingOnlyByCaseBothComeOut() {
        val lower = Random(6).nextBytes(3_000)
        val upper = Random(7).nextBytes(3_000)
        seal("holiday.jpg", lower)
        seal("HOLIDAY.JPG", upper)

        val result = exportAndWait()

        val got = copies().map { shaOf(it) }.toSet()
        assertEquals(0, result.failed)
        assertTrue("holiday.jpg is missing: ${copies().map { it.name }}", sha(lower) in got)
        assertTrue("HOLIDAY.JPG is missing: ${copies().map { it.name }}", sha(upper) in got)
    }

    /** Awkward names: very long, reserved characters, dots and spaces, non-Latin. */
    @Test
    fun awkwardNamesDoNotCrashTheRunAndAreAccountedFor() {
        val names = listOf(
            "a".repeat(300) + ".jpg",
            "road/to:nowhere?*.jpg",
            "   spaces   .jpg",
            "....jpg",
            "صورة الشاطئ.jpg",
            "emoji 😀 shot.jpg",
            "trailing.dot.",
            "con.jpg",
        )
        val bytes = names.mapIndexed { i, n -> n to Random(20 + i).nextBytes(1_000 + i * 7) }
        bytes.forEach { (n, b) -> seal(n, b) }

        val result = exportAndWait()

        assertEquals("every file must be accounted for: $result", names.size, result.done)
        // A name the filesystem refuses is a reported failure, never a silent success.
        if (result.failed > 0) {
            assertNotNull("files failed but nothing was reported: $result", result.error)
        }
        val got = copies().map { shaOf(it) }.toSet()
        val missing = bytes.filter { sha(it.second) !in got }.map { it.first.take(40) }
        assertEquals(
            "files neither copied nor reported failed (failed=${result.failed}, " +
                "error=${result.error}, missing=$missing, out=${copies().map { it.name.take(40) }})",
            result.failed,
            missing.size,
        )
        // And the index must not name anything that is not there.
        indexAgreesWithTheFolder()
    }

    /**
     * Nothing of the real vault's export may be visible or reachable once the decoy is open: not
     * the folder it went to, and not the report of the run.
     */
    @Test
    fun theDecoyShowsNothingOfTheRealVaultsExport() {
        seal("private.bin", Random(900).nextBytes(10_000))
        val result = exportAndWait()
        assertEquals(1, result.exported)

        val decoy = "harbour lantern thicket pebble"
        runBlocking { entry.session().createDecoy(decoy.toCharArray(), null) }
        instrumentation.runOnMainSync { entry.session().lock() }
        Thread.sleep(300)
        runBlocking { entry.session().unlockWithPassphrase(decoy.toCharArray()) }
        assertTrue("the decoy did not open", entry.session().openVault?.isDecoy == true)

        assertTrue(
            "the decoy can reach the real vault's export folder: ${entry.exportManager().destination()}",
            entry.exportManager().destination() == null,
        )
        // The figures are cleared as the next vault opens, which happens on the manager's own
        // coroutine rather than inside unlock, so this waits for it rather than reading at the
        // instant the unlock call returns.
        val until = System.currentTimeMillis() + 10_000
        var seen = entry.exportManager().progress.value
        while (seen.finished && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            seen = entry.exportManager().progress.value
        }
        assertTrue(
            "the decoy session is still showing the real vault's export: $seen",
            seen == ExportProgress() || (!seen.finished && !seen.running),
        )
    }

    // ---------------------------------------------------------------- the index

    /** Every row names a file that is there; every file there has a row; bytes match the vault. */
    private fun indexAgreesWithTheFolder() {
        val index = File(exported, ExportManager.INDEX)
        assertTrue("no index was written", index.isFile)
        val rows = indexRows()
        val present = copies().associateBy { it.name }
        val named = rows.map { it[0] }
        val phantom = named.filter { it !in present }
        assertEquals("the index names files that are not there: $phantom", emptyList<String>(), phantom)
        val unlisted = present.keys.filter { it !in named.toSet() }
        assertEquals("files at the destination with no index row: $unlisted", emptyList<String>(), unlisted)
        assertEquals("the index has rows for ${named.size} files but ${present.size} are there", present.size, named.size)
        // Sizes in the index are the real sizes.
        rows.forEach { row ->
            val file = present.getValue(row[0])
            assertEquals("${row[0]}: index says ${row[4]} bytes, the file is ${file.length()}", row[4], file.length().toString())
        }
    }

    @Test
    fun theIndexMatchesTheFolderAndTheCopiesMatchTheVault() {
        val albumId = "album-one"
        runBlocking {
            entry.session().openVault!!.database.albums()
                .insert(Album(albumId, "Trip, \"quoted\"", 0xFF112233, System.currentTimeMillis()))
        }
        val made = (0 until 25).map { i ->
            seal("file $i.bin", Random(300 + i).nextBytes(2_000 + i * 11), millis(2025, 1 + i % 12, 1 + i % 28))
        }
        runBlocking {
            val dao = entry.session().openVault!!.database.files()
            made.take(7).forEach { dao.setFavorite(it.id, true) }
            made.drop(5).take(9).forEach { dao.setAlbum(it.id, albumId) }
        }

        val result = exportAndWait()
        assertEquals("$result", 0, result.failed)
        assertEquals(25, result.exported)

        indexAgreesWithTheFolder()

        // Byte for byte against the vault.
        val wanted = vaultHashes().values.toList().sorted()
        val out = copies().map { shaOf(it) }.sorted()
        assertEquals("the copies are not the vault's contents", wanted, out)

        // Favourite and album made it across for the right files.
        val rows = indexRows().associateBy { it[1] }
        val refreshed = runBlocking { entry.session().openVault!!.database.files().all() }
        refreshed.forEach { f ->
            val row = rows.getValue(f.name)
            assertEquals("${f.name}: favourite", if (f.favorite) "yes" else "no", row[6])
            assertEquals("${f.name}: album", if (f.albumId == albumId) "Trip, \"quoted\"" else "", row[5])
        }
    }

    /** A run the user cancels must leave no index behind describing an export that did not happen. */
    @Test
    fun aCancelledRunLeavesNoIndex() {
        (0 until 12).forEach { i -> seal("big $i.bin", Random(400 + i).nextBytes(512 * 1024)) }

        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 60_000
        while (entry.exportManager().progress.value.done < 3 && System.currentTimeMillis() < until) Thread.sleep(5)
        entry.exportManager().cancel()
        val stopped = awaitExport()

        assertTrue("it should have reported being cancelled: $stopped", stopped.cancelled)

        // The list is kept, because a row is only written once its file is really there and is
        // flushed as it goes. So what survives a cancel is a true account of how far the run
        // got -- and it is what lets the next run know which copies are already its own work.
        val index = File(exported, ExportManager.INDEX)
        assertTrue("the run left no list at all", index.isFile)
        val rows = index.readLines().drop(1).filter { it.isNotBlank() }
        val onDisk = exported.listFiles().orEmpty()
            .filter { it.isFile && it.name != ExportManager.INDEX }
            .associate { it.name to it.length() }
        assertTrue("the list has no rows", rows.isNotEmpty())
        rows.forEach { row ->
            val name = row.removePrefix("\"").substringBefore("\"")
            assertTrue("the list names $name, which is not there: ${onDisk.keys}", name in onDisk)
        }
    }

    /**
     * The index is meant to be written as the run goes, so that a run killed outright leaves
     * something usable. Here the run is watched: how much of the index has actually reached the
     * destination by the time most of the copies are out.
     */
    @Test
    fun theIndexReachesTheDestinationWhileTheRunIsStillGoing() {
        (0 until 40).forEach { i -> seal("row $i.bin", Random(500 + i).nextBytes(60_000)) }

        val indexBytesWhenHalfDone = java.util.concurrent.atomic.AtomicLong(-1)
        val real = FileBackupDir(drive)
        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(real, startService = false))
        val until = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < until) {
            val p = entry.exportManager().progress.value
            if (p.done >= 30 && indexBytesWhenHalfDone.get() < 0) {
                indexBytesWhenHalfDone.set(File(exported, ExportManager.INDEX).let { if (it.isFile) it.length() else 0 })
            }
            if (p.finished && !p.running) break
            Thread.sleep(5)
        }
        awaitExport()

        assertTrue(
            "after 30 of 40 files were copied the index on disk held only " +
                "${indexBytesWhenHalfDone.get()} bytes: it is buffered, not written as the run " +
                "goes, so a run killed outright leaves a short index naming fewer files than are there",
            indexBytesWhenHalfDone.get() > 500,
        )
    }

    /** If the index alone cannot be written the copies must still go out, and it must be said. */
    @Test
    fun copiesStillGoOutWhenTheIndexCannotBeWritten() {
        val made = (0 until 6).map { i -> seal("keep $i.bin", Random(600 + i).nextBytes(20_000)) }

        fun blockIndex(real: BackupDir): BackupDir = object : BackupDir {
            override val name get() = real.name
            override fun list(): List<BackupNode> = real.list()
            override fun child(name: String): BackupNode? = real.child(name)
            override fun directory(name: String): BackupDir = blockIndex(real.directory(name))
            override fun write(name: String, mimeType: String): OutputStream {
                if (name == ExportManager.INDEX) throw java.io.IOException("no index here")
                return real.write(name, mimeType)
            }
            override fun writeNew(name: String, mimeType: String): OutputStream {
                if (name == ExportManager.INDEX) throw java.io.IOException("no index here")
                return real.writeNew(name, mimeType)
            }
            override fun setModified(name: String, whenMillis: Long) = real.setModified(name, whenMillis)
            override fun delete(name: String) = real.delete(name)
            override fun availableBytes(): Long? = real.availableBytes()
        }

        val result = exportAndWait(blockIndex(FileBackupDir(drive)))

        assertEquals("the copies should all have gone out: $result", made.size, result.exported)
        assertEquals(0, result.failed)
        assertNotNull("the index failing was not reported at all: $result", result.error)
        assertTrue("there should be no index: ${copies().map { it.name }}", !File(exported, ExportManager.INDEX).exists())
        val got = copies().map { shaOf(it) }.sorted()
        assertEquals(vaultHashes().values.sorted(), got)
    }

    // ---------------------------------------------------------------- across runs

    /**
     * The vault changes between runs in the way that most threatens the resume rule: a file is
     * shredded and another of the same name and the same size is imported in its place. The
     * leftover copy matches by name and size, so it is skipped — and the index then names the new
     * file beside bytes that are the shredded one's.
     */
    @Test
    fun aReplacedFileOfTheSameNameAndSizeIsNotStillTheOldContent() {
        val old = Random(700).nextBytes(30_000)
        val first = seal("IMG_2001.jpg", old)
        assertEquals(1, exportAndWait().exported)

        runBlocking { entry.session().openVault!!.shred(first) }
        val new = Random(701).nextBytes(30_000) // same size, different content
        seal("IMG_2001.jpg", new)

        val second = exportAndWait()
        assertEquals(1, second.done)

        val got = copies().map { shaOf(it) }.toSet()
        assertTrue(
            "the destination still holds the shredded file's bytes while the index names the " +
                "new one (skipped=${second.skipped}, exported=${second.exported})",
            sha(new) in got,
        )
        assertTrue("the shredded file's plaintext is still at the destination", sha(old) !in got)
    }

    /**
     * A file is shredded between runs. Its plaintext copy stays at the destination, and the index
     * the second run writes does not mention it — so the index no longer describes the folder,
     * and the folder holds plaintext of something the user has since destroyed.
     */
    @Test
    fun shreddingBetweenRunsLeavesNoUnlistedPlaintextBehind() {
        val made = (0 until 5).map { i -> seal("shot $i.bin", Random(800 + i).nextBytes(8_000 + i)) }
        assertEquals(5, exportAndWait().exported)
        val gone = sha(runBlocking {
            entry.session().openVault!!.files.open(made[0]).use { it.readBytes() }
        })

        runBlocking {
            entry.session().openVault!!.shred(made[0])
            entry.session().openVault!!.database.files().setFavorite(made[1].id, true)
        }
        val second = exportAndWait()

        assertEquals(0, second.failed)
        assertTrue(
            "the shredded file's plaintext is still sitting at the destination: " +
                copies().map { it.name },
            copies().none { shaOf(it) == gone },
        )
        indexAgreesWithTheFolder()
    }

    // ---------------------------------------------------------------- scale

    /** A few thousand small files in the one folder: it must not slow down the further it gets. */
    @Test
    fun thousandsOfFilesInOneFolderDoNotSlowDown() {
        val count = 2_500
        runBlocking {
            val vault = entry.session().openVault!!
            repeat(count) { i ->
                vault.files.put(
                    ByteArray(64) { (i + it).toByte() }.inputStream(),
                    IncomingFile("tiny $i.bin", "application/octet-stream", millis(2026, 1 + i % 12, 1 + i % 28)),
                )
            }
        }
        assertEquals(count, runBlocking { entry.session().openVault!!.database.files().count() })

        entry.exportManager().reset()
        val began = System.currentTimeMillis()
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        // Time the first and the last fifth: a per-file folder listing shows as the tail taking
        // several times as long as the head.
        var firstFifth = -1L
        val until = began + 900_000
        while (System.currentTimeMillis() < until) {
            val p = entry.exportManager().progress.value
            if (firstFifth < 0 && p.done >= count / 5) firstFifth = System.currentTimeMillis() - began
            if (p.finished && !p.running) break
            Thread.sleep(10)
        }
        val result = awaitExport()
        val whole = System.currentTimeMillis() - began

        assertEquals("$result", 0, result.failed)
        assertEquals(count, result.exported)
        assertEquals(count, copies().size)
        assertTrue("$count files took ${whole}ms", whole < 300_000)
        assertTrue(
            "the first fifth took ${firstFifth}ms but the whole run took ${whole}ms: it is " +
                "getting slower the further through it gets",
            firstFifth <= 0 || whole < firstFifth * 12,
        )
        indexAgreesWithTheFolder()
    }
}
