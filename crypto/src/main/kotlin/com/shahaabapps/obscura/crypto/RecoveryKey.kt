package com.shahaabapps.obscura.crypto

import java.security.MessageDigest

/**
 * A 128-bit recovery secret plus a 32-bit checksum, shown to the user as 8 groups of
 * 4 Crockford base32 characters, for example `7K2M-QX9D-...`. The checksum catches typos
 * before an expensive unlock attempt. Crockford's alphabet has no I, L, O or U, and decoding
 * accepts the common look-alikes (O→0, I/L→1) in either case.
 */
class RecoveryKey private constructor(private val entropy: ByteArray) {

    /** Raw 16-byte secret. The caller must wipe the returned copy. */
    fun secretBytes(): ByteArray = entropy.copyOf()

    fun format(): String {
        val payload = entropy + checksum(entropy)
        return encodeBase32(payload).chunked(GROUP).joinToString("-")
    }

    fun wipe() = entropy.wipe()

    override fun toString() = "RecoveryKey(****)"

    companion object {
        const val ENTROPY_SIZE = 16
        private const val CHECKSUM_SIZE = 4
        private const val GROUP = 4
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun generate(): RecoveryKey = RecoveryKey(randomBytes(ENTROPY_SIZE))

        /** @throws IllegalArgumentException if the text is malformed or the checksum fails. */
        fun parse(text: String): RecoveryKey {
            val cleaned = text.uppercase().filterNot { it == '-' || it.isWhitespace() }
                .map { c -> when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }
                .joinToString("")
            val bytes = decodeBase32(cleaned)
            require(bytes.size == ENTROPY_SIZE + CHECKSUM_SIZE) { "Recovery key has the wrong length" }
            val entropy = bytes.copyOfRange(0, ENTROPY_SIZE)
            val check = bytes.copyOfRange(ENTROPY_SIZE, bytes.size)
            require(MessageDigest.isEqual(check, checksum(entropy))) { "Recovery key checksum does not match" }
            return RecoveryKey(entropy)
        }

        private fun checksum(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data).copyOfRange(0, CHECKSUM_SIZE)

        private fun encodeBase32(data: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in data) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                    bits -= 5
                }
            }
            if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
            return out.toString()
        }

        private fun decodeBase32(text: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in text) {
                val value = ALPHABET.indexOf(c)
                require(value >= 0) { "Invalid character '$c' in recovery key" }
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    out.write((buffer shr (bits - 8)) and 0xFF)
                    bits -= 8
                }
            }
            return out.toByteArray()
        }
    }
}
