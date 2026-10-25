package com.shahaabapps.obscura.ui.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.BuildConfig
import com.shahaabapps.obscura.backup.BackupManager
import com.shahaabapps.obscura.ui.components.canUseStrongBiometric
import com.shahaabapps.obscura.ui.setup.PassphraseStrength
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.PanicLock
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Cipher
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SecurityState(
    val fingerprintAvailable: Boolean = false,
    val fingerprintOn: Boolean = false,
    val pinOn: Boolean = false,
    val autoLockMs: Long = AutoLock.DEFAULT_DELAY_MS,
    val panicAvailable: Boolean = false,
    val panicLock: Boolean = true,
    val wipeAfterTen: Boolean = false,
    /** Inside the decoy vault, decoy and wipe settings are hidden so it looks like a normal vault. */
    val isDecoy: Boolean = false,
    val hasDecoy: Boolean = false,
    val lastBackupAt: Long? = null,
    val message: String? = null,
    val version: String = BuildConfig.VERSION_NAME,
) {
    /** A rough 0–100 picture of how well the vault is protected, for the health card. */
    val score: Int
        get() {
            var s = 65
            if (fingerprintOn || pinOn) s += 5
            if (panicLock && panicAvailable) s += 5
            s += when {
                autoLockMs == 0L -> 20
                autoLockMs <= 30_000 -> 15
                autoLockMs <= 60_000 -> 10
                else -> 0
            }
            return s.coerceAtMost(100)
        }
}

data class PassphraseChange(
    val current: String = "",
    val replacement: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val strength get() = PassphraseStrength.evaluate(replacement)
    val canSubmit get() = current.isNotEmpty() && strength.score >= PassphraseStrength.MIN_SCORE && replacement == confirm && !busy
}

data class DeleteVault(
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

data class DecoySetup(
    val passphrase: String = "",
    val confirm: String = "",
    val pin: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val strength get() = PassphraseStrength.evaluate(passphrase)
    val pinValid get() = pin.isEmpty() || (pin.length == 6 && pin.all(Char::isDigit))
    val canSubmit get() = strength.score >= 2 && passphrase == confirm && pinValid && !busy
}

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val autoLock: AutoLock,
    private val settings: VaultSettings,
    private val panic: PanicLock,
    private val backups: BackupManager,
) : ViewModel() {

    private val _decoy = MutableStateFlow(DecoySetup())
    val decoy: StateFlow<DecoySetup> = _decoy.asStateFlow()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    private val _change = MutableStateFlow(PassphraseChange())
    val change: StateFlow<PassphraseChange> = _change.asStateFlow()

    private fun load(): SecurityState {
        val decoyOpen = session.openVault?.isDecoy == true
        return SecurityState(
            fingerprintAvailable = canUseStrongBiometric(context) && !decoyOpen,
            fingerprintOn = session.isBiometricEnrolled(),
            pinOn = session.hasPin(),
            autoLockMs = autoLock.delayMs,
            panicAvailable = panic.available,
            panicLock = settings.panicLock,
            wipeAfterTen = settings.wipeAfterTen,
            isDecoy = decoyOpen,
            hasDecoy = session.hasDecoy(),
            lastBackupAt = backups.lastBackupAt(),
        )
    }

    private val _delete = MutableStateFlow(DeleteVault())
    val delete: StateFlow<DeleteVault> = _delete.asStateFlow()

    fun onDeletePassphrase(value: String) = _delete.update { it.copy(passphrase = value, error = null) }

    fun resetDelete() { _delete.value = DeleteVault() }

    /** Destroys everything after checking the passphrase; navigation notices the vault is gone. */
    fun deleteVault() {
        val d = _delete.value
        if (d.passphrase.isEmpty() || d.busy) return
        _delete.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = session.deleteVault(d.passphrase.toCharArray())
            _delete.update {
                when (result) {
                    UnlockResult.Success -> DeleteVault(done = true)
                    is UnlockResult.Wrong -> it.copy(busy = false, passphrase = "", error = "That passphrase is wrong. ${result.freeAttemptsLeft} tries before a wait.")
                    is UnlockResult.LockedOut -> it.copy(busy = false, passphrase = "", error = "Too many wrong attempts. Try again in ${(result.remainingMs + 59_999) / 60_000} min.")
                    is UnlockResult.Failed -> it.copy(busy = false, passphrase = "", error = result.message)
                    UnlockResult.Wiped -> DeleteVault(done = true)
                }
            }
        }
    }

    fun setPanicLock(on: Boolean) {
        settings.panicLock = on
        panic.refresh()
        refresh()
    }

    fun setWipeAfterTen(on: Boolean) {
        settings.wipeAfterTen = on
        _state.update { load().copy(message = if (on) "Wipe after 10 wrong attempts is on." else "Wipe after 10 wrong attempts is off.") }
    }

    fun onDecoyPassphrase(v: String) = _decoy.update { it.copy(passphrase = v, error = null) }
    fun onDecoyConfirm(v: String) = _decoy.update { it.copy(confirm = v, error = null) }
    fun onDecoyPin(v: String) = _decoy.update { it.copy(pin = v.filter(Char::isDigit).take(6), error = null) }
    fun resetDecoy() { _decoy.value = DecoySetup() }

    fun createDecoy() {
        val d = _decoy.value
        if (!d.canSubmit) return
        _decoy.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = session.createDecoy(d.passphrase.toCharArray(), d.pin.takeIf { it.isNotEmpty() }?.toCharArray())
            if (result == UnlockResult.Success) {
                _decoy.value = DecoySetup(done = true)
                _state.update { load().copy(message = "Decoy vault ready. Lock, then unlock with the decoy passphrase or PIN to fill it with harmless files.") }
            } else {
                _decoy.update { it.copy(busy = false, error = (result as? UnlockResult.Failed)?.message ?: "Couldn't create the decoy vault.") }
            }
        }
    }

    fun removeDecoy() {
        session.removeDecoy()
        _state.update { load().copy(message = "Decoy vault deleted.") }
    }

    fun refresh() = _state.update { load().copy(message = it.message) }

    fun setAutoLock(ms: Long) {
        autoLock.delayMs = ms
        refresh()
    }

    fun enrollCipher(): Cipher = session.biometricEnrollCipher()

    fun completeEnroll(cipher: Cipher) {
        session.completeBiometricEnroll(cipher)
        _state.update { load().copy(message = "Fingerprint unlock is on.") }
    }

    fun disableFingerprint() {
        session.disableBiometric()
        _state.update { load().copy(message = "Fingerprint unlock is off.") }
    }

    fun removePin() {
        session.removePin()
        _state.update { load().copy(message = "PIN removed. Unlock with your passphrase.") }
    }

    fun setPin(pin: String, onDone: () -> Unit) {
        viewModelScope.launch {
            session.setPin(pin.toCharArray())
            _state.update { load().copy(message = "New PIN saved.") }
            onDone()
        }
    }

    fun lockNow() = session.lock()

    fun showMessage(text: String?) = _state.update { it.copy(message = text) }

    fun onCurrent(v: String) = _change.update { it.copy(current = v, error = null) }
    fun onReplacement(v: String) = _change.update { it.copy(replacement = v, error = null) }
    fun onConfirm(v: String) = _change.update { it.copy(confirm = v, error = null) }
    fun resetChange() { _change.value = PassphraseChange() }

    fun submitChange() {
        val c = _change.value
        if (!c.canSubmit) return
        _change.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = session.changePassphrase(c.current.toCharArray(), c.replacement.toCharArray())
            _change.update {
                when (result) {
                    UnlockResult.Success -> PassphraseChange(done = true)
                    is UnlockResult.Wrong -> it.copy(busy = false, current = "", error = "Current passphrase is wrong. ${result.freeAttemptsLeft} tries before a wait.")
                    is UnlockResult.LockedOut -> it.copy(busy = false, current = "", error = "Too many wrong attempts. Try again in ${(result.remainingMs + 59_999) / 60_000} min.")
                    is UnlockResult.Failed -> it.copy(busy = false, error = result.message)
                    UnlockResult.Wiped -> PassphraseChange()
                }
            }
            if (result == UnlockResult.Success) _state.update { it.copy(message = "Passphrase changed. Your recovery key still works.") }
        }
    }
}
