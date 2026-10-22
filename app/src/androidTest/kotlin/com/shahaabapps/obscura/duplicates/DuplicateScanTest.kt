package com.shahaabapps.obscura.duplicates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import com.shahaabapps.obscura.vault.DeviceClock
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.LockoutPolicy
import com.shahaabapps.obscura.vault.LockoutStorage
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Real photos: an exact copy, a resized re-save and an unrelated picture. */
@RunWith(AndroidJUnit4::class)
class DuplicateScanTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "dupe-test-${System.nanoTime()}")

    private object Storage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private object Clock : DeviceClock {
        override fun elapsedRealtime() = 1L
        override fun bootCount() = 1
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        KeystoreBinding("obscura.test.dupe.pin").delete()
    }

    /** A scene with shapes, so its visual fingerprint has real structure. */
    private fun scene(w: Int, h: Int, sunX: Float, quality: Int, sky: Int = Color.rgb(59, 91, 255)): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(sky)
            drawCircle(sunX * w, h * 0.3f, h * 0.2f, Paint().apply { color = Color.rgb(255, 225, 77) })
            drawRect(0f, h * 0.65f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.rgb(36, 64, 214) })
            drawRect(w * 0.1f, h * 0.45f, w * 0.3f, h * 0.65f, Paint().apply { color = Color.BLACK })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    /** A completely different picture: diagonal bands. */
    private fun stripes(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.rgb(255, 122, 182); strokeWidth = w / 14f }
        for (i in -10..20) canvas.drawLine(i * w / 8f, 0f, i * w / 8f + h, h.toFloat(), paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test
    fun findsExactCopiesAndResizedResaves() = runBlocking {
        val session = VaultSession(
            context, VaultStore(root), LockoutPolicy(Storage, Clock),
            KeystoreBinding("obscura.test.dupe.pin"), KeystoreGate("obscura.test.dupe.bio"), KdfParams(1024, 1, 1),
        )
        session.create("passphrase".toCharArray())
        val vault = session.openVault!!
        suspend fun put(name: String, bytes: ByteArray, w: Int, h: Int, taken: Long) =
            vault.files.put(bytes.inputStream(), IncomingFile(name, "image/jpeg", taken, w, h))

        val original = scene(1600, 1200, 0.7f, 92)
        val a = put("pier.jpg", original, 1600, 1200, 1_000_000)
        val copy = put("pier (1).jpg", original, 1600, 1200, 1_000_000)
        val small = put("pier-small.jpg", scene(640, 480, 0.7f, 60), 640, 480, 9_000_000_000)
        val other = put("stripes.jpg", stripes(1600, 1200), 1600, 1200, 5_000_000_000)

        var lastProgress = 0 to 0
        val groups = DuplicateScanner.scan(vault) { d, t -> lastProgress = d to t }
        assertEquals("fingerprints computed for all 4 photos", 4 to 4, lastProgress)

        val exact = groups.single { it.kind == DuplicateKind.EXACT }
        assertEquals(setOf(a.id, copy.id), exact.files.map { it.id }.toSet())
        assertEquals("the first sealed copy is kept", a.id, exact.best.id)

        val alike = groups.single { it.kind == DuplicateKind.LOOK_ALIKE }
        assertTrue(small.id in alike.files.map { it.id })
        assertEquals("the large original beats the small re-save", 1600, alike.best.width)
        assertTrue("an unrelated photo is never grouped", groups.none { g -> g.files.any { it.id == other.id } })

        // A second scan reuses the stored fingerprints.
        var rescanned = -1
        DuplicateScanner.scan(vault) { _, t -> rescanned = t }
        assertEquals(-1, rescanned)
        session.lock()
    }
}
