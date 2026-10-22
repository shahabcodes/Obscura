package com.shahaabapps.obscura.vault

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.shahaabapps.obscura.crypto.DeviceBinding
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
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
import java.io.File
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** The unlocked vault: keys, encrypted index and file store. Closed together on lock. */
class OpenVault internal constructor(
    val keys: VaultKeys,
    val database: IndexDatabase,
    val files: FileStore,
    val thumbnails: ThumbnailStore,
    /** SQLCipher keeps using this array to open extra connections, so it lives until close. */
    private val databaseKey: ByteArray,
    internal val store: VaultStore,
    /** True when the decoy vault is open. The UI never shows this; it only hides decoy settings. */
    val isDecoy: Boolean,
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
    /**
     * Wrong secret; [freeAttemptsLeft] more tries before delays start, and, when wipe-after-10 is
     * on, [attemptsBeforeWipe] more before everything is destroyed.
     */
    data class Wrong(val freeAttemptsLeft: Int, val attemptsBeforeWipe: Int? = null) : UnlockResult
    data class LockedOut(val remainingMs: Long, val attemptsBeforeWipe: Int? = null) : UnlockResult
    data class Failed(val message: String) : UnlockResult
    /** Too many wrong attempts with wipe-after-10 on: the vault no longer exists. */
    data object Wiped : UnlockResult
}

/**
 * Owns the vault lifecycle: creation, every unlock path, quick-unlock enrolment, the decoy vault,
 * wipe-after-10 and locking. All secrets passed in as CharArray are wiped once used.
 *
 * A decoy vault is a complete second vault in its own folder with its own passphrase and PIN.
 * Unlock attempts try the real vault first and the decoy second; only when both reject a secret
 * does it count as a wrong attempt. Fingerprint and recovery key always mean the real vault.
 */
@Singleton
class VaultSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: VaultStore,
    private val lockout: LockoutPolicy,
    private val pinBinding: DeviceBinding,
    private val biometricGate: KeystoreGate,
    private val kdfParams: KdfParams,
    @Named("decoy") private val decoyStore: VaultStore = VaultStore(File(store.root.path + "-decoy")),
    @Named("decoy") private val decoyPinBinding: DeviceBinding = KeystoreBinding("obscura.decoy.pin.v1"),
    private val settings: VaultSettings = MemoryVaultSettings(),
) {
    private val _state = MutableStateFlow<VaultState>(if (store.exists()) VaultState.Locked else VaultState.NoVault)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val openVault: OpenVault? get() = (_state.value as? VaultState.Unlocked)?.vault

    /** The store of whichever vault is open, or the real one while locked. */
    private val currentStore: VaultStore get() = openVault?.store ?: store

    /** Creates a new vault, leaves it unlocked and returns the recovery key to show once. */
    suspend fun create(passphrase: CharArray): RecoveryKey = withContext(Dispatchers.Default) {
        check(!store.exists()) { "A vault already exists" }
        try {
            val created = Vault.create(passphrase, kdfParams)
            store.writeHeader(created.header)
            lockout.recordSuccess()
            open(created.keys, store, isDecoy = false)
            created.recoveryKey
        } finally {
            passphrase.wipe()
        }
    }

    suspend fun unlockWithPassphrase(passphrase: CharArray): UnlockResult = attempt(passphrase::wipe) {
        try {
            Vault.unlock(store.readHeader(), passphrase) to false
        } catch (e: WrongSecretException) {
            if (!decoyStore.exists()) throw e
            Vault.unlock(decoyStore.readHeader(), passphrase) to true
        }
    }

    suspend fun unlockWithPin(pin: CharArray): UnlockResult {
        val lock = store.readPin() ?: return UnlockResult.Failed("PIN unlock is not set up")
        return attempt(pin::wipe) {
            try {
                QuickUnlock.unlockWithPin(lock, store.readHeader().vaultId, pin, pinBinding) to false
            } catch (e: WrongSecretException) {
                val decoyLock = decoyStore.readPin() ?: throw e
                QuickUnlock.unlockWithPin(decoyLock, decoyStore.readHeader().vaultId, pin, decoyPinBinding) to true
            }
        }
    }

    suspend fun unlockWithRecoveryKey(text: String): UnlockResult {
        val key = try {
            RecoveryKey.parse(text)
        } catch (e: IllegalArgumentException) {
            // A typo caught by the checksum is not a guess, so it does not count as an attempt.
            return UnlockResult.Failed("That recovery key has a typo. Check each group of 4.")
        }
        return attempt(key::wipe) { Vault.unlock(store.readHeader(), key) to false }
    }

    /**
     * Replaces the passphrase of the open vault. [current] must be right, and wrong guesses count
     * against the same lockout as unlocking. The master key is only re-wrapped: no file is
     * re-encrypted, and the recovery key, PIN and fingerprint keep working.
     */
    suspend fun changePassphrase(current: CharArray, replacement: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        try {
            val vault = openVault ?: return@withContext UnlockResult.Failed("Unlock the vault first")
            val remaining = lockout.remainingMs()
            if (remaining > 0) return@withContext UnlockResult.LockedOut(remaining)
            val header = vault.store.readHeader()
            try {
                Vault.unlock(header, current).close()
            } catch (e: WrongSecretException) {
                return@withContext failure()
            }
            lockout.recordSuccess()
            vault.store.writeHeader(Vault.changePassphrase(header, vault.keys, replacement, kdfParams))
            UnlockResult.Success
        } catch (e: Exception) {
            UnlockResult.Failed("The passphrase could not be changed: ${e.message}")
        } finally {
            current.wipe()
            replacement.wipe()
        }
    }

    /** Whether the open vault (or, while locked, the real vault) has a PIN. */
    fun hasPin(): Boolean = currentStore.readPin() != null

    suspend fun setPin(pin: CharArray) = withContext(Dispatchers.Default) {
        val vault = requireNotNull(openVault) { "Unlock the vault first" }
        try {
            vault.store.writePin(QuickUnlock.createPin(vault.keys, pin, bindingFor(vault), kdfParams))
        } finally {
            pin.wipe()
        }
    }

    fun removePin() {
        currentStore.deletePin()
    }

    // ---- Decoy vault ----

    fun hasDecoy(): Boolean = decoyStore.exists()

    /**
     * Creates the decoy vault from inside the real one. Its passphrase (and PIN, if given) must
     * differ from the real vault's, otherwise the real vault would always win.
     */
    suspend fun createDecoy(passphrase: CharArray, pin: CharArray?): UnlockResult = withContext(Dispatchers.Default) {
        try {
            val vault = openVault ?: return@withContext UnlockResult.Failed("Unlock the vault first")
            if (vault.isDecoy) return@withContext UnlockResult.Failed("Not available here")
            if (decoyStore.exists()) return@withContext UnlockResult.Failed("A decoy vault already exists")
            if (opens { Vault.unlock(store.readHeader(), passphrase) }) {
                return@withContext UnlockResult.Failed("The decoy passphrase must be different from your real one.")
            }
            val realPin = store.readPin()
            if (pin != null && realPin != null &&
                opens { QuickUnlock.unlockWithPin(realPin, store.readHeader().vaultId, pin, pinBinding) }
            ) {
                return@withContext UnlockResult.Failed("The decoy PIN must be different from your real PIN.")
            }
            val created = Vault.create(passphrase, kdfParams)
            decoyStore.writeHeader(created.header)
            created.recoveryKey.wipe()
            created.keys.use { keys ->
                if (pin != null) decoyStore.writePin(QuickUnlock.createPin(keys, pin, decoyPinBinding, kdfParams))
            }
            UnlockResult.Success
        } catch (e: Exception) {
            decoyStore.destroy()
            UnlockResult.Failed("The decoy vault could not be created: ${e.message}")
        } finally {
            passphrase.wipe()
            pin?.wipe()
        }
    }

    /** Deletes the decoy vault and everything in it. Only possible from the real vault. */
    fun removeDecoy() {
        val vault = openVault ?: return
        if (vault.isDecoy) return
        decoyStore.destroy()
        (decoyPinBinding as? KeystoreBinding)?.delete()
    }

    private inline fun opens(block: () -> VaultKeys): Boolean = try {
        block().close()
        true
    } catch (e: WrongSecretException) {
        false
    }

    // ---- Fingerprint (real vault only) ----

    /** Fingerprint unlock always opens the real vault, so inside the decoy it reads as unavailable. */
    fun isBiometricEnrolled(): Boolean = openVault?.isDecoy != true && store.readBiometric() != null && biometricGate.hasKey()

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
        check(!vault.isDecoy) { "Not available" }
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
            open(keys, store, isDecoy = false)
            UnlockResult.Success
        } catch (e: Exception) {
            UnlockResult.Failed("Fingerprint unlock failed. Use your PIN or passphrase.")
        }
    }

    fun disableBiometric() {
        if (openVault?.isDecoy == true) return
        store.deleteBiometric()
        biometricGate.deleteKey()
    }

    // ---- Lockout, wipe, lock ----

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

    /**
     * Destroys both vaults and every key that could open them. The files on disk are useless
     * without the master keys, which existed only in the deleted headers and Keystore entries.
     */
    private fun wipeEverything() {
        lock()
        store.destroy()
        decoyStore.destroy()
        (pinBinding as? KeystoreBinding)?.delete()
        (decoyPinBinding as? KeystoreBinding)?.delete()
        runCatching { biometricGate.deleteKey() }
        lockout.recordSuccess()
        _state.value = VaultState.NoVault
    }

    /** Records a wrong attempt and returns what the caller should show. */
    private fun failure(): UnlockResult {
        lockout.recordFailure()
        if (settings.wipeAfterTen && lockout.failures >= VaultSettings.WIPE_THRESHOLD) {
            wipeEverything()
            return UnlockResult.Wiped
        }
        val wait = lockout.remainingMs()
        val beforeWipe = if (settings.wipeAfterTen) VaultSettings.WIPE_THRESHOLD - lockout.failures else null
        return if (wait > 0) UnlockResult.LockedOut(wait, beforeWipe) else UnlockResult.Wrong(lockout.freeAttemptsLeft(), beforeWipe)
    }

    /** Wrong attempts left before wipe-after-10 destroys the vault, or null when it is off. */
    fun attemptsBeforeWipe(): Int? = if (settings.wipeAfterTen) VaultSettings.WIPE_THRESHOLD - lockout.failures else null

    private suspend fun attempt(wipeSecret: () -> Unit, unlock: () -> Pair<VaultKeys, Boolean>): UnlockResult =
        withContext(Dispatchers.Default) {
            try {
                val remaining = lockout.remainingMs()
                if (remaining > 0) return@withContext UnlockResult.LockedOut(remaining)
                val (keys, decoy) = try {
                    unlock()
                } catch (e: WrongSecretException) {
                    return@withContext failure()
                }
                lockout.recordSuccess()
                open(keys, if (decoy) decoyStore else store, decoy)
                UnlockResult.Success
            } catch (e: Exception) {
                UnlockResult.Failed("The vault could not be opened: ${e.message}")
            } finally {
                wipeSecret()
            }
        }

    private fun bindingFor(vault: OpenVault) = if (vault.isDecoy) decoyPinBinding else pinBinding

    private fun open(keys: VaultKeys, target: VaultStore, isDecoy: Boolean) {
        val dbKey = keys.deriveKey("index-db")
        val database = try {
            IndexDatabase.open(context, target.databaseFile, dbKey).also {
                // Open now so a bad key or corrupt file fails here, not on first query.
                it.openHelper.writableDatabase
            }
        } catch (e: Exception) {
            dbKey.wipe()
            keys.close()
            throw e
        }
        val files = FileStore(target, keys.fileCipher, database.files())
        _state.value = VaultState.Unlocked(OpenVault(keys, database, files, ThumbnailStore(target, keys.fileCipher, files), dbKey, target, isDecoy))
    }
}
