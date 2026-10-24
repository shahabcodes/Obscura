package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.shahaabapps.obscura.ui.components.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The bundled Lottie files load cleanly and draw what the Prism motion kit specifies. Also writes
 * a contact sheet (six frames of each) to the app's external files folder for a visual check.
 */
@RunWith(AndroidJUnit4::class)
class MotionKitTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Design size and length of each animation. */
    private val spec = mapOf(
        Motion.UNLOCK to Triple(120, 120, 1200f),
        Motion.SEAL to Triple(120, 120, 2200f),
        Motion.FAVORITE to Triple(120, 120, 900f),
        Motion.SCAN to Triple(160, 160, 2200f),
        Motion.SUCCESS to Triple(120, 120, 1400f),
        Motion.EMPTY to Triple(140, 120, 3000f),
        Motion.DECRYPT to Triple(120, 80, 1300f),
        Motion.PANIC to Triple(120, 120, 600f),
    )

    private fun load(motion: Motion): LottieComposition {
        val result = LottieCompositionFactory.fromRawResSync(context, motion.res)
        assertNull("${motion.name} failed to parse: ${result.exception}", result.exception)
        return requireNotNull(result.value)
    }

    /** Renders at [scale]× the design size, so design point (x, y) is pixel (x·scale, y·scale). */
    private fun render(motion: Motion, progress: Float, scale: Int = 4): Bitmap {
        val (w, h) = spec.getValue(motion)
        val bitmap = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LottieDrawable().apply {
                setComposition(load(motion))
                this.progress = progress
                setBounds(0, 0, bitmap.width, bitmap.height)
                draw(Canvas(bitmap))
            }
        }
        return bitmap
    }

    private fun pixel(bitmap: Bitmap, x: Float, y: Float, scale: Int = 4) = bitmap.getPixel((x * scale).toInt(), (y * scale).toInt())

    private fun assertColor(what: String, expected: Long, actual: Int) {
        val e = expected.toInt()
        val close = Color.alpha(actual) > 240 &&
            abs(Color.red(actual) - Color.red(e)) < 12 && abs(Color.green(actual) - Color.green(e)) < 12 && abs(Color.blue(actual) - Color.blue(e)) < 12
        assertTrue("$what: expected #%06X, got #%08X".format(e and 0xFFFFFF, actual), close)
    }

    private fun assertClear(what: String, actual: Int) = assertTrue("$what: expected transparent, got #%08X".format(actual), Color.alpha(actual) < 16)

    @Test
    fun everyAnimationLoadsWithoutWarnings() {
        assertEquals(Motion.entries.toSet(), spec.keys)
        for (motion in Motion.entries) {
            val composition = load(motion)
            assertTrue("${motion.name} warnings: ${composition.warnings}", composition.warnings.isEmpty())
            assertEquals(motion.name, spec.getValue(motion).third, composition.duration, 20f)
            assertTrue(motion.name, composition.layers.isNotEmpty())
        }
    }

    @Test
    fun unlockLiftsTheShackleAndLightsTheBody() {
        val start = render(Motion.UNLOCK, 0f)
        val end = render(Motion.UNLOCK, 1f)
        assertColor("body before", 0xFFF5F5F0, pixel(start, 60f, 96f))
        assertColor("body after", 0xFFFFE14D, pixel(end, 60f, 96f))
        assertColor("keyhole", 0xFF3B5BFF, pixel(end, 60f, 78f))
        assertColor("right leg in the body", 0xFFF5F5F0, pixel(start, 80f, 52f))
        assertClear("right leg lifted out", pixel(end, 80f, 52f))
    }

    @Test
    fun sealKeepsTheVaultBoxAndHole() {
        val frame = render(Motion.SEAL, 0.42f)
        assertColor("box", 0xFF0B0B0C, pixel(frame, 30f, 100f))
        assertColor("hole", 0xFFFFE14D, pixel(frame, 60f, 88f))
    }

    @Test
    fun favoriteBurstsThenFades() {
        assertColor("heart mid-burst", 0xFFFF7AB6, pixel(render(Motion.FAVORITE, 0.5f), 60f, 62f))
        assertClear("heart gone at the end", pixel(render(Motion.FAVORITE, 1f), 60f, 62f))
    }

    @Test
    fun scanDrawsTheRadarDisc() {
        assertColor("disc", 0xFF0B0B0C, pixel(render(Motion.SCAN, 0f), 80f, 140f))
        assertClear("outside the disc", pixel(render(Motion.SCAN, 0f), 4f, 4f))
    }

    @Test
    fun successEndsWithDiscAndCheck() {
        val end = render(Motion.SUCCESS, 1f)
        assertColor("disc", 0xFF5EF0C2, pixel(end, 60f, 34f))
        assertColor("check", 0xFF0B0B0C, pixel(end, 53f, 76f))
        assertClear("nothing yet at the start", pixel(render(Motion.SUCCESS, 0f), 53f, 76f))
    }

    @Test
    fun emptyShowsAllThreeShapes() {
        val frame = render(Motion.EMPTY, 0f)
        assertColor("circle", 0xFF3B5BFF, pixel(frame, 40f, 50f))
        assertColor("square", 0xFFFF7AB6, pixel(frame, 93f, 45f))
        assertColor("triangle", 0xFF0B0B0C, pixel(frame, 80f, 80f))
    }

    @Test
    fun decryptShowsRevealedAndShimmeringTiles() {
        val frame = render(Motion.DECRYPT, 0f)
        assertColor("revealed tile", 0xFF3B5BFF, pixel(frame, 60f, 20f))
        assertColor("revealed tile", 0xFFFF7AB6, pixel(frame, 21f, 59f))
        assertColor("tile still decrypting", 0xFFD6D6CF, pixel(frame, 21f, 20f))
    }

    @Test
    fun panicEndsOnTheBolt() {
        assertColor("bolt", 0xFF0B0B0C, pixel(render(Motion.PANIC, 1f), 52f, 60f))
    }

    @Test
    fun writeContactSheet() {
        val backgrounds = mapOf(
            Motion.UNLOCK to 0xFF3B5BFF, Motion.SEAL to 0xFFFFE14D, Motion.FAVORITE to 0xFF0B0B0C, Motion.SCAN to 0xFFFF8A3D,
            Motion.SUCCESS to 0xFF0B0B0C, Motion.EMPTY to 0xFF5EF0C2, Motion.DECRYPT to 0xFFF5F5F0, Motion.PANIC to 0xFFFF7AB6,
        )
        val steps = listOf(0f, 0.15f, 0.3f, 0.5f, 0.75f, 1f)
        val cell = 340
        val sheet = Bitmap.createBitmap(cell * steps.size, cell * Motion.entries.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val paint = Paint()
        Motion.entries.forEachIndexed { row, motion ->
            steps.forEachIndexed { col, progress ->
                paint.color = backgrounds.getValue(motion).toInt()
                canvas.drawRect((col * cell + 6).toFloat(), (row * cell + 6).toFloat(), ((col + 1) * cell - 6).toFloat(), ((row + 1) * cell - 6).toFloat(), paint)
                val frame = render(motion, progress, scale = 2)
                canvas.drawBitmap(frame, col * cell + (cell - frame.width) / 2f, row * cell + (cell - frame.height) / 2f, null)
            }
        }
        val out = File(context.getExternalFilesDir(null), "motion-sheet.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(out.length().takeIf { it > 0 })
    }
}
