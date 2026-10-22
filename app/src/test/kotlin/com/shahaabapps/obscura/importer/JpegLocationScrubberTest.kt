package com.shahaabapps.obscura.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JpegLocationScrubberTest {

    /** Little-endian EXIF with Make="Pixel", a GPS directory (N, 51°30'26") and DateTimeOriginal-like ASCII. */
    private fun exifPayload(): ByteArray {
        val tiff = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        // IFD0 at 8: 2 entries
        tiff.putShort(2)
        tiff.putShort(0x010F).putShort(2).putInt(6).putInt(50) // Make -> offset 50
        tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(70) // GPS IFD -> offset 70
        tiff.putInt(0) // no next IFD
        tiff.position(50); tiff.put("Pixel\u0000".toByteArray())
        // GPS IFD at 70: 2 entries
        tiff.position(70)
        tiff.putShort(2)
        tiff.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0).put(0).put(0) // LatitudeRef inline
        tiff.putShort(2).putShort(5).putInt(3).putInt(120) // Latitude, 3 rationals at 120
        tiff.putInt(0)
        tiff.position(120)
        intArrayOf(51, 1, 30, 1, 26, 1).forEach { tiff.putInt(it) }
        return "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private val pixels = ByteArray(5000) { (it * 31).toByte() }

    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        out.write(segment(0xDB, ByteArray(65))) // quantisation table
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(pixels)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun scrub(bytes: ByteArray): Pair<ByteArray, Boolean> {
        val result = JpegLocationScrubber.wrap(bytes.inputStream())
        val out = result.stream.readBytes()
        return out to result.removedLocation()
    }

    @Test
    fun gpsValuesAreZeroedAndOtherExifKept() {
        val original = jpeg(segment(0xE1, exifPayload()))
        val (out, removed) = scrub(original)
        assertTrue(removed)
        assertEquals(original.size, out.size)
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("Make survives", text.contains("Pixel"))
        // The latitude rationals (51/1, 30/1, 26/1) must be gone.
        val lat = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply { intArrayOf(51, 1, 30, 1, 26, 1).forEach { putInt(it) } }.array()
        assertFalse(String(out, Charsets.ISO_8859_1).contains(String(lat, Charsets.ISO_8859_1)))
        // Pixel data is byte-for-byte identical.
        assertArrayEquals(pixels, out.copyOfRange(out.size - 2 - pixels.size, out.size - 2))
    }

    @Test
    fun xmpIsDropped() {
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta exif:GPSLatitude=\"51,30.4N\"/>".toByteArray(Charsets.ISO_8859_1)
        val original = jpeg(segment(0xE1, xmp))
        val (out, removed) = scrub(original)
        assertTrue(removed)
        assertFalse(String(out, Charsets.ISO_8859_1).contains("GPSLatitude"))
        assertEquals(original.size - xmp.size - 4, out.size)
    }

    @Test
    fun jpegWithoutLocationIsUnchanged() {
        val original = jpeg(segment(0xE0, "JFIF\u0000".toByteArray()))
        val (out, removed) = scrub(original)
        assertFalse(removed)
        assertArrayEquals(original, out)
    }

    @Test
    fun nonJpegPassesThrough() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + pixels
        val (out, removed) = scrub(png)
        assertFalse(removed)
        assertArrayEquals(png, out)
    }

    @Test
    fun truncatedJpegDoesNotCrash() {
        val original = jpeg(segment(0xE1, exifPayload()))
        val (out, _) = scrub(original.copyOf(40))
        assertEquals(40, out.size)
    }

    @Test
    fun tinyInputsPassThrough() {
        assertArrayEquals(ByteArray(0), scrub(ByteArray(0)).first)
        assertArrayEquals(byteArrayOf(1), scrub(byteArrayOf(1)).first)
    }
}
