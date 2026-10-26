package com.shahaabapps.obscura.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.shahaabapps.obscura.crypto.FileCipher
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small previews for the gallery. Each thumbnail is generated once from the sealed file,
 * encrypted with that file's own key (as a separate "thumbnail" part) and stored under
 * `vault/thumbs`. Decoded bitmaps live only in an in-memory cache that is emptied on lock.
 */
class ThumbnailStore(
    private val store: VaultStore,
    private val cipher: FileCipher,
    private val files: FileStore,
) {
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val generating = Mutex()

    @Volatile
    private var closed = false

    /** Cached or stored thumbnail, generating it on first use. Null for files without a preview. */
    suspend fun thumbnail(file: VaultFile): Bitmap? {
        cache.get(file.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            if (closed) return@withContext null
            // The vault can lock between that check and the work below, and then there is no key
            // to read or write a thumbnail with. There is nothing to show once it has locked, and
            // the failure must not travel up into whichever screen asked for the picture.
            try {
                readStored(file) ?: generating.withLock {
                    cache.get(file.id) ?: generate(file)?.also { save(file, it) }
                }
            } catch (e: IllegalStateException) {
                null
            }
        }?.also { if (!closed) cache.put(file.id, it) }
    }

    /** A photo decoded for full-screen viewing, scaled down to fit [maxSide] pixels. Not cached. */
    suspend fun display(file: VaultFile, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        // A damaged or missing blob shows as "no preview" rather than crashing the viewer.
        try {
            when (file.kind) {
                FileKind.PHOTO, FileKind.GIF -> decodeImage(file, maxSide)
                FileKind.VIDEO -> videoFrame(file, maxSide)
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun delete(id: String) {
        cache.remove(id)
        thumbFile(id).delete()
    }

    /** Called on lock: drop every decoded preview from memory. */
    fun clear() {
        closed = true
        cache.evictAll()
    }

    private fun readStored(file: VaultFile): Bitmap? {
        val stored = thumbFile(file.id)
        if (!stored.isFile) return null
        return try {
            cipher.decryptingStream(file.wrappedKey, file.id, stored.inputStream().buffered(), FileCipher.PART_THUMBNAIL)
                .use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            stored.delete() // unreadable thumbnail: regenerate next time
            null
        }
    }

    private fun save(file: VaultFile, bitmap: Bitmap) {
        val target = thumbFile(file.id)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${file.id}.part")
        try {
            temp.outputStream().buffered().use { raw ->
                cipher.encryptingStream(file.wrappedKey, file.id, raw, FileCipher.PART_THUMBNAIL).use { sealed ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, sealed)
                }
            }
            temp.renameTo(target)
        } finally {
            temp.delete()
        }
    }

    private fun generate(file: VaultFile): Bitmap? = try {
        when (file.kind) {
            FileKind.PHOTO, FileKind.GIF -> decodeImage(file, THUMB_SIDE)
            FileKind.VIDEO -> videoFrame(file, THUMB_SIDE)
            FileKind.AUDIO -> embeddedArt(file)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeImage(file: VaultFile, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val decoded = open(file).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = try {
            open(file).use { ExifInterface(it).rotationDegrees }
        } catch (e: Exception) {
            0
        }
        val scaled = scaleToFit(decoded, maxSide)
        return if (rotation == 0) scaled else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    private fun videoFrame(file: VaultFile, maxSide: Int): Bitmap? = withRetriever(file) { retriever ->
        val frame = retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            ?: retriever.frameAtTime
        frame?.let { scaleToFit(it, maxSide) }
    }

    private fun embeddedArt(file: VaultFile): Bitmap? = withRetriever(file) { retriever ->
        retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { scaleToFit(it, THUMB_SIDE) }
    }

    private fun <T> withRetriever(file: VaultFile, block: (MediaMetadataRetriever) -> T?): T? {
        val source = EncryptedMediaDataSource(cipher, file, files.blobFile(file.id))
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            block(retriever)
        } finally {
            retriever.release()
            source.close()
        }
    }

    private fun open(file: VaultFile): InputStream = files.open(file)

    private fun thumbFile(id: String) = File(File(store.thumbDir, id.take(2)), id)

    companion object {
        const val THUMB_SIDE = 384

        private fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt()

        /**
         * Scales down keeping the aspect ratio. Full-screen images fit the longer side within
         * [side]; thumbnails ([THUMB_SIDE]) cover it with the shorter side, since the grid crops squares.
         */
        fun scaleToFit(bitmap: Bitmap, side: Int): Bitmap {
            val reference = if (side == THUMB_SIDE) minOf(bitmap.width, bitmap.height) else maxOf(bitmap.width, bitmap.height)
            if (reference <= side) return bitmap
            val ratio = side.toFloat() / reference
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        }
    }
}
