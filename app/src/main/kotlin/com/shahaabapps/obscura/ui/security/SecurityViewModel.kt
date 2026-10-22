package com.shahaabapps.obscura.ui.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.BuildConfig
import com.shahaabapps.obscura.ui.components.canUseStrongBiometric
import com.shahaabapps.obscura.ui.setup.PassphraseStrength
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

data class SecurityState(
    val fingerprintAvailable: Boolean = false,
    val fingerprintOn: Boolean = false,
    val pinOn: Boolean = false,
    val autoLockMs: Long = AutoLock.DEFAULT_DELAY_MS,
    val message: String? = null,
    val version: String = BuildConfig.VERSION_NAME,
) {
    /** A rough 0–100 picture of how well the vault is protected, for the health card. */
    val score: Int
        get() {
            var s = 70
            if (fingerprintOn || pinOn) s += 5
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

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val autoLock: AutoLock,
) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    private val _change = MutableStateFlow(PassphraseChange())
    val change: StateFlow<PassphraseChange> = _change.asStateFlow()

    private fun load() = SecurityState(
        fingerprintAvailable = canUseStrongBiometric(context),
        fingerprintOn = session.isBiometricEnrolled(),
        pinOn = session.hasPin(),
        autoLockMs = autoLock.delayMs,
    )

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
                }
            }
            if (result == UnlockResult.Success) _state.update { it.copy(message = "Passphrase changed. Your recovery key still works.") }
        }
    }
}
