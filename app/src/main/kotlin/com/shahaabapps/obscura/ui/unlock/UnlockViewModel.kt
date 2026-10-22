package com.shahaabapps.obscura.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.crypto.QuickUnlock
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

data class UnlockUiState(
    val hasPin: Boolean = false,
    val pin: String = "",
    val passphrase: String = "",
    val recovery: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val wrong: Boolean = false,
    val lockoutMs: Long = 0,
    val biometricAvailable: Boolean = false,
) {
    val lockedOut get() = lockoutMs > 0
}

/** Drives every unlock screen. A successful unlock calls the screen's onUnlocked. */
@HiltViewModel
class UnlockViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {

    private val _state = MutableStateFlow(
        UnlockUiState(hasPin = session.hasPin(), biometricAvailable = session.isBiometricEnrolled()),
    )
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    private var ticker: Job? = null

    init {
        refreshLockout()
    }

    fun onPinDigit(digit: Char, onUnlocked: () -> Unit) {
        val s = _state.value
        if (s.busy || s.lockedOut || s.pin.length >= QuickUnlock.PIN_LENGTH) return
        val next = s.pin + digit
        _state.update { it.copy(pin = next, message = null, wrong = false) }
        if (next.length == QuickUnlock.PIN_LENGTH) {
            submit({ session.unlockWithPin(next.toCharArray()) }, "PIN", onUnlocked) { it.copy(pin = "") }
        }
    }

    fun onPinDelete() = _state.update { it.copy(pin = it.pin.dropLast(1)) }

    fun onPassphrase(value: String) = _state.update { it.copy(passphrase = value, message = null, wrong = false) }

    fun submitPassphrase(onUnlocked: () -> Unit) {
        val text = _state.value.passphrase
        if (text.isEmpty()) return
        submit({ session.unlockWithPassphrase(text.toCharArray()) }, "passphrase", onUnlocked) { it.copy(passphrase = "") }
    }

    fun onRecovery(value: String) = _state.update { it.copy(recovery = value.uppercase(), message = null, wrong = false) }

    fun submitRecovery(onUnlocked: () -> Unit) {
        val text = _state.value.recovery
        if (text.isBlank()) return
        submit({ session.unlockWithRecoveryKey(text) }, "recovery key", onUnlocked) { it }
    }

    /** Cipher for the fingerprint prompt, or null if fingerprint unlock just became unavailable. */
    fun biometricCipher(): Cipher? = session.biometricUnlockCipher().also {
        if (it == null) _state.update { s -> s.copy(biometricAvailable = false) }
    }

    fun onBiometricAuthorized(cipher: Cipher, onUnlocked: () -> Unit) {
        submit({ session.unlockWithBiometric(cipher) }, "fingerprint", onUnlocked) { it }
    }

    fun onBiometricError(message: String) = _state.update { it.copy(message = message) }

    private fun submit(
        action: suspend () -> UnlockResult,
        what: String,
        onUnlocked: () -> Unit,
        clear: (UnlockUiState) -> UnlockUiState,
    ) {
        _state.update { it.copy(busy = true, message = null, wrong = false) }
        viewModelScope.launch {
            val result = action()
            _state.update { clear(it).copy(busy = false) }
            when (result) {
                UnlockResult.Success -> onUnlocked()
                is UnlockResult.Wrong -> _state.update {
                    it.copy(
                        wrong = true,
                        message = when (result.freeAttemptsLeft) {
                            0 -> "Wrong $what. The next mistake starts a 1-minute wait."
                            1 -> "Wrong $what. 1 more try before a wait."
                            else -> "Wrong $what. ${result.freeAttemptsLeft} more tries before a wait."
                        },
                    )
                }
                is UnlockResult.LockedOut -> {
                    _state.update { it.copy(wrong = true, message = null) }
                    refreshLockout()
                }
                is UnlockResult.Failed -> _state.update { it.copy(message = result.message) }
            }
        }
    }

    private fun refreshLockout() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val remaining = session.lockoutRemainingMs()
                _state.update { it.copy(lockoutMs = remaining) }
                if (remaining <= 0) break
                delay(500)
            }
        }
    }
}
