package com.shahaabapps.obscura.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The structure of the file the GIF writer produces, and the colour choosing. That the pixels
 * themselves survive is proven against Android's own decoder in GifOnDeviceTest, rather than
 * against a decoder written from the same assumptions as the encoder.
 */
class GifEncoderTest {

    private fun encode(width: Int, height: Int, palette: IntArray, frames: List<ByteArray>, delay: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        val gif = GifEncoder(out, width, height, palette)
        frames.forEach { gif.addFrame(it, delay) }
        gif.finish()
        return out.toByteArray()
    }

    @Test
    fun theFileLooksLikeAGifAndSaysHowBigItIs() {
        val bytes = encode(4, 3, intArrayOf(0xFF0000, 0x00FF00), listOf(ByteArray(12)))
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(4, (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8))
        assertEquals(3, (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8))
        assertTrue("global colour table flag", bytes[10].toInt() and 0x80 != 0)
        assertEquals(0x3B.toByte(), bytes.last()) // trailer
        assertTrue("loops for ever", String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    }

    @Test
    fun everyFrameIsWritten() {
        val frames = List(5) { ByteArray(12) { _ -> 0 } }
        val bytes = encode(4, 3, intArrayOf(0x000000, 0xFFFFFF), frames)
        assertEquals(5, bytes.count { it == 0x2C.toByte() }) // one image descriptor each
    }

    @Test
    fun theColoursChosenAreTheOnesInThePicture() {
        val quantizer = Quantizer(maxColours = 4)
        // Three strong colours, many pixels each.
        val pixels = IntArray(300) { i ->
            when (i % 3) {
                0 -> 0xFFFF0000.toInt()
                1 -> 0xFF00FF00.toInt()
                else -> 0xFF0000FF.toInt()
            }
        }
        quantizer.sample(pixels)
        val palette = quantizer.palette()
        assertTrue("got ${palette.size}", palette.size in 3..4)

        val mapped = ByteArray(pixels.size)
        Quantizer.Mapper(palette).map(pixels, mapped)
        // Each original colour maps to a palette entry that is close to it.
        for (i in pixels.indices) {
            val chosen = palette[mapped[i].toInt() and 0xFF]
            val original = pixels[i]
            fun channel(shift: Int) = ((chosen shr shift) and 0xFF) - ((original shr shift) and 0xFF)
            val distance = channel(16) * channel(16) + channel(8) * channel(8) + channel(0) * channel(0)
            assertTrue("pixel $i landed far away", distance < 3 * 20 * 20)
        }
    }

    @Test
    fun aPlainPictureStillWorksWithOneColour() {
        val quantizer = Quantizer()
        quantizer.sample(IntArray(50) { 0xFF123456.toInt() })
        val palette = quantizer.palette()
        assertEquals(1, palette.size)
        val bytes = encode(5, 10, palette, listOf(ByteArray(50)))
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), bytes.last())
    }
}

/** The size and shape of what gets made, and the limits that keep it from exhausting memory. */
class GifSizeTest {

    @Test
    fun anOrdinaryVideoKeepsItsShape() {
        assertEquals(480 to 270, VideoToGif.frameSize(1920, 1080, 480))
        assertEquals(480 to 640, VideoToGif.frameSize(1080, 1440, 480))
        // Smaller than asked for: left alone rather than blown up.
        assertEquals(320 to 240, VideoToGif.frameSize(320, 240, 480))
    }

    @Test
    fun anAbsurdlyTallVideoIsScaledDownWholeRatherThanMakingAHugeFrame() {
        val (width, height) = VideoToGif.frameSize(480, 60_000, 480)
        assertTrue("$width x $height", width.toLong() * height <= GifRequest.MAX_PIXELS)
        assertTrue("still far taller than wide", height > width * 10)
        assertTrue(width >= 2 && height >= 2)
    }

    @Test
    fun nonsenseVideoSizesStillGiveSomethingUsable() {
        for (size in listOf(VideoToGif.frameSize(0, 0, 480), VideoToGif.frameSize(-4, 10, 480), VideoToGif.frameSize(10, 0, 64))) {
            assertTrue("$size", size.first >= 2 && size.second >= 2)
            assertTrue("$size", size.first.toLong() * size.second <= GifRequest.MAX_PIXELS)
        }
    }

    @Test
    fun aRequestThatMakesNoSenseIsRefused() {
        for (bad in listOf({ GifRequest(0, 1_000, fps = 0) }, { GifRequest(0, 1_000, fps = -5) }, { GifRequest(0, 0) }, { GifRequest(0, 1_000, maxWidth = 4) })) {
            try {
                bad()
                org.junit.Assert.fail("expected this request to be refused")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aFrameLargerThanAnyScreenIsRefusedByTheWriter() {
        try {
            GifEncoder(ByteArrayOutputStream(), 60_000, 60_000, intArrayOf(0))
            org.junit.Assert.fail("expected the writer to refuse it")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
