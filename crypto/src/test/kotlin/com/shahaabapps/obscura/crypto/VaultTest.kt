package com.shahaabapps.obscura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultTest {

    private fun pass(s: String) = s.toCharArray()

    @Test
    fun passphraseUnlocksTheSameMasterKey() {
        val created = Vault.create(pass("lantern orbit velvet canyon"), FAST_KDF)
        Vault.unlock(created.header, pass("lantern orbit velvet canyon")).use { keys ->
            assertArrayEquals(created.keys.master(), keys.master())
        }
    }

    @Test(expected = WrongSecretException::class)
    fun wrongPassphraseIsRejected() {
        val created = Vault.create(pass("correct horse"), FAST_KDF)
        Vault.unlock(created.header, pass("correct hors"))
    }

    @Test
    fun recoveryKeyUnlocksAfterRoundTripThroughText() {
        val created = Vault.create(pass("anything"), FAST_KDF)
        val typedBack = RecoveryKey.parse(created.recoveryKey.format())
        Vault.unlock(created.header, typedBack).use { keys ->
            assertArrayEquals(created.keys.master(), keys.master())
        }
    }

    @Test
    fun aNewRecoveryKeyReplacesTheOldOne() {
        val created = Vault.create(pass("lantern orbit"), FAST_KDF)
        val (header, fresh) = Vault.newRecoveryKey(created.header, created.keys)
        Vault.unlock(header, RecoveryKey.parse(fresh.format())).use { keys ->
            assertArrayEquals(created.keys.master(), keys.master())
        }
        // The passphrase is untouched, and the header survives a round trip to disk.
        Vault.unlock(VaultHeader.fromBytes(header.toBytes()), pass("lantern orbit")).use { keys ->
            assertArrayEquals(created.keys.master(), keys.master())
        }
        try {
            Vault.unlock(header, created.recoveryKey)
            fail("the old recovery key must stop working")
        } catch (expected: WrongSecretException) {
        }
    }

    @Test(expected = WrongSecretException::class)
    fun recoveryKeyFromAnotherVaultIsRejected() {
        val a = Vault.create(pass("a"), FAST_KDF)
        val b = Vault.create(pass("b"), FAST_KDF)
        Vault.unlock(a.header, b.recoveryKey)
    }

    @Test
    fun headerSurvivesSerialization() {
        val created = Vault.create(pass("serialize me"), FAST_KDF)
        val restored = VaultHeader.fromBytes(created.header.toBytes())
        assertEquals(FAST_KDF, restored.kdfParams)
        Vault.unlock(restored, pass("serialize me")).use { keys ->
            assertArrayEquals(created.keys.master(), keys.master())
        }
    }

    @Test
    fun tamperedHeaderIsDetected() {
        val created = Vault.create(pass("tamper"), FAST_KDF)
        val bytes = created.header.toBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x01).toByte()
        val tampered = VaultHeader.fromBytes(bytes)
        // Passphrase copy is untouched, the recovery copy was flipped.
        Vault.unlock(tampered, pass("tamper")).close()
        try {
            Vault.unlock(tampered, created.recoveryKey)
            fail("Tampered recovery blob should not unwrap")
        } catch (expected: WrongSecretException) {
        }
    }

    @Test(expected = CorruptVaultException::class)
    fun garbageHeaderIsRejected() {
        VaultHeader.fromBytes(byteArrayOf(1, 2, 3, 4, 5))
    }

    @Test(expected = CorruptVaultException::class)
    fun truncatedHeaderIsRejected() {
        val bytes = Vault.create(pass("x"), FAST_KDF).header.toBytes()
        VaultHeader.fromBytes(bytes.copyOf(bytes.size - 10))
    }

    @Test
    fun changingPassphraseKeepsMasterKeyAndRecovery() {
        val created = Vault.create(pass("old passphrase"), FAST_KDF)
        val newHeader = Vault.changePassphrase(created.header, created.keys, pass("new passphrase"))

        Vault.unlock(newHeader, pass("new passphrase")).use { keys ->
            assertArrayEquals(created.keys.master(), keys.master())
        }
        try {
            Vault.unlock(newHeader, pass("old passphrase"))
            fail("Old passphrase must stop working")
        } catch (expected: WrongSecretException) {
        }
        Vault.unlock(newHeader, created.recoveryKey).close()
    }

    @Test
    fun closingKeysWipesAndBlocksUse() {
        val created = Vault.create(pass("lock me"), FAST_KDF)
        val keys = created.keys
        val cipher = keys.fileCipher
        keys.close()
        assertTrue(keys.isClosed)
        try {
            cipher.newWrappedFileKey("f1")
            fail("A locked vault must not hand out keys")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun eachVaultHasItsOwnIdAndMaster() {
        val a = Vault.create(pass("same"), FAST_KDF)
        val b = Vault.create(pass("same"), FAST_KDF)
        assertFalse(a.header.vaultId.contentEquals(b.header.vaultId))
        assertFalse(a.keys.master().contentEquals(b.keys.master()))
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Vault.hkdfSha256(ikm, salt, info, 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }
}
