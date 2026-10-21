package com.shahaabapps.obscura.crypto

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps small secrets (the master key, per-file keys) with AES-256-GCM.
 * Output layout: 12-byte nonce || ciphertext || 16-byte tag.
 * The associated data binds a wrapped key to its purpose so blobs cannot be swapped.
 */
object KeyWrap {
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun wrap(kek: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        require(kek.size == 32) { "KEK must be 256 bits" }
        val nonce = randomBytes(NONCE_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    /** @throws WrongSecretException if the KEK is wrong or the blob was modified. */
    fun unwrap(kek: ByteArray, wrapped: ByteArray, associatedData: ByteArray): ByteArray {
        require(kek.size == 32) { "KEK must be 256 bits" }
        if (wrapped.size < NONCE_SIZE + TAG_BITS / 8) throw CorruptVaultException("Wrapped key too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(kek, "AES"),
            GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_SIZE),
        )
        cipher.updateAAD(associatedData)
        return try {
            cipher.doFinal(wrapped, NONCE_SIZE, wrapped.size - NONCE_SIZE)
        } catch (e: AEADBadTagException) {
            throw WrongSecretException()
        } catch (e: GeneralSecurityException) {
            throw CorruptVaultException("Could not unwrap key", e)
        }
    }
}
