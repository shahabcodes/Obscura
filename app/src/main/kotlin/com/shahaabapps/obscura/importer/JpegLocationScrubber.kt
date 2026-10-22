package com.shahaabapps.obscura.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Removes location data from a JPEG while it streams into the vault, without ever writing
 * the photo to disk unencrypted. Only the metadata segments before the image data are
 * rewritten; the compressed pixels pass through untouched.
 *
 * - EXIF: every value in the GPS directory is zeroed and the directory is emptied.
 *   Other EXIF (date taken, camera, orientation) is kept.
 * - XMP: dropped entirely, because it can repeat the GPS position in text form.
 *
 * Anything that isn't a JPEG passes through unchanged.
 */
object JpegLocationScrubber {

    class Result(val stream: InputStream, val removedLocation: () -> Boolean)

    fun wrap(input: InputStream): Result {
        val first = ByteArray(2)
        val read = input.readNBytesCompat(first)
        if (read < 2 || first[0] != 0xFF.toByte() || first[1] != 0xD8.toByte()) {
            return Result(SequenceInputStream(ByteArrayInputStream(first, 0, read), input)) { false }
        }
        val header = ByteArrayOutputStream()
        header.write(first)
        var removed = false
        run {
            while (true) {
                val lead = input.read()
                if (lead < 0) break
                if (lead != 0xFF) {
                    header.write(lead)
                    break
                }
                // Fill bytes: any number of 0xFF may precede the marker code.
                var marker: Int
                do { marker = input.read() } while (marker == 0xFF)
                if (marker < 0) {
                    header.write(0xFF)
                    break
                }
                if (marker == 0xD9 || marker == 0xDA || marker in 0xD0..0xD7 || marker == 0x01) {
                    // End of image, start of scan or a standalone marker: everything after is pixel data.
                    header.write(0xFF)
                    header.write(marker)
                    break
                }
                val hi = input.read()
                val lo = if (hi < 0) -1 else input.read()
                if (hi < 0 || lo < 0) {
                    header.write(0xFF)
                    header.write(marker)
                    if (hi >= 0) header.write(hi)
                    break
                }
                val length = (hi shl 8) or lo
                val payload = ByteArray((length - 2).coerceAtLeast(0))
                val got = input.readNBytesCompat(payload)
                if (length < 2 || got != payload.size) {
                    // Truncated or malformed: keep exactly what was read and stop rewriting.
                    header.write(0xFF)
                    header.write(marker)
                    header.write(hi)
                    header.write(lo)
                    header.write(payload, 0, got)
                    break
                }

                if (marker == 0xE1 && payload.startsWith(XMP_ID)) {
                    removed = true
                    continue
                }
                if (marker == 0xE1 && payload.startsWith(EXIF_ID)) {
                    if (scrubGps(payload, EXIF_ID.size)) removed = true
                }
                header.write(0xFF)
                header.write(marker)
                header.write(hi)
                header.write(lo)
                header.write(payload)
            }
        }
        val wasRemoved = removed
        return Result(SequenceInputStream(ByteArrayInputStream(header.toByteArray()), input)) { wasRemoved }
    }

    /**
     * Zeroes the GPS directory inside an EXIF segment. [tiff] is where the TIFF header starts.
     * Returns true if a GPS directory was found. Every offset is bounds-checked; malformed
     * EXIF is left as it was.
     */
    internal fun scrubGps(data: ByteArray, tiff: Int): Boolean {
        if (data.size < tiff + 8) return false
        val little = when {
            data[tiff] == 'I'.code.toByte() && data[tiff + 1] == 'I'.code.toByte() -> true
            data[tiff] == 'M'.code.toByte() && data[tiff + 1] == 'M'.code.toByte() -> false
            else -> return false
        }
        fun u16(at: Int): Int = if (at < 0 || at + 2 > data.size) -1 else if (little)
            (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        else ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (at < 0 || at + 4 > data.size) -1 else if (little)
            (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
        else (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        fun zero(from: Int, count: Int) {
            if (from < 0 || count <= 0) return
            val end = minOf(data.size, from + count)
            for (i in from until end) data[i] = 0
        }

        val ifd0 = u32(tiff + 4)
        if (ifd0 < 0) return false
        val ifd0At = tiff + ifd0.toInt()
        val entries = u16(ifd0At)
        if (entries <= 0 || entries > 1000) return false
        var gpsOffset = -1L
        for (i in 0 until entries) {
            val entry = ifd0At + 2 + i * 12
            if (u16(entry) == GPS_IFD_TAG) gpsOffset = u32(entry + 8)
        }
        if (gpsOffset <= 0) return false

        val gpsAt = tiff + gpsOffset.toInt()
        val gpsEntries = u16(gpsAt)
        if (gpsEntries < 0 || gpsEntries > 1000) return false
        for (i in 0 until gpsEntries) {
            val entry = gpsAt + 2 + i * 12
            val type = u16(entry + 2)
            val count = u32(entry + 4)
            val size = (TYPE_SIZES[type] ?: 1) * count
            if (size > 4 && size < data.size) {
                val valueAt = u32(entry + 8)
                if (valueAt > 0) zero(tiff + valueAt.toInt(), size.toInt())
            }
        }
        // Empty the directory itself: entry count, all entries and the next-directory link.
        zero(gpsAt, 2 + gpsEntries * 12 + 4)
        return true
    }

    private const val GPS_IFD_TAG = 0x8825
    private val EXIF_ID = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val TYPE_SIZES = mapOf(1 to 1L, 2 to 1L, 3 to 2L, 4 to 4L, 5 to 8L, 6 to 1L, 7 to 1L, 8 to 2L, 9 to 4L, 10 to 8L, 11 to 4L, 12 to 8L)

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
