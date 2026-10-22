package com.shahaabapps.obscura.vault

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.shahaabapps.obscura.crypto.DeviceBinding
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreGate
import com.shahaabapps.obscura.crypto.QuickUnlock
import com.shahaabapps.obscura.crypto.RecoveryKey
import com.shahaabapps.obscura.crypto.Vault
import com.shahaabapps.obscura.crypto.VaultKeys
import com.shahaabapps.obscura.crypto.WrongSecretException
import com.shahaabapps.obscura.crypto.wipe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/** The unlocked vault: keys, encrypted index and file store. Closed together on lock. */
class OpenVault internal constructor(
    val keys: VaultKeys,
    val database: IndexDatabase,
    val files: FileStore,
    val thumbnails: ThumbnailStore,
    /** SQLCipher keeps using this array to open extra connections, so it lives until close. */
    private val databaseKey: ByteArray,
) {
    /** Removes a file for good: its index row (with its key), its blob and its thumbnail. */
    suspend fun shred(file: VaultFile) {
        files.delete(file)
        thumbnails.delete(file.id)
    }

    internal fun close() {
        thumbnails.clear()
        database.close()
        databaseKey.wipe()
        keys.close()
    }
}

sealed interface VaultState {
    data object NoVault : VaultState
    data object Locked : VaultState
    class Unlocked(val vault: OpenVault) : VaultState
}

sealed interface UnlockResult {
    data object Success : UnlockResult
    /** Wrong secret; [freeAttemptsLeft] more tries before delays start. */
    data class Wrong(val freeAttemptsLeft: Int) : UnlockResult
    data class LockedOut(val remainingMs: Long) : UnlockResult
    data class Failed(val message: String) : UnlockResult
}

/**
 * Owns the vault lifecycle: creation, every unlock path, quick-unlock enrolment and locking.
 * All secrets passed in as CharArray are wiped once used.
 */
@Singleton
class VaultSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: VaultStore,
    private val lockout: LockoutPolicy,
    private val pinBinding: DeviceBinding,
    private val biometricGate: KeystoreGate,
    private val kdfParams: KdfParams,
) {
    private val _state = MutableStateFlow<VaultState>(if (store.exists()) VaultState.Locked else VaultState.NoVault)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val openVault: OpenVault? get() = (_state.value as? VaultState.Unlocked)?.vault

    /** Creates a new vault, leaves it unlocked and returns the recovery key to show once. */
    suspend fun create(passphrase: CharArray): RecoveryKey = withContext(Dispatchers.Default) {
        check(!store.exists()) { "A vault already exists" }
        try {
            val created = Vault.create(passphrase, kdfParams)
            store.writeHeader(created.header)
            lockout.recordSuccess()
            open(created.keys)
            created.recoveryKey
        } finally {
            passphrase.wipe()
        }
    }

    suspend fun unlockWithPassphrase(passphrase: CharArray): UnlockResult = attempt(passphrase::wipe) {
        Vault.unlock(store.readHeader(), passphrase)
    }

    suspend fun unlockWithPin(pin: CharArray): UnlockResult {
        val lock = store.readPin() ?: return UnlockResult.Failed("PIN unlock is not set up")
        return attempt(pin::wipe) {
            QuickUnlock.unlockWithPin(lock, store.readHeader().vaultId, pin, pinBinding)
        }
    }

    suspend fun unlockWithRecoveryKey(text: String): UnlockResult {
        val key = try {
            RecoveryKey.parse(text)
        } catch (e: IllegalArgumentException) {
            // A typo caught by the checksum is not a guess, so it does not count as an attempt.
            return UnlockResult.Failed("That recovery key has a typo. Check each group of 4.")
        }
        return attempt(key::wipe) { Vault.unlock(store.readHeader(), key) }
    }

    /**
     * Replaces the passphrase. [current] must be right, and wrong guesses count against the same
     * lockout as unlocking, so an unlocked phone can't be used to try passphrases freely.
     * The master key is only re-wrapped: no file is re-encrypted, and the recovery key,
     * PIN and fingerprint keep working.
     */
    suspend fun changePassphrase(current: CharArray, replacement: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        try {
            val vault = openVault ?: return@withContext UnlockResult.Failed("Unlock the vault first")
            val remaining = lockout.remainingMs()
            if (remaining > 0) return@withContext UnlockResult.LockedOut(remaining)
            val header = store.readHeader()
            try {
                Vault.unlock(header, current).close()
            } catch (e: WrongSecretException) {
                lockout.recordFailure()
                val wait = lockout.remainingMs()
                return@withContext if (wait > 0) UnlockResult.LockedOut(wait) else UnlockResult.Wrong(lockout.freeAttemptsLeft())
            }
            lockout.recordSuccess()
            store.writeHeader(Vault.changePassphrase(header, vault.keys, replacement, kdfParams))
            UnlockResult.Success
        } catch (e: Exception) {
            UnlockResult.Failed("The passphrase could not be changed: ${e.message}")
        } finally {
            current.wipe()
            replacement.wipe()
        }
    }

    fun hasPin(): Boolean = store.readPin() != null

    suspend fun setPin(pin: CharArray) = withContext(Dispatchers.Default) {
        val vault = requireNotNull(openVault) { "Unlock the vault first" }
        try {
            store.writePin(QuickUnlock.createPin(vault.keys, pin, pinBinding, kdfParams))
        } finally {
            pin.wipe()
        }
    }

    fun removePin() {
        store.deletePin()
    }

    fun isBiometricEnrolled(): Boolean = store.readBiometric() != null && biometricGate.hasKey()

    /** Cipher for BiometricPrompt during enrolment. */
    fun biometricEnrollCipher(): Cipher {
        if (!biometricGate.hasKey()) biometricGate.createKey()
        return try {
            biometricGate.encryptCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            biometricGate.deleteKey()
            biometricGate.createKey()
            biometricGate.encryptCipher()
        }
    }

    fun completeBiometricEnroll(authorizedCipher: Cipher) {
        val vault = requireNotNull(openVault) { "Unlock the vault first" }
        store.writeBiometric(QuickUnlock.sealForBiometric(vault.keys, authorizedCipher))
    }

    /**
     * Cipher for BiometricPrompt at unlock, or null when fingerprint unlock is unavailable,
     * for example after a new fingerprint was enrolled (Android invalidates the key then).
     */
    fun biometricUnlockCipher(): Cipher? {
        val sealed = store.readBiometric() ?: return null
        return try {
            biometricGate.decryptCipher(KeystoreGate.ivOf(sealed))
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometric()
            null
        } catch (e: IllegalStateException) {
            disableBiometric()
            null
        }
    }

    suspend fun unlockWithBiometric(authorizedCipher: Cipher): UnlockResult = withContext(Dispatchers.Default) {
        val sealed = store.readBiometric() ?: return@withContext UnlockResult.Failed("Fingerprint unlock is not set up")
        try {
            val keys = QuickUnlock.unlockWithBiometric(sealed, store.readHeader().vaultId, authorizedCipher)
            lockout.recordSuccess()
            open(keys)
            UnlockResult.Success
        } catch (e: Exception) {
            UnlockResult.Failed("Fingerprint unlock failed. Use your PIN or passphrase.")
        }
    }

    fun disableBiometric() {
        store.deleteBiometric()
        biometricGate.deleteKey()
    }

    fun lockoutRemainingMs(): Long = lockout.remainingMs()

    /** Wipes keys from memory and closes the index. Safe to call at any time. */
    fun lock() {
        val vault = openVault ?: return
        _state.value = VaultState.Locked
        vault.close()
    }

    /**
     * Locks, then re-reads whether a vault exists on disk. Needed after the vault folder is
     * replaced or removed outside this session (restoring a backup, deleting the vault).
     */
    fun reload() {
        lock()
        _state.value = if (store.exists()) VaultState.Locked else VaultState.NoVault
    }

    private suspend fun attempt(wipeSecret: () -> Unit, unlock: () -> VaultKeys): UnlockResult =
        withContext(Dispatchers.Default) {
            try {
                val remaining = lockout.remainingMs()
                if (remaining > 0) return@withContext UnlockResult.LockedOut(remaining)
                val keys = try {
                    unlock()
                } catch (e: WrongSecretException) {
                    lockout.recordFailure()
                    val wait = lockout.remainingMs()
                    return@withContext if (wait > 0) UnlockResult.LockedOut(wait) else UnlockResult.Wrong(lockout.freeAttemptsLeft())
                }
                lockout.recordSuccess()
                open(keys)
                UnlockResult.Success
            } catch (e: Exception) {
                UnlockResult.Failed("The vault could not be opened: ${e.message}")
            } finally {
                wipeSecret()
            }
        }

    private fun open(keys: VaultKeys) {
        val dbKey = keys.deriveKey("index-db")
        val database = try {
            IndexDatabase.open(context, store.databaseFile, dbKey).also {
                // Open now so a bad key or corrupt file fails here, not on first query.
                it.openHelper.writableDatabase
            }
        } catch (e: Exception) {
            dbKey.wipe()
            keys.close()
            throw e
        }
        val files = FileStore(store, keys.fileCipher, database.files())
        _state.value = VaultState.Unlocked(OpenVault(keys, database, files, ThumbnailStore(store, keys.fileCipher, files), dbKey))
    }
}
