package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: puts a known vault on the device for the GIF and month-jump journeys that are
 * driven by hand with uiautomator — a video long enough that making a GIF of it can be
 * interrupted, and photos spread over fourteen months so there is somewhere to jump to.
 *
 *   adb shell am instrument -w -e class com.shahaabapps.obscura.GifAndMonthSeed \
 *       com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The vault is left locked, with passphrase "lantern orbit velvet canyon mosaic" and PIN 135790.
 */
@RunWith(AndroidJUnit4::class)
class GifAndMonthSeed {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)

    private fun millis(y: Int, m: Int, d: Int) =
        LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(color) }
            .compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun encodeMp4(seconds: Int, width: Int = 720, height: Int = 480): ByteArray {
        val file = File(context.cacheDir, "seed.mp4")
        val fps = 20
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
        repeat(fps * seconds) { frame ->
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(20 + frame % 60, 91, 255 - frame % 60))
            canvas.drawCircle(40f + (frame % 30) * 22f, height / 2f, 80f, paint)
            surface.unlockCanvasAndPost(canvas)
            Thread.sleep(1000L / fps)
            drain(end = false)
        }
        encoder.signalEndOfInputStream()
        drain(end = true)
        encoder.stop(); encoder.release(); surface.release()
        muxer.stop(); muxer.release()
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun seed() = runBlocking {
        instrumentation.runOnMainSync {
            entry.session().lock()
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
        entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
        entry.session().setPin("135790".toCharArray())
        val vault = entry.session().openVault!!

        // Fourteen months of photos, so the month list spans two years and no month name repeats
        // within a year. A couple of months hold a video so the filter has something to show.
        val video = encodeMp4(8)
        var year = 2024
        var month = 9
        repeat(14) { index ->
            repeat(3) { within ->
                vault.files.put(
                    jpeg(Color.rgb(30 + index * 14, 90 + within * 30, 220 - index * 12)).inputStream(),
                    IncomingFile("p$year-$month-$within.jpg", "image/jpeg", millis(year, month, 3 + within * 7), 160, 160, null),
                )
            }
            if (index == 2 || index == 13) {
                vault.files.put(
                    video.inputStream(),
                    IncomingFile("clip-$year-$month.mp4", "video/mp4", millis(year, month, 20), 720, 480, 8_000),
                )
            }
            month++
            if (month > 12) { month = 1; year++ }
        }
        println("SEEDED ${vault.database.files().all().size} files")
        entry.session().lock()
    }
}
