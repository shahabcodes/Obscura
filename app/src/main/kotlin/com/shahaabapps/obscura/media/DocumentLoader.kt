package com.shahaabapps.obscura.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.shahaabapps.obscura.vault.VaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileOutputStream
import java.io.InputStream

/**
 * A PDF decrypted into an anonymous in-memory file (memfd). Android's PDF renderer needs a
 * seekable file descriptor; memfd gives it one that lives only in RAM and disappears on close,
 * so the document is never written to storage unencrypted. Needs Android 11+ (memfd_create).
 */
class SealedPdf private constructor(private val renderer: PdfRenderer, private val fd: ParcelFileDescriptor) : Closeable {
    private val lock = Mutex()
    val pageCount: Int get() = renderer.pageCount

    /** Renders page [index] [widthPx] wide. Pages render one at a time (the renderer isn't thread-safe). */
    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }

    companion object {
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        suspend fun open(source: () -> InputStream): SealedPdf = withContext(Dispatchers.IO) {
            check(supported) { "PDF preview needs Android 11 or newer" }
            val memfd = Os.memfd_create("obscura-doc", 0)
            try {
                // FileOutputStream on the raw descriptor; not closed here so the fd stays open.
                val out = FileOutputStream(memfd)
                source().use { it.copyTo(out, 64 * 1024) }
                out.flush()
                Os.lseek(memfd, 0, OsConstants.SEEK_SET)
                val pfd = ParcelFileDescriptor.dup(memfd)
                SealedPdf(PdfRenderer(pfd), pfd)
            } finally {
                Os.close(memfd)
            }
        }
    }
}

object TextPreview {
    const val LIMIT = 512 * 1024

    fun isText(file: VaultFile) = file.mimeType.startsWith("text/") ||
        file.mimeType in setOf("application/json", "application/xml", "application/x-yaml") ||
        file.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json", "log", "xml", "yaml", "yml")

    /** First [LIMIT] bytes as UTF-8, plus whether the file was cut short. */
    suspend fun read(source: () -> InputStream): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        source().use { input ->
            val buffer = ByteArray(LIMIT + 1)
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            String(buffer, 0, minOf(total, LIMIT), Charsets.UTF_8) to (total > LIMIT)
        }
    }
}
