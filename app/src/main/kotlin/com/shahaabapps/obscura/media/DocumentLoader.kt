package com.shahaabapps.obscura.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.shahaabapps.obscura.vault.OpenVault
import com.shahaabapps.obscura.vault.VaultFile
import java.io.Closeable
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Random-access PDF bytes, decrypted segment by segment as the renderer asks for them. */
interface PdfSource {
    val size: Long
    fun open(): SeekableByteChannel

    /** False once the vault that granted this source has locked. */
    fun stillOpen(): Boolean = true
}

/** A sealed file as a [PdfSource]; nothing is decrypted until the renderer reads. */
fun OpenVault.pdfSource(file: VaultFile, stillOpen: () -> Boolean = { true }): PdfSource = object : PdfSource {
    override val size = file.sizeBytes

    override fun stillOpen() = stillOpen.invoke()

    override fun open(): SeekableByteChannel {
        val raw = FileChannel.open(files.blobFile(file.id).toPath(), StandardOpenOption.READ)
        return try {
            val plain = keys.fileCipher.seekableChannel(file.wrappedKey, file.id, raw)
            object : SeekableByteChannel by plain {
                override fun close() {
                    runCatching { plain.close() }
                    runCatching { raw.close() }
                }
            }
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
    }
}

/**
 * A sealed PDF handed to Android's renderer through a file descriptor that decrypts on demand,
 * so the document is never written to storage and never held in memory in full. Pages render one
 * at a time and are capped in size, because a PDF may declare a page metres long and a bitmap for
 * it would exhaust the phone's memory.
 */
class SealedPdf private constructor(
    private val renderer: PdfRenderer,
    private val fd: ParcelFileDescriptor,
    private val source: PdfSource,
) : Closeable {

    private val lock = Mutex()
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pageCount: Int = renderer.pageCount

    /**
     * Renders page [index] about [widthPx] wide, scaled down if the page is so large that a
     * full-size bitmap wouldn't fit in memory. Returns null once the document is closed, or if
     * that page can't be drawn; the caller shows a placeholder rather than crashing.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        // Checked inside the lock, and close() waits for the lock, so the renderer is never
        // used after it has been closed.
        // A locked vault ends the document: its pages are no longer ours to draw.
        if (!source.stillOpen()) {
            close()
            return@withLock null
        }
        if (closed.get() || index !in 0 until pageCount) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                renderer.openPage(index).use { page ->
                    val (width, height) = fit(page.width, page.height, widthPx)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Safe to call from the main thread while pages are rendering; the close itself waits. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            lock.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }

    companion object {
        /** Biggest bitmap for one page: roughly 6 megapixels. */
        const val MAX_PAGE_BYTES = 24 * 1024 * 1024

        /** Keeps each side within what phone graphics can draw. */
        const val MAX_SIDE = 4096

        /** Only the fallback path holds the whole PDF in memory, so it is capped. */
        const val MAX_IN_MEMORY = 192L * 1024 * 1024

        /**
         * Page size in pixels for a page [pageWidth] by [pageHeight] points, aiming for
         * [widthPx] wide but never more than [MAX_PAGE_BYTES] or [MAX_SIDE] on a side.
         */
        fun fit(pageWidth: Int, pageHeight: Int, widthPx: Int): Pair<Int, Int> {
            val safeWidth = pageWidth.coerceAtLeast(1)
            val safeHeight = pageHeight.coerceAtLeast(1)
            var width = widthPx.coerceAtLeast(1).toDouble()
            var height = (width * safeHeight / safeWidth).coerceAtLeast(1.0)
            val maxPixels = MAX_PAGE_BYTES / 4.0
            if (width * height > maxPixels) {
                val scale = sqrt(maxPixels / (width * height))
                width *= scale
                height *= scale
            }
            val longest = maxOf(width, height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE / longest
                width *= scale
                height *= scale
            }
            return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
        }

        /**
         * Opens [source] for rendering.
         * @throws Exception if the bytes aren't a PDF this phone can open (damaged, or
         *   password-protected).
         */
        suspend fun open(context: Context, source: PdfSource): SealedPdf = withContext(Dispatchers.IO) {
            // A descriptor that decrypts as the renderer reads: no copy, any size.
            val streamed = try {
                decryptingDescriptor(context, source)
            } catch (e: Throwable) {
                null
            }
            if (streamed != null) {
                val document = try {
                    SealedPdf(PdfRenderer(streamed), streamed, source)
                } catch (e: Throwable) {
                    streamed.close()
                    throw e
                }
                return@withContext document.unlessCancelled()
            }
            // Fallback for phones where that isn't available: an anonymous in-memory file.
            val memory = inMemoryDescriptor(source)
            val document = try {
                SealedPdf(PdfRenderer(memory), memory, source)
            } catch (e: Throwable) {
                memory.close()
                throw e
            }
            document.unlessCancelled()
        }

        /**
         * Opening can't be interrupted part-way, so a caller who gave up (left the screen while
         * the spinner showed) would never get the document back to close it. Hand it over only
         * if they are still waiting; otherwise close it here, with its descriptor and its key.
         */
        private suspend fun SealedPdf.unlessCancelled(): SealedPdf {
            if (!coroutineContext.isActive) {
                close()
                throw CancellationException("The document was closed before it finished opening")
            }
            return this
        }

        private fun decryptingDescriptor(context: Context, source: PdfSource): ParcelFileDescriptor {
            val channel = source.open()
            val thread = HandlerThread("obscura-pdf").apply { start() }
            return try {
                context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        override fun onGetSize(): Long = source.size

                        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                            // Stops the moment the vault locks, which also drops the file key
                            // the decrypting channel holds.
                            if (!source.stillOpen()) {
                                runCatching { channel.close() }
                                throw ErrnoException("read", OsConstants.EIO)
                            }
                            if (offset >= source.size) return 0
                            return try {
                                synchronized(channel) {
                                    channel.position(offset)
                                    val target = ByteBuffer.wrap(data, 0, size)
                                    while (target.hasRemaining()) if (channel.read(target) <= 0) break
                                    size - target.remaining()
                                }
                            } catch (e: Exception) {
                                throw ErrnoException("read", OsConstants.EIO)
                            }
                        }

                        override fun onRelease() {
                            runCatching { channel.close() }
                            thread.quitSafely()
                        }
                    },
                    Handler(thread.looper),
                )
            } catch (e: Throwable) {
                runCatching { channel.close() }
                thread.quitSafely()
                throw e
            }
        }

        private fun inMemoryDescriptor(source: PdfSource): ParcelFileDescriptor {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "PDF preview needs Android 11 or newer on this phone" }
            require(source.size <= MAX_IN_MEMORY) { "this PDF is too large to preview on this phone" }
            val memfd = Os.memfd_create("obscura-doc", 0)
            try {
                // FileOutputStream over the raw descriptor; it does not own or close it.
                val out = FileOutputStream(memfd)
                source.open().use { channel -> Channels.newInputStream(channel).copyTo(out, 64 * 1024) }
                out.flush()
                Os.lseek(memfd, 0, OsConstants.SEEK_SET)
                return ParcelFileDescriptor.dup(memfd)
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
