package com.shahaabapps.obscura.vault

import com.shahaabapps.obscura.crypto.FileCipher
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** What the caller knows about a file before it is sealed. */
data class IncomingFile(
    val name: String,
    val mimeType: String,
    val takenAt: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

/**
 * Seals file contents into `vault/blobs` and records them in the index.
 * Blobs are written to a temp file first and renamed only after the index row exists,
 * so a crash mid-import never leaves a half-written file that looks complete.
 */
class FileStore(
    private val store: VaultStore,
    private val cipher: FileCipher,
    private val dao: FileDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun put(source: InputStream, info: IncomingFile): VaultFile =
        checkNotNull(put(source, info, skipIfDuplicate = false))

    /**
     * Seals [source] and returns the new index row, or null when [skipIfDuplicate] is set and a
     * file with identical contents is already in the vault. The fingerprint is taken while
     * encrypting, so each file is read only once.
     */
    suspend fun put(source: InputStream, info: IncomingFile, skipIfDuplicate: Boolean): VaultFile? {
        val id = newId()
        val wrappedKey = cipher.newWrappedFileKey(id)
        store.tempDir.mkdirs()
        val temp = File(store.tempDir, "$id.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            temp.outputStream().buffered().use { raw ->
                cipher.encryptingStream(wrappedKey, id, raw).use { sealed ->
                    DigestInputStream(source, digest).use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            sealed.write(buffer, 0, n)
                            size += n
                        }
                    }
                }
            }
            val hash = digest.digest().toHex()
            if (skipIfDuplicate && dao.countWithHash(hash) > 0) return null
            val file = VaultFile(
                id = id,
                name = info.name,
                mimeType = info.mimeType,
                kind = kindOf(info.mimeType),
                sizeBytes = size,
                takenAt = info.takenAt,
                sealedAt = clock(),
                wrappedKey = wrappedKey,
                sha256 = hash,
                width = info.width,
                height = info.height,
                durationMs = info.durationMs,
            )
            dao.insert(file)
            val target = blobFile(id)
            target.parentFile?.mkdirs()
            check(temp.renameTo(target)) { "Could not move sealed blob into place" }
            return file
        } finally {
            temp.delete()
        }
    }

    /** Decrypting stream over a sealed file. Callers must close it. */
    fun open(file: VaultFile): InputStream =
        cipher.decryptingStream(file.wrappedKey, file.id, blobFile(file.id).inputStream().buffered())

    suspend fun delete(file: VaultFile) {
        dao.delete(file.id)
        blobFile(file.id).delete()
    }

    fun blobFile(id: String): File = File(File(store.blobDir, id.take(2)), id)

    companion object {
        private const val BUFFER = 64 * 1024
        private val random = SecureRandom()

        /** 128-bit random id, hex. Reveals nothing about the file. */
        fun newId(): String = ByteArray(16).also(random::nextBytes).toHex()

        fun kindOf(rawMimeType: String): FileKind = normalise(rawMimeType).let { mimeType ->
            kindOfNormalised(mimeType)
        }

        /** "image/GIF; charset=x" and "image/gif" are the same type. */
        fun normalise(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

        private fun kindOfNormalised(mimeType: String): FileKind = when {
            mimeType == "image/gif" -> FileKind.GIF
            mimeType.startsWith("image/") -> FileKind.PHOTO
            mimeType.startsWith("video/") -> FileKind.VIDEO
            mimeType.startsWith("audio/") -> FileKind.AUDIO
            mimeType == "application/pdf" || mimeType.startsWith("text/") ||
                mimeType.contains("document") || mimeType.contains("sheet") ||
                mimeType.contains("presentation") || mimeType == "application/msword" -> FileKind.DOCUMENT
            else -> FileKind.OTHER
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
