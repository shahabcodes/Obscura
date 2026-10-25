package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * Release checks for the new file kind: that a real animated GIF really animates from vault
 * bytes, and what holding several of them in memory at once costs. Fixtures come from
 * /sdcard/Download/ObscuraTest (anim.gif, big.gif, huge.gif, broken.gif).
 */
@RunWith(AndroidJUnit4::class)
class ReleaseGifAndReelTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private val dir = File(context.filesDir, "fixtures")

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before fun setUp() = wipe()
    @After fun tearDown() = wipe()

    private suspend fun seal(vararg names: Pair<String, String>): List<VaultFile> {
        entry.session().create(passphrase.toCharArray())
        val vault = entry.session().openVault!!
        return names.mapIndexed { i, (name, mime) ->
            val f = File(dir, name)
            org.junit.Assume.assumeTrue("fixture $name at $f is not on this device; push it to run this", f.exists())
            vault.files.put(f.inputStream(), IncomingFile(name, mime, 1_700_000_000_000L - i * 60_000L))
        }
    }

    private suspend fun bytesOf(file: VaultFile): ByteArray =
        entry.session().openVault!!.files.open(file).use { it.readBytes() }

    /** Draws [d] into a fresh bitmap, downscaled, and returns it. */
    private fun frameOf(d: AnimatedImageDrawable, side: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, side, side)
        d.draw(canvas)
        return bmp
    }

    private fun differs(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (x in 0 until a.width) for (y in 0 until a.height) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    /**
     * The headline claim: a GIF sealed in the vault decodes to an animation and the frames
     * actually move. Exercises exactly the decode AnimatedGif.kt does (whole file in memory,
     * ImageDecoder over a ByteBuffer).
     */
    @Test
    fun aSealedGifDecodesToAnAnimationAndMoves() = runBlocking {
        val gif = seal("anim.gif" to "image/gif").single()
        assertEquals(FileKind.GIF, gif.kind)
        val bytes = bytesOf(gif)
        assertEquals(File(dir, "anim.gif").length(), bytes.size.toLong())

        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertTrue("decoded a ${drawable::class.java.simpleName}, not an animation", drawable is AnimatedImageDrawable)
        val anim = drawable as AnimatedImageDrawable

        anim.start()
        val first = frameOf(anim)
        var moved = 0
        repeat(12) {
            Thread.sleep(120)
            val next = frameOf(anim)
            moved = maxOf(moved, differs(first, next))
        }
        assertTrue("the GIF never changed frame while running (max pixels changed: $moved)", moved > 50)

        // Stopping is what the viewer relies on when it goes off screen.
        anim.stop()
        val a = frameOf(anim)
        Thread.sleep(500)
        assertEquals("the GIF kept animating after stop()", 0, differs(a, frameOf(anim)))
    }

    /** A truncated GIF must fail softly, the way AnimatedGif.kt's runCatching intends. */
    @Test
    fun aDamagedGifDoesNotCrashTheDecoder() = runBlocking {
        val gif = seal("broken.gif" to "image/gif").single()
        val bytes = bytesOf(gif)
        val result = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }
        println("BROKEN decode: success=${result.isSuccess} ${result.exceptionOrNull()}")
        // Whatever the decoder decides, the grid thumbnail path must not throw either.
        val vault = entry.session().openVault!!
        println("BROKEN thumbnail: ${vault.thumbnails.thumbnail(gif)}")
    }

    /**
     * What the reel and the viewer do when they page across GIFs: beyondViewportPageCount = 1
     * means three pages are composed, so up to three whole GIFs are decrypted into the heap and
     * three decoders are alive. This measures that against the heap growth limit.
     */
    @Test
    fun threeLargeGifsAtOnceStayWithinTheHeap() = runBlocking {
        val files = seal(
            "huge.gif" to "image/gif",
            "huge.gif" to "image/gif",
            "huge.gif" to "image/gif",
        )
        val limitMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
        println("HEAP limit=${limitMb}MB fixture=${File(dir, "huge.gif").length() / 1024 / 1024}MB")

        val held = mutableListOf<Pair<ByteArray, AnimatedImageDrawable>>()
        try {
            files.forEach { f ->
                val bytes = bytesOf(f)
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) as AnimatedImageDrawable
                d.start()
                // Draw once so the decoder allocates its frame buffers, as the ImageView does.
                frameOf(d, 512)
                held += bytes to d
                val heapMb = (Debug.getNativeHeapAllocatedSize() / 1024 / 1024)
                val javaMb = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1024 / 1024)
                println("HEAP after ${held.size} GIFs: java=${javaMb}MB native=${heapMb}MB intrinsic=${d.intrinsicWidth}x${d.intrinsicHeight}")
            }
        } catch (e: OutOfMemoryError) {
            throw AssertionError("OutOfMemoryError holding ${held.size + 1} GIFs of ${File(dir, "huge.gif").length() / 1024 / 1024}MB (heap limit ${limitMb}MB)", e)
        }
        assertEquals(3, held.size)
        held.forEach { it.second.stop() }
    }

    /**
     * One very large GIF, opened the way the viewer opens it: the whole file into a ByteArray,
     * then ImageDecoder over it. Nothing in that path caps the size or samples the frames down,
     * so this is the shape of the worst case a real phone can hand the app.
     */
    @Test
    fun oneVeryLargeGifDoesNotExhaustTheHeap() = runBlocking {
        val fixture = File(dir, "giant.gif")
        org.junit.Assume.assumeTrue("giant.gif fixture not pushed", fixture.exists())
        val gif = seal("giant.gif" to "image/gif").single()
        val limitMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
        println("GIANT file=${fixture.length() / 1024 / 1024}MB heapLimit=${limitMb}MB")
        try {
            val bytes = bytesOf(gif)
            val javaMb = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1024 / 1024
            println("GIANT after read: java=${javaMb}MB bytes=${bytes.size / 1024 / 1024}MB")
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                as AnimatedImageDrawable
            d.start()
            frameOf(d, 1024)
            println(
                "GIANT after decode: java=" +
                    "${Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1024 / 1024}MB " +
                    "native=${Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB ${d.intrinsicWidth}x${d.intrinsicHeight}",
            )
            d.stop()
        } catch (e: OutOfMemoryError) {
            throw AssertionError(
                "OutOfMemoryError opening a ${fixture.length() / 1024 / 1024}MB GIF: the viewer " +
                    "reads the whole file into memory (GalleryViewModel.bytes) and decodes it " +
                    "whole (AnimatedGif.kt), with no size limit anywhere",
                e,
            )
        }
    }

    /**
     * Two large GIFs held at the same time, which is what the viewer and the reel do: both use
     * beyondViewportPageCount = 1, so the neighbouring page's GIF is decrypted and decoded while
     * the current one is on screen.
     */
    @Test
    fun twoLargeGifsSideBySideStayWithinTheHeap() = runBlocking {
        val fixture = File(dir, "giant.gif")
        org.junit.Assume.assumeTrue("giant.gif fixture not pushed", fixture.exists())
        val files = seal("giant.gif" to "image/gif", "giant.gif" to "image/gif")
        val limitMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
        val held = mutableListOf<Pair<ByteArray, AnimatedImageDrawable>>()
        try {
            files.forEach { f ->
                val bytes = bytesOf(f)
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    as AnimatedImageDrawable
                d.start()
                frameOf(d, 512)
                held += bytes to d
                println(
                    "PAIR after ${held.size}: java=" +
                        "${Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1024 / 1024}MB " +
                        "of ${limitMb}MB",
                )
            }
        } catch (e: OutOfMemoryError) {
            throw AssertionError(
                "OutOfMemoryError with ${held.size + 1} GIFs of ${fixture.length() / 1024 / 1024}MB " +
                    "held at once (heap limit ${limitMb}MB). The viewer and the reel keep the " +
                    "neighbouring page composed, so two large GIFs are in memory together.",
                e,
            )
        } finally {
            held.forEach { it.second.stop() }
        }
    }

    /** The grid still needs a still preview for a GIF, and the viewer a full-size one. */
    @Test
    fun aGifStillHasAThumbnailAndADisplayBitmap() = runBlocking {
        val gif = seal("anim.gif" to "image/gif").single()
        val vault = entry.session().openVault!!
        assertNotNull("thumbnail", vault.thumbnails.thumbnail(gif))
        assertNotNull("display bitmap", vault.thumbnails.display(gif, 1024))
    }
}
