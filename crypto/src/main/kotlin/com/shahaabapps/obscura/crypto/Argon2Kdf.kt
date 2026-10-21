package com.shahaabapps.obscura.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id cost settings. They are stored in the vault header so they can be raised
 * later without breaking existing vaults.
 */
data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKiB >= 8 * parallelism) { "memoryKiB too small" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(parallelism in 1..16) { "parallelism out of range" }
    }

    companion object {
        /** 64 MiB, 3 passes: roughly 0.5–1 s on a mid-range phone. */
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 2)
    }
}

object Argon2Kdf {
    const val SALT_SIZE = 16
    const val KEY_SIZE = 32

    /** Derives a 256-bit key from [secret]. The caller owns and must wipe the result. */
    fun derive(secret: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.size == SALT_SIZE) { "salt must be $SALT_SIZE bytes" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        return ByteArray(KEY_SIZE).also { generator.generateBytes(secret, it) }
    }

    fun derive(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val bytes = passphrase.toUtf8Bytes()
        try {
            return derive(bytes, salt, params)
        } finally {
            bytes.wipe()
        }
    }
}
