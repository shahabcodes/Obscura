package com.shahaabapps.obscura.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GIF this app writes, read back by Android's own decoder. That is the judge that matters:
 * a decoder written from the same assumptions as the encoder would agree with its mistakes.
 */
@RunWith(AndroidJUnit4::class)
class GifOnDeviceTest {

    private fun encode(width: Int, height: Int, palette: IntArray, frames: List<ByteArray>, delayCentis: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        val gif = GifEncoder(out, width, height, palette)
        frames.forEach { gif.addFrame(it, delayCentis) }
        gif.finish()
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray) =
        // Software pixels, so the frames can be drawn into a bitmap and inspected here.
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun firstFrame(bytes: ByteArray): Bitmap {
        val drawable = decode(bytes)
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun assertClose(expected: Int, actual: Int, where: String) {
        fun channel(shift: Int) = abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
        assertTrue(
            "$where: expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF),
            channel(16) <= 4 && channel(8) <= 4 && channel(0) <= 4,
        )
    }

    @Test
    fun androidCanReadWhatWeWrite() {
        val palette = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)
        val width = 8
        val height = 4
        val pixels = ByteArray(width * height) { (it % 4).toByte() }
        val bitmap = firstFrame(encode(width, height, palette, listOf(pixels)))

        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertClose(palette[(y * width + x) % 4], bitmap.getPixel(x, y), "pixel $x,$y")
            }
        }
    }

    /**
     * A big, noisy frame is what forces the compressor to widen its codes and then start over
     * when its dictionary fills. Getting that wrong gives a corrupt file that still looks
     * plausible, so it is checked pixel by pixel.
     */
    @Test
    fun aLargeNoisyFrameSurvivesTheDictionaryFillingUp() {
        val width = 320
        val height = 240
        val palette = IntArray(256) { (it shl 16) or (it shl 8) or it }
        val random = Random(11)
        val pixels = ByteArray(width * height) { random.nextInt(256).toByte() }
        val bitmap = firstFrame(encode(width, height, palette, listOf(pixels)))

        assertEquals(width, bitmap.width)
        var wrong = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expected = palette[pixels[y * width + x].toInt() and 0xFF]
                if (bitmap.getPixel(x, y) and 0xFFFFFF != expected and 0xFFFFFF) wrong++
            }
        }
        assertEquals("pixels that came back wrong", 0, wrong)
    }

    @Test
    fun severalFramesMakeAnAnimationThatLoops() {
        val palette = intArrayOf(0x000000, 0xFFFFFF)
        val frames = List(6) { frame -> ByteArray(16) { (if (frame % 2 == 0) 0 else 1).toByte() } }
        val drawable = decode(encode(4, 4, palette, frames, delayCentis = 5))
        assertTrue("an animation, not a still: ${drawable.javaClass.simpleName}", drawable is AnimatedImageDrawable)
        assertEquals(4, drawable.intrinsicWidth)
    }

    @Test
    fun aSingleFrameIsStillAValidPicture() {
        val drawable = decode(encode(2, 2, intArrayOf(0x112233), listOf(ByteArray(4))))
        assertTrue(drawable is BitmapDrawable || drawable is AnimatedImageDrawable)
    }

    /** What the app will actually make: real photo-like colours through the quantizer. */
    @Test
    fun aPhotoLikeFrameRoundTripsThroughTheQuantizer() {
        val width = 64
        val height = 48
        val source = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            0xFF000000.toInt() or ((x * 4) shl 16) or ((y * 5) shl 8) or ((x + y) * 2)
        }
        val quantizer = Quantizer()
        quantizer.sample(source)
        val palette = quantizer.palette()
        val indices = ByteArray(source.size)
        Quantizer.Mapper(palette).map(source, width, indices)

        val bitmap = firstFrame(encode(width, height, palette, listOf(indices)))
        var worst = 0
        var total = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expected = source[y * width + x]
                val actual = bitmap.getPixel(x, y)
                fun channel(shift: Int) = abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
                val off = maxOf(channel(16), maxOf(channel(8), channel(0)))
                worst = maxOf(worst, off)
                total += off
            }
        }
        // Dithering trades single pixels for the whole: one pixel may be pushed well off its own
        // colour so that its neighbourhood averages out to the shade that was really there. So
        // the average is what must stay tight, while a lone pixel is allowed to stray.
        val average = total.toDouble() / (width * height)
        assertTrue("average channel was off by $average", average <= 8.0)
        assertTrue("worst channel was off by $worst", worst <= 64)
    }
}
