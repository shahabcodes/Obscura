package com.shahaabapps.obscura.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom

/** Thrown when a passphrase, PIN or recovery key does not open the vault. */
class WrongSecretException(message: String = "The secret does not unlock this vault") : Exception(message)

/** Thrown when stored vault data is malformed or has been tampered with. */
class CorruptVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a device-bound key in the Android Keystore is gone (the app was reinstalled with
 * its data kept, or the phone's key store was reset). The data it protected can't be opened here.
 */
class DeviceKeyLostException(message: String) : Exception(message)

internal val secureRandom: SecureRandom by lazy { SecureRandom() }

internal fun randomBytes(size: Int, random: SecureRandom = secureRandom): ByteArray =
    ByteArray(size).also(random::nextBytes)

/** Overwrites the array so the secret does not linger on the heap. */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')

/** UTF-8 encodes a passphrase without creating an immutable String copy. */
internal fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer: ByteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(this))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}
