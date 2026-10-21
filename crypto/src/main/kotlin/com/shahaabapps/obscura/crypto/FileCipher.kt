package com.shahaabapps.obscura.crypto

import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

/**
 * Encrypts file contents with Tink's AES-256-GCM-HKDF streaming AEAD in 1 MiB segments.
 *
 * Every file gets its own random key, wrapped by the master key and stored in the encrypted
 * index. Segmenting lets the video player decrypt from any offset without reading the whole
 * file, and each segment is authenticated, so tampering or truncation is detected.
 * The file id is bound in as associated data, so ciphertexts cannot be swapped between files.
 */
class FileCipher internal constructor(private val keys: VaultKeys) {

    /** Creates a fresh key for a new file and returns it wrapped by the master key. */
    fun newWrappedFileKey(fileId: String): ByteArray {
        val fileKey = randomBytes(FILE_KEY_SIZE)
        try {
            return KeyWrap.wrap(keys.master(), fileKey, fileKeyAad(fileId))
        } finally {
            fileKey.wipe()
        }
    }

    /** Wraps [output]; everything written is encrypted. Close the returned stream to finish the file. */
    fun encryptingStream(wrappedKey: ByteArray, fileId: String, output: OutputStream): OutputStream =
        streamingAead(wrappedKey, fileId).newEncryptingStream(output, contentAad(fileId))

    /** Reads plaintext from an encrypted [input]. Throws an IOException on tampering. */
    fun decryptingStream(wrappedKey: ByteArray, fileId: String, input: InputStream): InputStream =
        streamingAead(wrappedKey, fileId).newDecryptingStream(input, contentAad(fileId))

    /** Random-access plaintext view over an encrypted [channel], used for seeking in media. */
    fun seekableChannel(wrappedKey: ByteArray, fileId: String, channel: SeekableByteChannel): SeekableByteChannel =
        streamingAead(wrappedKey, fileId).newSeekableDecryptingChannel(channel, contentAad(fileId))

    private fun streamingAead(wrappedKey: ByteArray, fileId: String): StreamingAead {
        val fileKey = KeyWrap.unwrap(keys.master(), wrappedKey, fileKeyAad(fileId))
        return AesGcmHkdfStreaming(fileKey, "HmacSha256", FILE_KEY_SIZE, SEGMENT_SIZE, 0)
    }

    private fun fileKeyAad(fileId: String) = Vault.aad("obscura/file-key/v1:$fileId", keys.vaultId)

    private fun contentAad(fileId: String) = "obscura/content/v1:$fileId".toByteArray()

    companion object {
        const val FILE_KEY_SIZE = 32
        const val SEGMENT_SIZE = 1 shl 20
    }
}
