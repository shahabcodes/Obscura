package com.shahaabapps.obscura.importer

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A video shot holding the phone upright is stored as a landscape picture plus a quarter turn.
 * What Obscura records has to be the shape you see, or the player draws it on its side while it
 * is still reading the file.
 */
@RunWith(AndroidJUnit4::class)
class RotatedVideoImportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private class FileSource(
        private val file: File,
        override val displayName: String,
        override val mimeType: String = "video/mp4",
    ) : ImportSource {
        override val sizeBytes: Long = file.length()
        override val lastModified: Long = 1_600_000_000_000
        override val uri: Uri = Uri.fromFile(file)
        override fun open(): InputStream = ByteArrayInputStream(file.readBytes())
        override fun deleteOriginal(): Boolean = true
    }

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        // Closing the index leaves work queued on the main thread. It has to run before the
        // files it points at are deleted, or Room reopens a vault that is no longer there and
        // brings the whole process down with it.
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            entry.session().reload()
        }
    }

    @Before fun setUp() = wipe()

    @After fun tearDown() = wipe()

    /** An MP4 coded [width] by [height], carrying an orientation hint of [rotation] degrees. */
    private fun encodeMp4(name: String, width: Int, height: Int, rotation: Int): File {
        val file = File(context.cacheDir, name)
        val fps = 10
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, data, info)
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val paint = Paint().apply { color = Color.rgb(255, 225, 77) }
        repeat(fps) { frame ->
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(59, 91, 255))
            canvas.drawCircle(20f + frame * 9f, height / 2f, 30f, paint)
            surface.unlockCanvasAndPost(canvas)
            Thread.sleep(1000L / fps)
            drain(end = false)
        }
        encoder.signalEndOfInputStream()
        drain(end = true)
        encoder.stop(); encoder.release(); surface.release()
        muxer.stop(); muxer.release()
        return file
    }

    @Test
    fun anUprightVideoIsRecordedUpright(): Unit = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val upright = encodeMp4("upright.mp4", width = 640, height = 360, rotation = 90)
        val flat = encodeMp4("flat.mp4", width = 640, height = 360, rotation = 0)

        val sources = listOf(FileSource(upright, "upright.mp4"), FileSource(flat, "flat.mp4"))
        assertTrue(manager().start(sources, ImportOptions(), startService = false))
        awaitImport()

        val files = entry.session().openVault!!.database.files().all().associateBy { it.name }
        // The turned one is recorded as the tall picture it is seen as...
        assertEquals(360, files.getValue("upright.mp4").width)
        assertEquals(640, files.getValue("upright.mp4").height)
        // ...while one with no turn is left exactly as it was coded.
        assertEquals(640, files.getValue("flat.mp4").width)
        assertEquals(360, files.getValue("flat.mp4").height)

        upright.delete()
        flat.delete()
    }

    private fun manager() = entry.importManager()

    private fun awaitImport(timeoutMs: Long = 60_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val progress = manager().progress.value
            if (progress.finished && !progress.running) return
            Thread.sleep(50)
        }
        throw AssertionError("the import did not finish: ${manager().progress.value}")
    }
}
