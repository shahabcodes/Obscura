package com.shahaabapps.obscura.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A hardware-backed AES key in the Android Keystore that only works after a strong biometric
 * check. It wraps the master key for fingerprint unlock; the passphrase path never depends on it.
 *
 * Usage: get a cipher from [encryptCipher] or [decryptCipher], pass it to BiometricPrompt as a
 * CryptoObject, and call [seal] / [open] with the authenticated cipher it returns.
 * Enrolling a new fingerprint invalidates the key, which forces a passphrase unlock.
 */
class KeystoreGate(private val alias: String = DEFAULT_ALIAS) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = keyStore.containsAlias(alias)

    /** Creates the key, preferring a StrongBox secure element when the phone has one. */
    fun createKey() {
        try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    fun deleteKey() {
        if (hasKey()) keyStore.deleteEntry(alias)
    }

    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, secretKey())
    }

    fun decryptCipher(iv: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
    }

    private fun secretKey(): SecretKey =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Biometric key missing or invalidated")

    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (strongBox) builder.setIsStrongBoxBacked(true)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "obscura.biometric.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** Encrypts [secret] with an authenticated cipher. Returns iv || ciphertext. */
        fun seal(authenticatedCipher: Cipher, secret: ByteArray): ByteArray {
            val iv = authenticatedCipher.iv
            check(iv.size in 1..255) { "Unexpected IV size" }
            return byteArrayOf(iv.size.toByte()) + iv + authenticatedCipher.doFinal(secret)
        }

        /** Reads the IV from a blob produced by [seal], for building the decrypt cipher. */
        fun ivOf(sealed: ByteArray): ByteArray {
            val size = sealed[0].toInt() and 0xFF
            return sealed.copyOfRange(1, 1 + size)
        }

        fun open(authenticatedCipher: Cipher, sealed: ByteArray): ByteArray {
            val size = sealed[0].toInt() and 0xFF
            return authenticatedCipher.doFinal(sealed, 1 + size, sealed.size - 1 - size)
        }
    }
}
