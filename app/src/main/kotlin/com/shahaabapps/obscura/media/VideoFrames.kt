package com.shahaabapps.obscura.media

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.Closeable
import kotlinx.coroutines.CancellationException

/**
 * Reads frames out of a video by playing it through once, rather than seeking to each one.
 *
 * Asking a video for an arbitrary moment means decoding from the keyframe before it, so pulling
 * thirty frames one at a time can decode hundreds. Running the decoder straight through decodes
 * each frame once, which is most of the difference between a GIF that takes a moment and one
 * that takes half a minute.
 *
 * Frames arrive already scaled to the size asked for and turned the right way up, as rows of
 * ARGB pixels.
 */
internal class VideoFrames(private val source: MediaDataSource) : Closeable {

    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var format: MediaFormat? = null

    /** Degrees the picture must be turned to be seen upright. */
    var rotation: Int = 0
        private set

    /** The picture's size as stored, before any turn. */
    var storedWidth: Int = 0
        private set
    var storedHeight: Int = 0
        private set

    init {
        extractor.setDataSource(source)
        var track = -1
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                track = index
                format = candidate
                break
            }
        }
        check(track >= 0) { "This file has no video in it" }
        val chosen = format!!
        extractor.selectTrack(track)
        storedWidth = chosen.getInteger(MediaFormat.KEY_WIDTH)
        storedHeight = chosen.getInteger(MediaFormat.KEY_HEIGHT)
        rotation = if (chosen.containsKey(MediaFormat.KEY_ROTATION)) chosen.getInteger(MediaFormat.KEY_ROTATION) else 0
    }

    /**
     * Decodes from [startUs], handing each wanted moment in [targetsUs] to [onFrame] as ARGB
     * pixels [width] by [height]. Targets must be in order. Stops early if [onFrame] returns
     * false. Returns how many frames were handed over.
     */
    fun read(
        startUs: Long,
        targetsUs: LongArray,
        width: Int,
        height: Int,
        keepGoing: () -> Boolean = { true },
        onFrame: (index: Int, pixels: IntArray) -> Boolean,
    ): Int {
        if (targetsUs.isEmpty()) return 0
        val chosen = format!!
        val mime = chosen.getString(MediaFormat.KEY_MIME)!!
        // Flexible YUV is the one layout every decoder can hand back as an Image.
        chosen.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(mime)
        decoder = codec
        codec.configure(chosen, null, null, 0)
        codec.start()

        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val pixels = IntArray(width * height)
        // The decoder's own output format states which colour standard and range the frames are
        // really in; the track format often leaves it out, and guessing tints the picture.
        var converter = YuvToRgb(chosen, storedWidth, storedHeight)
        val info = MediaCodec.BufferInfo()
        var next = 0
        var fedEverything = false
        var handed = 0
        // A decoder that stops producing without ever saying so would otherwise spin here for
        // good, and this loop holds the vault open while it runs.
        var lastProgress = SystemClock.elapsedRealtime()

        while (next < targetsUs.size) {
            // Asked every time round, not only when a frame arrives: a long stretch between
            // keyframes can decode for a while, and a vault that has locked must stop now.
            if (!keepGoing()) throw CancellationException("Stopped while reading the video")
            if (SystemClock.elapsedRealtime() - lastProgress > STALL_MS) {
                throw IllegalStateException("This video stopped partway through being read")
            }
            if (!fedEverything) {
                val input = codec.dequeueInputBuffer(TIMEOUT_US)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fedEverything = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
            }

            val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                output >= 0 -> {
                    lastProgress = SystemClock.elapsedRealtime()
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var wanted = false
                    if (info.size > 0) {
                        // One decoded frame can answer several wanted moments at once, which is
                        // what happens when a GIF asks for more frames a second than the video has.
                        while (next < targetsUs.size && info.presentationTimeUs >= targetsUs[next]) {
                            wanted = true
                            next++
                        }
                        // The last frame of the video answers whatever is still outstanding.
                        if (endOfStream && next < targetsUs.size) {
                            wanted = true
                            next = targetsUs.size
                        }
                        if (wanted) {
                            val image = codec.getOutputImage(output)
                            if (image != null) {
                                converter.into(image, pixels, width, height, rotation)
                                image.close()
                                if (!onFrame(handed, pixels)) {
                                    codec.releaseOutputBuffer(output, false)
                                    return handed + 1
                                }
                                handed++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (endOfStream) break
                }
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    converter = YuvToRgb(codec.outputFormat, storedWidth, storedHeight)
                }
                output == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Nothing to do; the buffers are fetched by index each time.
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
        }
        return handed
    }

    override fun close() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor.release() }
        runCatching { source.close() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        /** How long the decoder may say nothing at all before the video is given up on. */
        const val STALL_MS = 8_000L
        const val COLOR_FormatYUV420Flexible = 0x7F420888
    }
}
