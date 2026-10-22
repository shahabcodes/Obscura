package com.shahaabapps.obscura.importer

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** What we learn about a file before sealing it: when it was taken and its dimensions. */
data class MediaInfo(
    val takenAt: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

/**
 * Reads dates and sizes straight from the source stream (EXIF for photos, container metadata
 * for video). Nothing is written to disk. Falls back to the file's modified time, then now.
 */
class MetadataReader(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {

    fun read(source: ImportSource): MediaInfo {
        val fallback = source.lastModified ?: now()
        return try {
            when {
                source.mimeType.startsWith("image/") -> readImage(source, fallback)
                source.mimeType.startsWith("video/") || source.mimeType.startsWith("audio/") -> readMedia(source, fallback)
                else -> MediaInfo(fallback)
            }
        } catch (e: Exception) {
            MediaInfo(fallback)
        }
    }

    private fun readImage(source: ImportSource, fallback: Long): MediaInfo {
        var taken: Long? = null
        var width: Int? = null
        var height: Int? = null
        try {
            source.open().use { stream ->
                val exif = ExifInterface(stream)
                taken = parseExifDate(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                )
                width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
                    ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
                    ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
            }
        } catch (e: Exception) {
            // Not every image has EXIF (PNG, GIF, screenshots): fall through to decoding bounds.
        }
        if (width == null || height == null) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.open().use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0) {
                width = options.outWidth
                height = options.outHeight
            }
        }
        return MediaInfo(taken ?: fallback, width, height)
    }

    private fun readMedia(source: ImportSource, fallback: Long): MediaInfo {
        val uri = source.uri ?: return MediaInfo(fallback)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val date = parseMediaDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            return MediaInfo(date ?: fallback, width, height, duration)
        } finally {
            retriever.release()
        }
    }

    companion object {
        /** EXIF dates are local time ("2025:08:14 18:32:00"), optionally with an offset ("+05:30"). */
        fun parseExifDate(value: String?, offset: String?): Long? {
            if (value.isNullOrBlank() || value.startsWith("0000")) return null
            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            format.timeZone = if (offset != null && offset.matches(Regex("[+-]\\d{2}:\\d{2}"))) TimeZone.getTimeZone("GMT$offset") else TimeZone.getDefault()
            format.isLenient = false
            return try {
                format.parse(value.trim())?.time
            } catch (e: Exception) {
                null
            }
        }

        /** MediaMetadataRetriever dates look like "20250814T183200.000Z" (UTC). */
        fun parseMediaDate(value: String?): Long? {
            if (value.isNullOrBlank() || value.startsWith("1904") || value.startsWith("1970")) return null
            for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
                try {
                    val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                    return format.parse(value.trim())?.time
                } catch (e: Exception) {
                    // try next pattern
                }
            }
            return null
        }
    }
}
