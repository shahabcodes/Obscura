package com.shahaabapps.obscura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * A 5 GiB file through the real file cipher: sealed, unsealed and verified byte for byte, then
 * read at offsets past 4 GiB, where 32-bit size or offset bugs would show. Writes about 5 GB to
 * build/, so it only runs when OBSCURA_LARGE_FILE_TEST=1.
 */
class LargeFileTest {

    private val size = 5L * 1024 * 1024 * 1024 + 12_345 // not a multiple of the segment size

    /** Deterministic content: any offset can be checked without keeping the original. */
    private fun byteAt(position: Long): Byte = ((position * 2654435761L) ushr 13).toByte()

    private inner class Pattern(private val length: Long) : InputStream() {
        private var position = 0L
        override fun read(): Int = if (position >= length) -1 else byteAt(position++).toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= length) return -1
            val n = minOf(len.toLong(), length - position).toInt()
            for (i in 0 until n) b[off + i] = byteAt(position + i)
            position += n
            return n
        }
    }

    private fun pump(input: InputStream, output: OutputStream, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest?.update(buffer, 0, n)
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }

    @Test
    fun fiveGigabytesRoundTripAndSeekPastFourGigabytes() {
        assumeTrue("set OBSCURA_LARGE_FILE_TEST=1 to run", System.getenv("OBSCURA_LARGE_FILE_TEST") == "1")
        val keys = Vault.create("large file".toCharArray(), FAST_KDF).keys
        val cipher = keys.fileCipher
        val id = "0123456789abcdef0123456789abcdef"
        val wrapped = cipher.newWrappedFileKey(id)
        val sealed = File("build/large-file-test.bin")
        try {
            val original = MessageDigest.getInstance("SHA-256")
            var start = System.nanoTime()
            val written = sealed.outputStream().buffered(1 shl 20).use { out ->
                cipher.encryptingStream(wrapped, id, out).use { enc -> pump(Pattern(size), enc, original) }
            }
            val sealSeconds = (System.nanoTime() - start) / 1e9
            assertEquals(size, written)
            println("sealed %,d bytes in %.1f s (%.0f MB/s), ciphertext %,d bytes".format(size, sealSeconds, size / 1e6 / sealSeconds, sealed.length()))

            start = System.nanoTime()
            val unsealed = MessageDigest.getInstance("SHA-256")
            val read = cipher.decryptingStream(wrapped, id, sealed.inputStream().buffered(1 shl 20)).use { dec ->
                pump(dec, OutputStream.nullOutputStream(), unsealed)
            }
            val openSeconds = (System.nanoTime() - start) / 1e9
            println("unsealed in %.1f s (%.0f MB/s)".format(openSeconds, size / 1e6 / openSeconds))
            assertEquals(size, read)
            assertArrayEquals(original.digest(), unsealed.digest())

            // Seeking, as video playback and share links do, beyond the 4 GiB mark.
            FileChannel.open(sealed.toPath(), StandardOpenOption.READ).use { raw ->
                cipher.seekableChannel(wrapped, id, raw).use { plain ->
                    assertEquals(size, plain.size())
                    for (at in listOf(0L, (4L shl 30) - 7, 4L shl 30, (4L shl 30) + 1_048_575, size - 4096)) {
                        val buffer = ByteBuffer.allocate(4096)
                        plain.position(at)
                        while (buffer.hasRemaining() && plain.read(buffer) > 0) Unit
                        val expected = ByteArray(4096) { byteAt(at + it) }
                        assertArrayEquals("bytes at $at", expected, buffer.array())
                    }
                }
            }
        } finally {
            sealed.delete()
            keys.close()
        }
    }
}
