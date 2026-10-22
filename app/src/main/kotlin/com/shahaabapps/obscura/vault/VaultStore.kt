package com.shahaabapps.obscura.vault

import android.content.Context
import android.util.AtomicFile
import com.shahaabapps.obscura.crypto.PinLock
import com.shahaabapps.obscura.crypto.VaultHeader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk layout of the vault, all inside app-private storage (`files/vault`), which other apps,
 * file managers and USB file transfer cannot see:
 *
 * ```
 * vault/vault.hdr   salts + wrapped master keys (no plaintext secrets)
 * vault/pin.bin     device-bound PIN unlock data (optional)
 * vault/bio.bin     fingerprint-sealed master key (optional)
 * vault/index.db    SQLCipher database of file names, dates and keys
 * vault/blobs/      encrypted file contents
 * vault/thumbs/     encrypted thumbnails
 * ```
 */
@Singleton
class VaultStore internal constructor(val root: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "vault"))

    private val headerFile get() = AtomicFile(File(root, "vault.hdr"))
    private val pinFile get() = AtomicFile(File(root, "pin.bin"))
    private val bioFile get() = AtomicFile(File(root, "bio.bin"))

    val databaseFile: File get() = File(root, "index.db")
    val blobDir: File get() = File(root, "blobs")
    val thumbDir: File get() = File(root, "thumbs")
    val tempDir: File get() = File(root, "tmp")

    fun exists(): Boolean = File(root, "vault.hdr").isFile

    fun readHeader(): VaultHeader = VaultHeader.fromBytes(headerFile.readFully())

    fun writeHeader(header: VaultHeader) = write(headerFile, header.toBytes())

    fun readPin(): PinLock? = pinFile.takeIf { it.baseFile.isFile }?.let { PinLock.fromBytes(it.readFully()) }

    fun writePin(lock: PinLock) = write(pinFile, lock.toBytes())

    fun deletePin() = pinFile.delete()

    fun readBiometric(): ByteArray? = bioFile.takeIf { it.baseFile.isFile }?.readFully()

    fun writeBiometric(sealed: ByteArray) = write(bioFile, sealed)

    fun deleteBiometric() = bioFile.delete()

    /** Removes the entire vault. Only reachable through an explicit, confirmed user action. */
    fun destroy() {
        root.deleteRecursively()
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        root.mkdirs()
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
