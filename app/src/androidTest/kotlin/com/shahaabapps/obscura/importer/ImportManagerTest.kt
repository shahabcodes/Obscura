package com.shahaabapps.obscura.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class ImportManagerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val manager get() = entry.importManager()

    private class TestSource(
        override val displayName: String,
        override val mimeType: String,
        private val bytes: ByteArray,
        override val lastModified: Long? = 1_600_000_000_000,
        private val delayPerChunkMs: Long = 0,
    ) : ImportSource {
        var deleted = false
        override val sizeBytes: Long = bytes.size.toLong()
        override fun open(): InputStream = if (delayPerChunkMs == 0L) ByteArrayInputStream(bytes) else object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(delayPerChunkMs)
                return inner.read(b, off, minOf(len, 64 * 1024))
            }
        }
        override fun deleteOriginal(): Boolean {
            deleted = true
            return true
        }
    }

    private fun wipe() = instrumentation.runOnMainSync {
        manager.cancel()
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    private fun awaitFinished(timeoutMs: Long = 30_000): ImportProgress {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val p = manager.progress.value
            if (p.finished && !p.running) return p
            Thread.sleep(50)
        }
        throw AssertionError("Import did not finish: ${manager.progress.value}")
    }

    /** A real JPEG with GPS and a date, written by Android's own EXIF code. */
    private fun jpegWithLocation(): ByteArray {
        val file = File(context.cacheDir, "gps-test.jpg")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(59, 91, 255)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply {
            setLatLong(51.5074, -0.1278)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:07:01 10:20:30")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+01:00")
            setAttribute(ExifInterface.TAG_MAKE, "ObscuraTest")
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    @Before
    fun setUp() {
        wipe()
        manager.reset()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
    }

    @After
    fun tearDown() {
        manager.reset()
        wipe()
    }

    @Test
    fun importsSkipsDuplicatesAndStripsLocation() = runBlocking {
        val jpeg = jpegWithLocation()
        assertNotNull("test photo has GPS", ExifInterface(ByteArrayInputStream(jpeg)).latLong)
        val text = "private notes".toByteArray()
        val sources = listOf(
            TestSource("london.jpg", "image/jpeg", jpeg),
            TestSource("notes.txt", "text/plain", text),
            TestSource("notes copy.txt", "text/plain", text),
        )
        assertTrue(manager.start(sources, ImportOptions(skipDuplicates = true, stripLocation = true), startService = false))
        val result = awaitFinished()
        assertEquals(2, result.sealed)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.locationRemoved)
        assertEquals(0, result.failed)

        val vault = entry.session().openVault!!
        val rows = vault.database.files().observeAll().first()
        assertEquals(2, rows.size)
        val photo = rows.single { it.name == "london.jpg" }
        assertEquals(FileKind.PHOTO, photo.kind)
        assertEquals(64, photo.width)
        // EXIF date with its +01:00 offset: 2024-07-01 09:20:30 UTC.
        assertEquals(1_719_825_630_000L, photo.takenAt)

        val sealedPhoto = vault.files.open(photo).use { it.readBytes() }
        val exif = ExifInterface(ByteArrayInputStream(sealedPhoto))
        assertNull("location must be gone", exif.latLong)
        assertEquals("2024:07:01 10:20:30", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("ObscuraTest", exif.getAttribute(ExifInterface.TAG_MAKE))

        val note = rows.single { it.kind == FileKind.DOCUMENT }
        assertArrayEquals(text, vault.files.open(note).use { it.readBytes() })
        assertEquals(1_600_000_000_000, note.takenAt)
    }

    @Test
    fun locationIsKeptWhenTheOptionIsOff() = runBlocking {
        manager.start(listOf(TestSource("keep.jpg", "image/jpeg", jpegWithLocation())), ImportOptions(stripLocation = false), startService = false)
        assertEquals(0, awaitFinished().locationRemoved)
        val vault = entry.session().openVault!!
        val row = vault.database.files().observeAll().first().single()
        assertNotNull(ExifInterface(vault.files.open(row)).latLong)
    }

    @Test
    fun vaultStaysOpenDuringImportThenLocks() {
        val slow = TestSource("big.bin", "application/octet-stream", ByteArray(6 * 64 * 1024), delayPerChunkMs = 400)
        manager.start(listOf(slow), ImportOptions(), startService = false)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        Thread.sleep(1_000)
        assertTrue("vault must stay unlocked while importing", entry.session().state.value is VaultState.Unlocked)
        assertTrue(entry.autoLock().isLockDeferred)
        val result = awaitFinished()
        assertEquals(1, result.sealed)
        Thread.sleep(500)
        assertTrue("deferred lock happens once the import is done", entry.session().state.value is VaultState.Locked)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
    }

    @Test
    fun cancelStopsTheBatch() {
        val sources = List(5) { TestSource("f$it.bin", "application/octet-stream", ByteArray(4 * 64 * 1024) { b -> (b + it).toByte() }, delayPerChunkMs = 300) }
        manager.start(sources, ImportOptions(), startService = false)
        Thread.sleep(700)
        manager.cancel()
        val result = awaitFinished()
        assertTrue(result.cancelled)
        assertTrue(result.sealed < 5)
    }

    @Test
    fun deletingOriginalsCoversSealedAndDuplicateFiles() = runBlocking {
        val bytes = "same".toByteArray()
        val a = TestSource("a.txt", "text/plain", bytes)
        val b = TestSource("b.txt", "text/plain", bytes)
        manager.start(listOf(a, b), ImportOptions(), startService = false)
        awaitFinished()
        assertEquals(2, manager.deletableOriginals)
        assertEquals(2 to 0, manager.deleteOriginals())
        assertTrue(a.deleted && b.deleted)
    }

    @Test
    fun parsesMediaAndExifDates() {
        assertEquals(1_723_660_320_000L, MetadataReader.parseMediaDate("20240814T183200.000Z"))
        assertNull(MetadataReader.parseMediaDate("19040101T000000.000Z"))
        val utc = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals(1_723_660_320_000L, MetadataReader.parseExifDate("2024:08:14 18:32:00", null))
        } finally {
            TimeZone.setDefault(utc)
        }
        assertNull(MetadataReader.parseExifDate("0000:00:00 00:00:00", null))
    }
}
