package com.shahaabapps.obscura.media

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the viewer manages a [SealedPdf]'s life. The document must stay open for as long as the
 * screen is composed. Keying the effect on the document and reading that same state inside
 * onDispose closes the document the instant it arrives, before a single page is drawn, because
 * onDispose then sees the new value; the screen captures it in a local instead.
 * [PdfViewerScreenTest] guards the real screen; this pins the rule on its own.
 */
@RunWith(AndroidJUnit4::class)
class PdfViewerLifecycleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pdf(): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        page.canvas.drawText("Page 1", 20f, 40f, Paint().apply { textSize = 24f })
        doc.finishPage(page)
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    private fun source(bytes: ByteArray) = object : PdfSource {
        override val size = bytes.size.toLong()
        override fun open(): SeekableByteChannel = object : SeekableByteChannel {
            private var at = 0L
            private var open = true
            override fun read(dst: ByteBuffer): Int {
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

    /** The ownership the viewer uses: the effect captures the document it was set up with. */
    @Composable
    private fun Viewer(src: PdfSource, onOpened: (SealedPdf) -> Unit) {
        val context = LocalContext.current
        val pdf by produceState<Result<SealedPdf>?>(null, src) {
            value = runCatching { SealedPdf.open(context, src) }
        }
        val current = pdf
        DisposableEffect(current) { onDispose { current?.getOrNull()?.close() } }
        current?.getOrNull()?.let { onOpened(it) }
    }

    @Test
    fun theDocumentStaysOpenWhileItsScreenIs() {
        var document: SealedPdf? = null
        compose.setContent { Viewer(source(pdf())) { document = it } }
        compose.waitUntil(20_000) { document != null }
        compose.waitForIdle()
        Thread.sleep(500)
        assertNotNull("capturing the value keeps the document open", runBlocking { document!!.render(0, 600) })
    }

}
