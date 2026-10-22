package com.shahaabapps.obscura.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.shahaabapps.obscura.crypto.FileCipher
import com.shahaabapps.obscura.vault.FileStore
import com.shahaabapps.obscura.vault.VaultFile
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * Feeds Media3 (ExoPlayer) straight from a sealed file. Media URIs look like
 * `obscura://file/<id>`; reads decrypt only the 1 MiB segments the player asks for,
 * so seeking is instant and nothing is ever written to disk unencrypted.
 */
@UnstableApi
class SealedDataSource(
    private val cipher: FileCipher,
    private val files: FileStore,
    private val lookup: (String) -> VaultFile?,
) : BaseDataSource(/* isNetwork = */ false) {

    private var raw: FileChannel? = null
    private var plain: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.lastPathSegment ?: throw FileNotFoundException("No file id in ${dataSpec.uri}")
        val file = lookup(id) ?: throw FileNotFoundException("Sealed file $id not found")
        val channel = FileChannel.open(files.blobFile(file.id).toPath(), StandardOpenOption.READ)
        raw = channel
        val decrypted = cipher.seekableChannel(file.wrappedKey, file.id, channel)
        plain = decrypted
        if (dataSpec.position > file.sizeBytes) throw IOException("Seek past end of file")
        decrypted.position(dataSpec.position)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else file.sizeBytes - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), bytesRemaining).toInt()
        val n = plain!!.read(ByteBuffer.wrap(buffer, offset, wanted))
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            plain?.close()
            raw?.close()
        } finally {
            plain = null
            raw = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val cipher: FileCipher,
        private val files: FileStore,
        private val lookup: (String) -> VaultFile?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SealedDataSource(cipher, files, lookup)
    }

    companion object {
        fun uriFor(file: VaultFile): Uri = Uri.parse("obscura://file/${file.id}")
    }
}
