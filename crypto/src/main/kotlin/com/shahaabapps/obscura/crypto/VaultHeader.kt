package com.shahaabapps.obscura.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Everything needed to re-derive the master key, stored unencrypted next to the vault.
 * It holds only salts, cost settings and wrapped (encrypted) copies of the master key,
 * so reading it reveals nothing without the passphrase or recovery key.
 */
class VaultHeader(
    val vaultId: ByteArray,
    val kdfParams: KdfParams,
    val passphraseSalt: ByteArray,
    val masterByPassphrase: ByteArray,
    val recoverySalt: ByteArray,
    val masterByRecovery: ByteArray,
) {
    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(FORMAT_VERSION)
            out.write(vaultId)
            out.writeInt(kdfParams.memoryKiB)
            out.writeInt(kdfParams.iterations)
            out.writeInt(kdfParams.parallelism)
            out.write(passphraseSalt)
            out.writeBlob(masterByPassphrase)
            out.write(recoverySalt)
            out.writeBlob(masterByRecovery)
        }
        return bytes.toByteArray()
    }

    fun copy(
        kdfParams: KdfParams = this.kdfParams,
        passphraseSalt: ByteArray = this.passphraseSalt,
        masterByPassphrase: ByteArray = this.masterByPassphrase,
        recoverySalt: ByteArray = this.recoverySalt,
        masterByRecovery: ByteArray = this.masterByRecovery,
    ) = VaultHeader(vaultId, kdfParams, passphraseSalt, masterByPassphrase, recoverySalt, masterByRecovery)

    companion object {
        const val VAULT_ID_SIZE = 16
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte())
        private const val FORMAT_VERSION = 1
        private const val MAX_BLOB = 1024

        fun fromBytes(data: ByteArray): VaultHeader = try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) throw CorruptVaultException("Not an Obscura vault header")
                val version = input.readUnsignedByte()
                if (version != FORMAT_VERSION) throw CorruptVaultException("Unsupported header version $version")
                val vaultId = ByteArray(VAULT_ID_SIZE).also(input::readFully)
                val params = KdfParams(input.readInt(), input.readInt(), input.readInt())
                val passSalt = ByteArray(Argon2Kdf.SALT_SIZE).also(input::readFully)
                val byPass = input.readBlob()
                val recSalt = ByteArray(Argon2Kdf.SALT_SIZE).also(input::readFully)
                val byRecovery = input.readBlob()
                if (input.read() != -1) throw CorruptVaultException("Trailing bytes in vault header")
                VaultHeader(vaultId, params, passSalt, byPass, recSalt, byRecovery)
            }
        } catch (e: EOFException) {
            throw CorruptVaultException("Vault header is truncated", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptVaultException("Vault header has invalid parameters", e)
        }

        private fun DataOutputStream.writeBlob(blob: ByteArray) {
            writeShort(blob.size)
            write(blob)
        }

        private fun DataInputStream.readBlob(): ByteArray {
            val size = readUnsignedShort()
            if (size > MAX_BLOB) throw CorruptVaultException("Blob too large")
            return ByteArray(size).also(::readFully)
        }
    }
}
