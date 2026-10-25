package com.shahaabapps.obscura.media

import java.io.OutputStream

/**
 * Writes an animated GIF89a. Android can decode GIFs but cannot write them, and Obscura adds no
 * library it does not need, so the format is written here: a global colour table, one frame
 * after another, each compressed with the LZW scheme GIF uses.
 *
 * Pixels come in as plain ARGB ints, so this is ordinary Kotlin and can be tested off a device.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val palette: IntArray,
) {
    init {
        require(width in 1..65535 && height in 1..65535) { "A GIF is at most 65535 by 65535" }
        require(width.toLong() * height <= MAX_PIXELS) { "That picture is too large to hold a frame at a time" }
        require(palette.isNotEmpty() && palette.size <= 256) { "A GIF palette holds 1 to 256 colours" }
    }

    companion object {
        /** A frame larger than any screen would mean allocations no phone can meet. */
        const val MAX_PIXELS = 8L * 1000 * 1000
    }

    /** Colour table sizes are powers of two, from 2 up. */
    private val tableSize = generateSequence(2) { it * 2 }.first { it >= palette.size }
    private val bitsPerColour = Integer.numberOfTrailingZeros(tableSize)
    private var started = false

    /** Writes the header, screen description and colour table. */
    private fun begin() {
        if (started) return
        started = true
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        // Global colour table present, 8-bit colour resolution, table size in the low bits.
        out.write(0x80 or (0x7 shl 4) or (bitsPerColour - 1))
        out.write(0) // background colour index
        out.write(0) // square pixels
        for (i in 0 until tableSize) {
            val colour = palette.getOrElse(i) { 0 }
            out.write((colour shr 16) and 0xFF)
            out.write((colour shr 8) and 0xFF)
            out.write(colour and 0xFF)
        }
        // Netscape extension: loop for ever.
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); writeShort(0)
        out.write(0)
    }

    /**
     * Adds one frame. [indices] holds one palette index per pixel, left to right and top to
     * bottom; [delayCentis] is how long it shows, in hundredths of a second.
     */
    fun addFrame(indices: ByteArray, delayCentis: Int) {
        require(indices.size == width * height) { "Expected ${width * height} pixels, got ${indices.size}" }
        begin()
        // Graphic control: how long to wait, and leave the frame in place for the next one.
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04) // disposal "do not dispose", no transparency
        writeShort(delayCentis.coerceIn(0, 65535))
        out.write(0) // transparent index, unused
        out.write(0)
        // Image descriptor: the whole frame, no local table, not interlaced.
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0)
        compress(indices)
    }

    /** Ends the file. The stream itself is left open for the caller to close. */
    fun finish() {
        begin()
        out.write(0x3B)
        out.flush()
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /**
     * GIF's variable-width LZW. Codes grow from [minCodeSize] + 1 bits up to 12, and the
     * dictionary is cleared and restarted whenever it fills.
     */
    private fun compress(indices: ByteArray) {
        val minCodeSize = maxOf(2, bitsPerColour)
        out.write(minCodeSize)
        val blocks = BlockWriter(out)
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1

        // Key is (prefix code << 8) or next byte; both fit comfortably in an Int.
        val dictionary = HashMap<Int, Int>(4096)
        var codeSize = minCodeSize + 1
        var next = endCode + 1

        fun reset() {
            dictionary.clear()
            codeSize = minCodeSize + 1
            next = endCode + 1
        }

        blocks.writeCode(clearCode, codeSize)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val pixel = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or pixel
            val found = dictionary[key]
            if (found != null) {
                prefix = found
                continue
            }
            blocks.writeCode(prefix, codeSize)
            if (next < 4096) {
                dictionary[key] = next++
                // Once the next code would not fit, every later code is one bit wider. The
                // decoder counts the same entries and widens at the same moment.
                if (next - 1 == (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                blocks.writeCode(clearCode, codeSize)
                reset()
            }
            prefix = pixel
        }
        blocks.writeCode(prefix, codeSize)
        blocks.writeCode(endCode, codeSize)
        blocks.finish()
        out.write(0) // end of this frame's data blocks
    }

    /** Packs codes into bits, least significant first, and emits them in blocks of up to 255. */
    private class BlockWriter(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var count = 0
        private var bits = 0
        private var bitCount = 0

        fun writeCode(code: Int, size: Int) {
            bits = bits or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                put((bits and 0xFF).toByte())
                bits = bits ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) put((bits and 0xFF).toByte())
            flushBlock()
        }

        private fun put(byte: Byte) {
            block[count++] = byte
            if (count == 255) flushBlock()
        }

        private fun flushBlock() {
            if (count == 0) return
            out.write(count)
            out.write(block, 0, count)
            count = 0
        }
    }
}

/**
 * Picks a palette of at most 256 colours for a set of frames and maps pixels onto it.
 *
 * Colours are first rounded to 5 bits a channel, then the resulting cloud is split in half
 * repeatedly along whichever channel it spreads over most ("median cut"), which keeps the
 * colours a picture actually uses rather than a fixed grid.
 */
class Quantizer(private val maxColours: Int = 256) {

    private val counts = HashMap<Int, Int>()

    /** Feeds [pixels] (ARGB) into the colour survey. */
    fun sample(pixels: IntArray) {
        for (pixel in pixels) {
            val key = quantise(pixel)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }

    /** The palette, as RGB ints. Always holds at least one colour. */
    fun palette(): IntArray {
        if (counts.isEmpty()) return intArrayOf(0)
        var boxes = listOf(counts.keys.toIntArray())
        while (boxes.size < maxColours) {
            val target = boxes.filter { it.size > 1 }.maxByOrNull { spread(it).second } ?: break
            val (channel, range) = spread(target)
            if (range == 0) break
            val sorted = target.sortedBy { channelOf(it, channel) }.toIntArray()
            val middle = sorted.size / 2
            boxes = boxes - listOf(target) + listOf(sorted.copyOfRange(0, middle), sorted.copyOfRange(middle, sorted.size))
        }
        return boxes.filter { it.isNotEmpty() }.map { average(it) }.toIntArray().takeIf { it.isNotEmpty() } ?: intArrayOf(0)
    }

    companion object {
        /** Rounds a colour to 5 bits a channel, which is as fine as this needs to look. */
        fun quantise(argb: Int): Int =
            (((argb shr 19) and 0x1F) shl 10) or (((argb shr 11) and 0x1F) shl 5) or ((argb shr 3) and 0x1F)

        private fun channelOf(key: Int, channel: Int) = when (channel) {
            0 -> (key shr 10) and 0x1F
            1 -> (key shr 5) and 0x1F
            else -> key and 0x1F
        }

        /** Which channel this cloud of colours spreads over most, and by how much. */
        private fun spread(box: IntArray): Pair<Int, Int> {
            var best = 0
            var bestRange = -1
            for (channel in 0..2) {
                var low = 32
                var high = -1
                for (key in box) {
                    val value = channelOf(key, channel)
                    if (value < low) low = value
                    if (value > high) high = value
                }
                val range = high - low
                if (range > bestRange) {
                    bestRange = range
                    best = channel
                }
            }
            return best to bestRange
        }

        private fun average(box: IntArray): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            for (key in box) {
                r += channelOf(key, 0)
                g += channelOf(key, 1)
                b += channelOf(key, 2)
            }
            val n = box.size
            // Back from 5 bits to 8, keeping white white.
            fun up(v: Long) = ((v / n) * 255 / 31).toInt().coerceIn(0, 255)
            return (up(r) shl 16) or (up(g) shl 8) or up(b)
        }
    }

    /** Maps pixels onto [palette], remembering each rounded colour's answer so it is found once. */
    class Mapper(private val palette: IntArray) {
        private val cache = IntArray(1 shl 15) { -1 }

        fun map(pixels: IntArray, into: ByteArray) {
            for (i in pixels.indices) {
                val key = quantise(pixels[i])
                var index = cache[key]
                if (index < 0) {
                    index = nearest(key)
                    cache[key] = index
                }
                into[i] = index.toByte()
            }
        }

        private fun nearest(key: Int): Int {
            val r = ((key shr 10) and 0x1F) * 255 / 31
            val g = ((key shr 5) and 0x1F) * 255 / 31
            val b = (key and 0x1F) * 255 / 31
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in palette.indices) {
                val colour = palette[i]
                val dr = r - ((colour shr 16) and 0xFF)
                val dg = g - ((colour shr 8) and 0xFF)
                val db = b - (colour and 0xFF)
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                    if (distance == 0) break
                }
            }
            return best
        }
    }
}
