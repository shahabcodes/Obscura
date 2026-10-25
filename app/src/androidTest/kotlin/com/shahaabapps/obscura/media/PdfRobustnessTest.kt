package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.recovery.RecoveryKeyPdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PDFs that are huge, damaged, locked or closed mid-render must never bring the app down. */
@RunWith(AndroidJUnit4::class)
class PdfRobustnessTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A PdfSource over bytes in memory, standing in for a sealed file. */
    private fun source(bytes: ByteArray, stillOpen: () -> Boolean = { true }, slowReads: Boolean = false) = object : PdfSource {
        override val size = bytes.size.toLong()
        override fun stillOpen() = stillOpen.invoke()
        override fun open(): SeekableByteChannel = object : SeekableByteChannel {
            private var at = 0L
            private var open = true
            override fun read(dst: ByteBuffer): Int {
                if (slowReads) Thread.sleep(3)
                if (at >= bytes.size) return -1
                val n = minOf(dst.remaining().toLong(), bytes.size - at).toInt()
                dst.put(bytes, at.toInt(), n)
                at += n
                return n
            }
            override fun write(src: ByteBuffer) = throw UnsupportedOperationException()
            override fun position() = at
            override fun position(newPosition: Long) = apply { at = newPosition }
            override fun size() = bytes.size.toLong()
            override fun truncate(size: Long) = throw UnsupportedOperationException()
            override fun isOpen() = open
            override fun close() {
                open = false
            }
        }
    }

    private fun pdf(vararg pages: Pair<Int, Int>): ByteArray {
        val doc = PdfDocument()
        pages.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", 20f, 40f, Paint().apply { textSize = 24f })
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    @Test
    fun ordinaryPagesRender() = runBlocking {
        val doc = SealedPdf.open(context, source(pdf(612 to 792, 792 to 612)))
        assertEquals(2, doc.pageCount)
        val portrait = doc.render(0, 1000)
        val landscape = doc.render(1, 1000)
        assertNotNull("portrait page", portrait)
        assertNotNull("landscape page", landscape)
        assertEquals(1000, portrait!!.width)
        assertEquals(1294, portrait.height)
        assertEquals(1000, landscape!!.width)
        assertEquals(773, landscape.height)
        doc.close()
    }

    @Test
    fun aPageMetresLongStillFitsInMemory() = runBlocking {
        // 612 x 60,000 points: the old code asked for a 700 MB bitmap and hung the phone.
        val doc = SealedPdf.open(context, source(pdf(612 to 60_000)))
        val bitmap = doc.render(0, 1344)
        assertNotNull("a very tall page still renders", bitmap)
        assertTrue("bitmap ${bitmap!!.width}x${bitmap.height}", bitmap.byteCount <= SealedPdf.MAX_PAGE_BYTES)
        assertTrue(bitmap.height <= SealedPdf.MAX_SIDE)
        // The shape of the page is kept, just smaller.
        assertEquals(60_000f / 612f, bitmap.height.toFloat() / bitmap.width, 1f)
        doc.close()
    }

    @Test
    fun closingWhilePagesRenderIsSafe() = runBlocking {
        val doc = SealedPdf.open(context, source(pdf(*Array(40) { 612 to 792 })))
        val renders = (0 until 40).map { i -> async(Dispatchers.IO) { doc.render(i, 1344) } }
        delay(10)
        withContext(Dispatchers.Main) { doc.close() } // what leaving the screen does
        renders.forEach { it.await() } // pages after the close come back null, not as a crash
        assertNull("rendering after close gives nothing", doc.render(0, 1344))
    }

    @Test
    fun aDamagedPdfFailsCleanly() = runBlocking {
        val junk = "%PDF-1.7\n".toByteArray() + Random(5).nextBytes(50_000)
        assertTrue(runCatching { SealedPdf.open(context, source(junk)) }.isFailure)
        assertTrue(runCatching { SealedPdf.open(context, source(ByteArray(0))) }.isFailure)
    }

    @Test
    fun aPasswordProtectedPdfFailsCleanly() = runBlocking {
        val locked = RecoveryKeyPdf.build("7KQ2-M9XA-T4RH-WC1E-3PZN-B8DV-Y6JF-0GSU", ByteArray(16), "pass word".toCharArray())
        assertTrue(runCatching { SealedPdf.open(context, source(locked)) }.isFailure)
    }

    /** A locked vault ends the document, as it ends a share link. */
    @Test
    fun lockingTheVaultEndsAnOpenDocument() = runBlocking {
        var unlocked = true
        val doc = SealedPdf.open(context, source(pdf(612 to 792), stillOpen = { unlocked }))
        assertNotNull("renders while unlocked", doc.render(0, 600))
        unlocked = false
        assertNull("a locked vault ends the document", doc.render(0, 600))
        doc.close()
    }

    /**
     * Leaving the screen while a big PDF is still opening must not strand the descriptor, its
     * thread and the file key it holds: repeating it would run the app out of file handles.
     */
    @Test
    fun givingUpWhileItOpensLeavesNothingBehind() = runBlocking {
        val bytes = pdf(*Array(60) { 612 to 792 })
        fun openFds() = File("/proc/self/fd").listFiles()?.size ?: 0
        // Warm up first, so one-off allocations aren't counted as a leak.
        SealedPdf.open(context, source(bytes)).close()
        Thread.sleep(200)
        val before = openFds()
        repeat(15) { withTimeoutOrNull(5) { SealedPdf.open(context, source(bytes, slowReads = true)) }?.close() }
        Thread.sleep(500)
        val after = openFds()
        assertTrue("open files went from $before to $after", after - before < 8)
    }

    @Test
    fun pagesOutsideTheDocumentGiveNothing() = runBlocking {
        val doc = SealedPdf.open(context, source(pdf(612 to 792)))
        assertNull(doc.render(5, 1000))
        assertNull(doc.render(-1, 1000))
        doc.close()
    }
}
