package com.shahaabapps.obscura.vault

import android.media.MediaDataSource
import com.shahaabapps.obscura.crypto.FileCipher
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * Lets Android's media framework (thumbnails now, playback later) read a sealed file with random
 * access. Only the requested 1 MiB segments are decrypted, into memory, never to disk.
 */
class EncryptedMediaDataSource(
    cipher: FileCipher,
    private val file: VaultFile,
    blob: File,
) : MediaDataSource() {

    private val raw: FileChannel = FileChannel.open(blob.toPath(), StandardOpenOption.READ)
    private val plain: SeekableByteChannel = cipher.seekableChannel(file.wrappedKey, file.id, raw)

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= file.sizeBytes) return -1
        if (size == 0) return 0
        plain.position(position)
        val target = ByteBuffer.wrap(buffer, offset, size)
        while (target.hasRemaining()) {
            if (plain.read(target) <= 0) break
        }
        val read = size - target.remaining()
        return if (read == 0) -1 else read
    }

    override fun getSize(): Long = file.sizeBytes

    @Synchronized
    override fun close() {
        plain.close()
        raw.close()
    }
}
