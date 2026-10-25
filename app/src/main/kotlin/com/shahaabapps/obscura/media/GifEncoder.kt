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
 * Colours are surveyed in buckets of 5 bits a channel, and the resulting cloud is split in half
 * repeatedly along whichever channel it spreads over most ("median cut"). Each split is weighted
 * by how many pixels a colour covers, not by how many different shades there are, so a wide
 * smooth sky earns more of the palette than a handful of stray pixels in a corner.
 */
class Quantizer(private val maxColours: Int = 256) {

    private val counts = IntArray(BUCKETS)
    private val sumR = LongArray(BUCKETS)
    private val sumG = LongArray(BUCKETS)
    private val sumB = LongArray(BUCKETS)

    /** Feeds [pixels] (ARGB) into the colour survey. */
    fun sample(pixels: IntArray) {
        for (pixel in pixels) {
            val key = quantise(pixel)
            counts[key]++
            sumR[key] += (pixel shr 16) and 0xFF
            sumG[key] += (pixel shr 8) and 0xFF
            sumB[key] += pixel and 0xFF
        }
    }

    /** One cloud of colours, with the figures needed to decide whether to split it kept to hand. */
    private inner class Box(val keys: IntArray) {
        var population = 0L
        var channel = 0
        var range = 0

        init {
            val low = intArrayOf(32, 32, 32)
            val high = intArrayOf(-1, -1, -1)
            for (key in keys) {
                population += counts[key]
                for (c in 0..2) {
                    val value = channelOf(key, c)
                    if (value < low[c]) low[c] = value
                    if (value > high[c]) high[c] = value
                }
            }
            for (c in 0..2) {
                val spread = high[c] - low[c]
                if (spread > range) {
                    range = spread
                    channel = c
                }
            }
        }

        /** Worth splitting only if it holds more than one shade and really does spread. */
        val splittable: Boolean get() = keys.size > 1 && range > 0

        /** Big and varied beats big and flat, so a gradient is split before a plain wall. */
        val claim: Double get() = population.toDouble() * range

        /** The colour this box stands for: the average of its pixels, not of its shades. */
        fun colour(): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            for (key in keys) {
                r += sumR[key]
                g += sumG[key]
                b += sumB[key]
                n += counts[key]
            }
            if (n == 0L) return 0
            fun average(total: Long) = (total / n).toInt().coerceIn(0, 255)
            return (average(r) shl 16) or (average(g) shl 8) or average(b)
        }
    }

    /** The palette, as RGB ints. Always holds at least one colour. */
    fun palette(): IntArray {
        val used = (0 until BUCKETS).filter { counts[it] > 0 }.toIntArray()
        if (used.isEmpty()) return intArrayOf(0)
        val boxes = ArrayList<Box>()
        boxes += Box(used)
        while (boxes.size < maxColours) {
            val target = boxes.filter { it.splittable }.maxByOrNull { it.claim } ?: break
            val sorted = target.keys.sortedBy { channelOf(it, target.channel) }.toIntArray()
            // Cut where half the pixels lie rather than half the shades, so neither side of the
            // split is left standing for almost nothing.
            val half = target.population / 2
            var running = 0L
            var cut = 0
            while (cut < sorted.size - 1 && running + counts[sorted[cut]] <= half) {
                running += counts[sorted[cut]]
                cut++
            }
            if (cut == 0) cut = 1
            boxes -= target
            boxes += Box(sorted.copyOfRange(0, cut))
            boxes += Box(sorted.copyOfRange(cut, sorted.size))
        }
        return boxes.map { it.colour() }.toIntArray()
    }

    companion object {
        /** 5 bits a channel: fine enough to survey a picture without a vast table. */
        private const val BUCKETS = 1 shl 15

        /** Rounds a colour to 5 bits a channel, which is as fine as this needs to look. */
        fun quantise(argb: Int): Int =
            (((argb shr 19) and 0x1F) shl 10) or (((argb shr 11) and 0x1F) shl 5) or ((argb shr 3) and 0x1F)

        private fun channelOf(key: Int, channel: Int) = when (channel) {
            0 -> (key shr 10) and 0x1F
            1 -> (key shr 5) and 0x1F
            else -> key and 0x1F
        }
    }

    /**
     * Maps pixels onto [palette], spreading each pixel's rounding error onto its neighbours
     * (Floyd-Steinberg). Without this a smooth sky becomes visible bands of flat colour; with it
     * the same few colours are mixed in a fine texture the eye reads as the shade in between.
     */
    class Mapper(private val palette: IntArray) {
        private val cache = IntArray(1 shl 15) { -1 }

        /** @param width the width of the picture in [pixels], so its rows can be told apart. */
        fun map(pixels: IntArray, width: Int, into: ByteArray) {
            if (width <= 0) return
            val height = pixels.size / width
            // What the pixels of this row and the next are still owed, three channels each.
            var thisRow = IntArray(width * 3)
            var nextRow = IntArray(width * 3)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val at = y * width + x
                    val pixel = pixels[at]
                    val r = (((pixel shr 16) and 0xFF) + thisRow[x * 3]).coerceIn(0, 255)
                    val g = (((pixel shr 8) and 0xFF) + thisRow[x * 3 + 1]).coerceIn(0, 255)
                    val b = ((pixel and 0xFF) + thisRow[x * 3 + 2]).coerceIn(0, 255)

                    val index = nearest((r shl 16) or (g shl 8) or b)
                    into[at] = index.toByte()

                    val chosen = palette[index]
                    val errorR = r - ((chosen shr 16) and 0xFF)
                    val errorG = g - ((chosen shr 8) and 0xFF)
                    val errorB = b - (chosen and 0xFF)

                    // 7 sixteenths to the right, then 3, 5 and 1 onto the row below.
                    fun give(row: IntArray, column: Int, part: Int) {
                        if (column < 0 || column >= width) return
                        row[column * 3] += errorR * part / 16
                        row[column * 3 + 1] += errorG * part / 16
                        row[column * 3 + 2] += errorB * part / 16
                    }
                    give(thisRow, x + 1, 7)
                    give(nextRow, x - 1, 3)
                    give(nextRow, x, 5)
                    give(nextRow, x + 1, 1)
                }
                val spent = thisRow
                thisRow = nextRow
                nextRow = spent
                java.util.Arrays.fill(nextRow, 0)
            }
        }

        private fun nearest(colour: Int): Int {
            val key = quantise(colour)
            val remembered = cache[key]
            if (remembered >= 0) return remembered
            val r = (colour shr 16) and 0xFF
            val g = (colour shr 8) and 0xFF
            val b = colour and 0xFF
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in palette.indices) {
                val candidate = palette[i]
                val dr = r - ((candidate shr 16) and 0xFF)
                val dg = g - ((candidate shr 8) and 0xFF)
                val db = b - (candidate and 0xFF)
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                    if (distance == 0) break
                }
            }
            cache[key] = best
            return best
        }
    }
}
