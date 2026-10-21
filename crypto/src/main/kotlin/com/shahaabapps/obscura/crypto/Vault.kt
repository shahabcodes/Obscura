package com.shahaabapps.obscura.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The unlocked master key. Close it (or let auto-lock close it) to wipe the key from memory;
 * any [FileCipher] obtained from it stops working afterwards.
 */
class VaultKeys internal constructor(
    private val masterKey: ByteArray,
    val vaultId: ByteArray,
) : AutoCloseable {
    @Volatile
    var isClosed = false
        private set

    val fileCipher: FileCipher by lazy { FileCipher(this) }

    internal fun master(): ByteArray {
        check(!isClosed) { "Vault is locked" }
        return masterKey
    }

    override fun close() {
        isClosed = true
        masterKey.wipe()
    }
}

/**
 * Creating and unlocking a vault.
 *
 * A random 256-bit master key encrypts every file key. The master key itself is stored twice
 * in the [VaultHeader]: once wrapped by an Argon2id key derived from the passphrase, once by a
 * key derived from the recovery key. Changing the passphrase only rewraps the master key,
 * so no file needs re-encrypting.
 */
object Vault {
    private const val MASTER_SIZE = 32

    class Created(val header: VaultHeader, val recoveryKey: RecoveryKey, val keys: VaultKeys)

    fun create(passphrase: CharArray, params: KdfParams = KdfParams.DEFAULT): Created {
        val vaultId = randomBytes(VaultHeader.VAULT_ID_SIZE)
        val master = randomBytes(MASTER_SIZE)
        val recoveryKey = RecoveryKey.generate()

        val passSalt = randomBytes(Argon2Kdf.SALT_SIZE)
        val passKek = Argon2Kdf.derive(passphrase, passSalt, params)
        val byPass = try {
            KeyWrap.wrap(passKek, master, aad(PASSPHRASE_PURPOSE, vaultId))
        } finally {
            passKek.wipe()
        }

        val recSalt = randomBytes(Argon2Kdf.SALT_SIZE)
        val recKek = recoveryKek(recoveryKey, recSalt)
        val byRecovery = try {
            KeyWrap.wrap(recKek, master, aad(RECOVERY_PURPOSE, vaultId))
        } finally {
            recKek.wipe()
        }

        val header = VaultHeader(vaultId, params, passSalt, byPass, recSalt, byRecovery)
        return Created(header, recoveryKey, VaultKeys(master, vaultId))
    }

    /** @throws WrongSecretException if the passphrase is wrong. */
    fun unlock(header: VaultHeader, passphrase: CharArray): VaultKeys {
        val kek = Argon2Kdf.derive(passphrase, header.passphraseSalt, header.kdfParams)
        try {
            val master = KeyWrap.unwrap(kek, header.masterByPassphrase, aad(PASSPHRASE_PURPOSE, header.vaultId))
            return VaultKeys(master, header.vaultId)
        } finally {
            kek.wipe()
        }
    }

    /** @throws WrongSecretException if the recovery key belongs to a different vault. */
    fun unlock(header: VaultHeader, recoveryKey: RecoveryKey): VaultKeys {
        val kek = recoveryKek(recoveryKey, header.recoverySalt)
        try {
            val master = KeyWrap.unwrap(kek, header.masterByRecovery, aad(RECOVERY_PURPOSE, header.vaultId))
            return VaultKeys(master, header.vaultId)
        } finally {
            kek.wipe()
        }
    }

    /** Returns a new header protected by [newPassphrase]. The recovery key keeps working. */
    fun changePassphrase(
        header: VaultHeader,
        keys: VaultKeys,
        newPassphrase: CharArray,
        params: KdfParams = header.kdfParams,
    ): VaultHeader {
        require(keys.vaultId.contentEquals(header.vaultId)) { "Keys belong to another vault" }
        val salt = randomBytes(Argon2Kdf.SALT_SIZE)
        val kek = Argon2Kdf.derive(newPassphrase, salt, params)
        try {
            val wrapped = KeyWrap.wrap(kek, keys.master(), aad(PASSPHRASE_PURPOSE, header.vaultId))
            return header.copy(kdfParams = params, passphraseSalt = salt, masterByPassphrase = wrapped)
        } finally {
            kek.wipe()
        }
    }

    private const val PASSPHRASE_PURPOSE = "obscura/master/passphrase/v1"
    private const val RECOVERY_PURPOSE = "obscura/master/recovery/v1"

    internal fun aad(purpose: String, id: ByteArray): ByteArray = purpose.toByteArray() + id

    /** The recovery key already carries 128 bits of entropy, so HKDF is enough here. */
    private fun recoveryKek(recoveryKey: RecoveryKey, salt: ByteArray): ByteArray {
        val secret = recoveryKey.secretBytes()
        try {
            return hkdfSha256(secret, salt, "obscura/recovery-kek/v1".toByteArray(), 32)
        } finally {
            secret.wipe()
        }
    }

    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        prk.wipe()
        previous.wipe()
        return out
    }
}
