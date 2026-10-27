package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.GallerySettings
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The vault the video-length journeys are run against, and the clips in it.
 *
 * The clips are really encoded and really sealed, so the lengths the filter is held against are
 * the ones the vault read off the files rather than numbers a test wrote into the index. Frames
 * are stamped from the frame rate, not from the wall clock, so a loaded emulator cannot turn a
 * two-second clip into a fifteen-second one and leave the thing under test meaningless.
 *
 * Shared, because more than one journey file needs the same vault and nothing about building it
 * belongs to any one of them.
 */
object VideoLengthFixture {

    const val PASSPHRASE = "lantern orbit velvet canyon mosaic"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)

    fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
            .compress(Bitmap.CompressFormat.JPEG, 80, it)
    }.toByteArray()

    /** A real H.264 MP4 of exactly [seconds] seconds. */
    fun encodeMp4(seconds: Int, name: String): File {
        val file = File(context.cacheDir, name)
        val width = 160
        val height = 128
        val fps = 10
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain() {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, data, info)
                        val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                        if (done) return
                    }
                }
            }
        }
        val frames = fps * seconds
        val plane = ByteArray(width * height * 3 / 2)
        repeat(frames) { frame ->
            var index = encoder.dequeueInputBuffer(1_000_000)
            while (index < 0) {
                drain()
                index = encoder.dequeueInputBuffer(1_000_000)
            }
            plane.fill((40 + frame % 180).toByte(), 0, width * height)
            plane.fill(128.toByte(), width * height, plane.size)
            val buffer = encoder.getInputBuffer(index)!!
            buffer.clear()
            buffer.put(plane)
            encoder.queueInputBuffer(index, 0, plane.size, frame * 1_000_000L / fps, 0)
            drain()
        }
        var tail = encoder.dequeueInputBuffer(1_000_000)
        while (tail < 0) {
            drain()
            tail = encoder.dequeueInputBuffer(1_000_000)
        }
        encoder.queueInputBuffer(tail, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain()
        encoder.stop(); encoder.release()
        muxer.stop(); muxer.release()
        return file
    }

    /** Seals [file] with the length read off it by the same API the importer uses. */
    suspend fun seal(file: File, name: String, takenAt: Long): Long {
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            retriever.release()
        }
        entry.session().openVault!!.files.put(
            file.inputStream(),
            IncomingFile(name, "video/mp4", takenAt, durationMs = duration),
        )
        file.delete()
        return duration
    }

    /**
     * Locks, destroys the vault and puts every piece of shared state back: the lockout, the
     * gallery's prefs file, and the gallery settings held in memory for the whole process --
     * clearing the file alone is not enough, since a leaked limit stays in force in the next
     * test.
     */
    fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        // Closing the index leaves work queued on the main thread, which has to run before the
        // files it points at go.
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.gallerySettings().update { GallerySettings() }
            // update() writes the defaults straight back, so the file has to go after it.
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    /** The vault the journeys run against: one photo, a two-second clip and a ten-second one. */
    suspend fun create(): Pair<Long, Long> {
        val short = encodeMp4(2, "fixture-short.mp4")
        val long = encodeMp4(10, "fixture-long.mp4")
        entry.session().create(PASSPHRASE.toCharArray())
        entry.session().openVault!!.files
            .put(jpeg().inputStream(), IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000))
        val quick = seal(short, "quick.mp4", 1_700_000_100_000)
        val feature = seal(long, "feature.mp4", 1_700_000_200_000)
        return quick to feature
    }
}
