package com.shahaabapps.obscura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import kotlin.random.Random

class FileCipherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vault = Vault.create("file tests".toCharArray(), FAST_KDF)
    private val cipher = vault.keys.fileCipher

    /** 3.5 MiB spans four 1 MiB segments, so segment boundaries get exercised. */
    private val plaintext = Random(42).nextBytes(3 * 1024 * 1024 + 512 * 1024)

    private fun encrypt(data: ByteArray, key: ByteArray, id: String): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.encryptingStream(key, id, out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decrypt(data: ByteArray, key: ByteArray, id: String): ByteArray =
        cipher.decryptingStream(key, id, ByteArrayInputStream(data)).use { it.readBytes() }

    @Test
    fun roundTripsLargeFile() {
        val key = cipher.newWrappedFileKey("photo-1")
        val sealed = encrypt(plaintext, key, "photo-1")
        assertFalse("Ciphertext must not contain the plaintext prefix", sealed.copyOfRange(0, 64).contentEquals(plaintext.copyOfRange(0, 64)))
        assertArrayEquals(plaintext, decrypt(sealed, key, "photo-1"))
    }

    @Test
    fun roundTripsEmptyFile() {
        val key = cipher.newWrappedFileKey("empty")
        assertArrayEquals(ByteArray(0), decrypt(encrypt(ByteArray(0), key, "empty"), key, "empty"))
    }

    @Test
    fun seekableChannelReadsFromAnyOffset() {
        val key = cipher.newWrappedFileKey("video-1")
        val file: File = temp.newFile("video-1.enc")
        file.writeBytes(encrypt(plaintext, key, "video-1"))

        val offset = 2_500_000L
        val length = 300_000
        java.nio.file.Files.newByteChannel(file.toPath(), StandardOpenOption.READ).use { raw ->
            cipher.seekableChannel(key, "video-1", raw).use { channel ->
                channel.position(offset)
                val buffer = ByteBuffer.allocate(length)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                assertArrayEquals(plaintext.copyOfRange(offset.toInt(), offset.toInt() + length), buffer.array())
            }
        }
    }

    @Test
    fun flippedByteIsDetected() {
        val key = cipher.newWrappedFileKey("doc-1")
        val sealed = encrypt(plaintext, key, "doc-1")
        sealed[1_200_000] = (sealed[1_200_000].toInt() xor 0x40).toByte()
        try {
            decrypt(sealed, key, "doc-1")
            fail("Tampering must be detected")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun truncatedFileIsDetected() {
        val key = cipher.newWrappedFileKey("doc-2")
        val sealed = encrypt(plaintext, key, "doc-2")
        try {
            decrypt(sealed.copyOf(sealed.size - 100), key, "doc-2")
            fail("Truncation must be detected")
        } catch (expected: IOException) {
        }
    }

    @Test(expected = WrongSecretException::class)
    fun fileKeyIsBoundToItsFileId() {
        val key = cipher.newWrappedFileKey("a")
        val sealed = encrypt(plaintext, key, "a")
        decrypt(sealed, key, "b")
    }

    @Test(expected = WrongSecretException::class)
    fun anotherVaultCannotOpenTheFile() {
        val key = cipher.newWrappedFileKey("shared-name")
        val sealed = encrypt(plaintext, key, "shared-name")
        val other = Vault.create("other".toCharArray(), FAST_KDF).keys.fileCipher
        other.decryptingStream(key, "shared-name", ByteArrayInputStream(sealed)).use { it.readBytes() }
    }

    @Test
    fun thumbnailCannotBeSwappedForContent() {
        val key = cipher.newWrappedFileKey("photo")
        val thumb = ByteArrayOutputStream().also { out ->
            cipher.encryptingStream(key, "photo", out, FileCipher.PART_THUMBNAIL).use { it.write(byteArrayOf(1, 2, 3)) }
        }.toByteArray()
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            cipher.decryptingStream(key, "photo", ByteArrayInputStream(thumb), FileCipher.PART_THUMBNAIL).use { it.readBytes() },
        )
        try {
            cipher.decryptingStream(key, "photo", ByteArrayInputStream(thumb)).use { it.readBytes() }
            fail("A thumbnail must not decrypt as the file's contents")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun filesSurviveLockAndUnlock() {
        val key = cipher.newWrappedFileKey("persist")
        val sealed = encrypt(plaintext, key, "persist")
        Vault.unlock(vault.header, "file tests".toCharArray()).use { reopened ->
            val back = reopened.fileCipher.decryptingStream(key, "persist", ByteArrayInputStream(sealed)).use { it.readBytes() }
            assertArrayEquals(plaintext, back)
        }
    }
}
