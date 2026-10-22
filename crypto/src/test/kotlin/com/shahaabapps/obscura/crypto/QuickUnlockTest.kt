package com.shahaabapps.obscura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Stands in for the Android Keystore: AES-GCM with a key that only this object holds. */
private class FakeBinding : DeviceBinding {
    private val key = randomBytes(32)
    override fun seal(plaintext: ByteArray) = KeyWrap.wrap(key, plaintext, "device".toByteArray())
    override fun open(sealed: ByteArray): ByteArray = try {
        KeyWrap.unwrap(key, sealed, "device".toByteArray())
    } catch (e: WrongSecretException) {
        throw CorruptVaultException("bound to another device")
    }
}

class QuickUnlockTest {

    private val created = Vault.create("passphrase".toCharArray(), FAST_KDF)

    @Test
    fun correctPinUnlocks() {
        val binding = FakeBinding()
        val lock = QuickUnlock.createPin(created.keys, "482913".toCharArray(), binding, FAST_KDF)
        QuickUnlock.unlockWithPin(lock, created.header.vaultId, "482913".toCharArray(), binding).use {
            assertArrayEquals(created.keys.master(), it.master())
        }
    }

    @Test(expected = WrongSecretException::class)
    fun wrongPinIsRejected() {
        val binding = FakeBinding()
        val lock = QuickUnlock.createPin(created.keys, "482913".toCharArray(), binding, FAST_KDF)
        QuickUnlock.unlockWithPin(lock, created.header.vaultId, "482914".toCharArray(), binding)
    }

    @Test(expected = CorruptVaultException::class)
    fun pinDataCopiedToAnotherDeviceIsUseless() {
        val lock = QuickUnlock.createPin(created.keys, "482913".toCharArray(), FakeBinding(), FAST_KDF)
        QuickUnlock.unlockWithPin(lock, created.header.vaultId, "482913".toCharArray(), FakeBinding())
    }

    @Test
    fun pinLockSurvivesSerialization() {
        val binding = FakeBinding()
        val lock = PinLock.fromBytes(QuickUnlock.createPin(created.keys, "000000".toCharArray(), binding, FAST_KDF).toBytes())
        QuickUnlock.unlockWithPin(lock, created.header.vaultId, "000000".toCharArray(), binding).close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun pinMustBeSixDigits() {
        QuickUnlock.createPin(created.keys, "12a456".toCharArray(), FakeBinding(), FAST_KDF)
    }

    @Test(expected = CorruptVaultException::class)
    fun truncatedPinDataIsRejected() {
        val bytes = QuickUnlock.createPin(created.keys, "123456".toCharArray(), FakeBinding(), FAST_KDF).toBytes()
        PinLock.fromBytes(bytes.copyOf(bytes.size - 4))
    }

    @Test
    fun derivedKeysDifferByPurposeAndAreStable() {
        val a = created.keys.deriveKey("index-db")
        val b = created.keys.deriveKey("thumbnails")
        assertFalse(a.contentEquals(b))
        Vault.unlock(created.header, "passphrase".toCharArray()).use { again ->
            assertArrayEquals(a, again.deriveKey("index-db"))
        }
    }
}
