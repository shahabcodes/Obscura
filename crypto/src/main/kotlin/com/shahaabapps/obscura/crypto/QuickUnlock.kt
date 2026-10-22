package com.shahaabapps.obscura.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import javax.crypto.Cipher

/**
 * Seals and opens bytes with a key that never leaves this device's secure hardware.
 * The PIN layer wraps its blob with this, so a copied PIN file cannot be brute-forced
 * on another machine; guesses must run on the phone, behind the app's lockout policy.
 */
interface DeviceBinding {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** Stored PIN unlock data: Argon2 settings, salt and the device-bound wrapped master key. */
class PinLock(
    val kdfParams: KdfParams,
    val salt: ByteArray,
    val sealed: ByteArray,
) {
    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeInt(kdfParams.memoryKiB)
            out.writeInt(kdfParams.iterations)
            out.writeInt(kdfParams.parallelism)
            out.write(salt)
            out.writeShort(sealed.size)
            out.write(sealed)
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val VERSION = 1

        fun fromBytes(data: ByteArray): PinLock = try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readUnsignedByte() != VERSION) throw CorruptVaultException("Unsupported PIN data")
                val params = KdfParams(input.readInt(), input.readInt(), input.readInt())
                val salt = ByteArray(Argon2Kdf.SALT_SIZE).also(input::readFully)
                val sealed = ByteArray(input.readUnsignedShort()).also(input::readFully)
                PinLock(params, salt, sealed)
            }
        } catch (e: EOFException) {
            throw CorruptVaultException("PIN data is truncated", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptVaultException("PIN data has invalid parameters", e)
        }
    }
}

/**
 * Quick-unlock paths that sit on top of the passphrase: a 6-digit PIN and fingerprint.
 * Both store an extra wrapped copy of the master key; removing them never affects the
 * passphrase or recovery key.
 */
object QuickUnlock {
    const val PIN_LENGTH = 6

    fun createPin(keys: VaultKeys, pin: CharArray, binding: DeviceBinding, params: KdfParams = KdfParams.DEFAULT): PinLock {
        require(pin.size == PIN_LENGTH && pin.all { it in '0'..'9' }) { "PIN must be $PIN_LENGTH digits" }
        val salt = randomBytes(Argon2Kdf.SALT_SIZE)
        val kek = Argon2Kdf.derive(pin, salt, params)
        val inner = try {
            KeyWrap.wrap(kek, keys.master(), Vault.aad(PIN_PURPOSE, keys.vaultId))
        } finally {
            kek.wipe()
        }
        return PinLock(params, salt, binding.seal(inner))
    }

    /** @throws WrongSecretException if the PIN is wrong. */
    fun unlockWithPin(lock: PinLock, vaultId: ByteArray, pin: CharArray, binding: DeviceBinding): VaultKeys {
        val inner = binding.open(lock.sealed)
        val kek = Argon2Kdf.derive(pin, lock.salt, lock.kdfParams)
        try {
            return VaultKeys(KeyWrap.unwrap(kek, inner, Vault.aad(PIN_PURPOSE, vaultId)), vaultId)
        } finally {
            kek.wipe()
        }
    }

    /** Seals the master key with a cipher that BiometricPrompt has just authorized. */
    fun sealForBiometric(keys: VaultKeys, authorizedCipher: Cipher): ByteArray =
        KeystoreGate.seal(authorizedCipher, keys.master())

    fun unlockWithBiometric(sealed: ByteArray, vaultId: ByteArray, authorizedCipher: Cipher): VaultKeys =
        VaultKeys(KeystoreGate.open(authorizedCipher, sealed), vaultId)

    private const val PIN_PURPOSE = "obscura/master/pin/v1"
}
