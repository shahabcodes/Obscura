package com.shahaabapps.obscura.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var session: VaultSession

    private class MemoryStorage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private object Clock : DeviceClock {
        override fun elapsedRealtime() = 1L
        override fun bootCount() = 1
    }

    private fun newSession() = VaultSession(
        context, VaultStore(root), LockoutPolicy(MemoryStorage(), Clock),
        KeystoreBinding("obscura.test.thumb.pin"), KeystoreGate("obscura.test.thumb.bio"), KdfParams(1024, 1, 1),
    )

    /** 2000×1500 landscape JPEG whose EXIF says "rotate 90°", like a phone held upright. */
    private fun rotatedJpeg(): ByteArray {
        val file = File(context.cacheDir, "rotated.jpg")
        Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 225, 77)) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    @Before
    fun setUp() {
        root = File(context.cacheDir, "thumb-test-${System.nanoTime()}")
        session = newSession()
    }

    @After
    fun tearDown() {
        session.lock()
        root.deleteRecursively()
        KeystoreBinding("obscura.test.thumb.pin").delete()
    }

    @Test
    fun photoThumbnailIsRotatedScaledAndStoredEncrypted() = runTest {
        session.create("passphrase".toCharArray())
        val vault = session.openVault!!
        val photo = vault.files.put(rotatedJpeg().inputStream(), IncomingFile("portrait.jpg", "image/jpeg", 0))

        val thumb = vault.thumbnails.thumbnail(photo)
        assertNotNull(thumb)
        assertTrue("rotated to portrait", thumb!!.height > thumb.width)
        assertEquals(ThumbnailStore.THUMB_SIDE, minOf(thumb.width, thumb.height))

        val stored = File(File(VaultStore(root).thumbDir, photo.id.take(2)), photo.id)
        assertTrue(stored.isFile)
        val bytes = stored.readBytes()
        assertFalse("stored thumbnail must not be a readable JPEG", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())

        val display = vault.thumbnails.display(photo, 1000)!!
        assertEquals(1000, maxOf(display.width, display.height))
    }

    @Test
    fun thumbnailsSurviveLockButTheMemoryCacheDoesNot() = runTest {
        session.create("passphrase".toCharArray())
        val photo = session.openVault!!.files.put(rotatedJpeg().inputStream(), IncomingFile("p.jpg", "image/jpeg", 0))
        session.openVault!!.thumbnails.thumbnail(photo)
        val oldStore = session.openVault!!.thumbnails
        session.lock()
        assertNull("a locked vault hands out nothing", oldStore.thumbnail(photo))

        assertEquals(UnlockResult.Success, session.unlockWithPassphrase("passphrase".toCharArray()))
        assertNotNull(session.openVault!!.thumbnails.thumbnail(photo))
    }

    @Test
    fun shredRemovesBlobRowAndThumbnail() = runTest {
        session.create("passphrase".toCharArray())
        val vault = session.openVault!!
        val photo = vault.files.put(rotatedJpeg().inputStream(), IncomingFile("gone.jpg", "image/jpeg", 0))
        vault.thumbnails.thumbnail(photo)
        vault.shred(photo)
        assertNull(vault.database.files().get(photo.id))
        assertFalse(vault.files.blobFile(photo.id).exists())
        assertFalse(File(File(VaultStore(root).thumbDir, photo.id.take(2)), photo.id).exists())
    }

    /** A real H.264 MP4 from the system screen recorder, read back through a shell pipe. */
    private fun recordMp4(): ByteArray {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(cmd: String): ByteArray =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }
        // The recorder produces nothing while the screen is off, which happens late in long runs.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("screenrecord --time-limit 2 --size 320x640 /data/local/tmp/obscura-test.mp4")
        return shell("cat /data/local/tmp/obscura-test.mp4").also { shell("rm /data/local/tmp/obscura-test.mp4") }
    }

    @Test
    fun videoThumbnailComesFromTheEncryptedFile() = runTest {
        val mp4 = recordMp4()
        assertTrue("recorded a video", mp4.size > 1000)
        session.create("passphrase".toCharArray())
        val vault = session.openVault!!
        val video = vault.files.put(mp4.inputStream(), IncomingFile("clip.mp4", "video/mp4", 0))
        val thumb = vault.thumbnails.thumbnail(video)
        assertNotNull("frame decoded from the sealed video", thumb)
        assertTrue(thumb!!.width > 0 && thumb.height > 0)
    }

    @Test
    fun encryptedMediaSourceReadsAnyRange() = runTest {
        session.create("passphrase".toCharArray())
        val vault = session.openVault!!
        val data = Random(7).nextBytes(3_300_000)
        val file = vault.files.put(data.inputStream(), IncomingFile("clip.mp4", "video/mp4", 0))
        EncryptedMediaDataSource(vault.keys.fileCipher, file, vault.files.blobFile(file.id)).use { source ->
            assertEquals(data.size.toLong(), source.size)
            for (start in listOf(0L, 1_048_570L, 2_500_000L, data.size - 10L)) {
                val buffer = ByteArray(4096)
                val n = source.readAt(start, buffer, 0, buffer.size)
                val expected = data.copyOfRange(start.toInt(), minOf(data.size, start.toInt() + 4096))
                assertEquals(expected.size, n)
                assertArrayEquals(expected, buffer.copyOf(n))
            }
            assertEquals(-1, source.readAt(data.size.toLong(), ByteArray(10), 0, 10))
        }
    }
}
