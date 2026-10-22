package com.shahaabapps.obscura.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val passphrase: String = "",
    val confirm: String = "",
    val creating: Boolean = false,
    val recoveryKey: String? = null,
    val pinFirst: String = "",
    val pinConfirm: String = "",
    val pinStage: PinStage = PinStage.ENTER,
    val savingPin: Boolean = false,
    val error: String? = null,
) {
    val strength get() = PassphraseStrength.evaluate(passphrase)
    val matches get() = passphrase.isNotEmpty() && passphrase == confirm
    val canCreate get() = strength.score >= PassphraseStrength.MIN_SCORE && matches && !creating
}

enum class PinStage { ENTER, CONFIRM }

/** Shared across the setup screens: passphrase → recovery key → PIN → fingerprint. */
@HiltViewModel
class SetupViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onPassphrase(value: String) = _state.update { it.copy(passphrase = value, error = null) }

    fun onConfirm(value: String) = _state.update { it.copy(confirm = value, error = null) }

    fun generate() {
        val generated = PassphraseStrength.generate()
        _state.update { it.copy(passphrase = generated, confirm = generated, error = null) }
    }

    /** Derives keys (about a second of Argon2id) and creates the vault. */
    fun create(onCreated: () -> Unit) {
        val current = _state.value
        if (!current.canCreate) return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val recovery = session.create(current.passphrase.toCharArray())
                _state.update {
                    // The passphrase strings are dropped as soon as the vault exists.
                    it.copy(passphrase = "", confirm = "", creating = false, recoveryKey = recovery.format())
                }
                recovery.wipe()
                onCreated()
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, error = "Could not create the vault: ${e.message}") }
            }
        }
    }

    fun forgetRecoveryKey() = _state.update { it.copy(recoveryKey = null) }

    fun onPinDigit(digit: Char, onSaved: () -> Unit) {
        val s = _state.value
        if (s.savingPin) return
        when (s.pinStage) {
            PinStage.ENTER -> {
                val next = s.pinFirst + digit
                _state.update { it.copy(pinFirst = next, error = null) }
                if (next.length == 6) _state.update { it.copy(pinStage = PinStage.CONFIRM) }
            }
            PinStage.CONFIRM -> {
                val next = s.pinConfirm + digit
                _state.update { it.copy(pinConfirm = next) }
                if (next.length == 6) {
                    if (next == s.pinFirst) savePin(next, onSaved)
                    else _state.update { SetupUiState(recoveryKey = it.recoveryKey, error = "PINs didn't match. Try again.") }
                }
            }
        }
    }

    fun onPinDelete() = _state.update {
        when (it.pinStage) {
            PinStage.ENTER -> it.copy(pinFirst = it.pinFirst.dropLast(1))
            PinStage.CONFIRM -> it.copy(pinConfirm = it.pinConfirm.dropLast(1))
        }
    }

    private fun savePin(pin: String, onSaved: () -> Unit) {
        _state.update { it.copy(savingPin = true) }
        viewModelScope.launch {
            try {
                session.setPin(pin.toCharArray())
                _state.update { it.copy(pinFirst = "", pinConfirm = "", savingPin = false) }
                onSaved()
            } catch (e: Exception) {
                _state.update { it.copy(pinFirst = "", pinConfirm = "", pinStage = PinStage.ENTER, savingPin = false, error = "Could not save the PIN: ${e.message}") }
            }
        }
    }

    fun biometricEnrollCipher() = session.biometricEnrollCipher()

    fun completeBiometric(cipher: javax.crypto.Cipher) = session.completeBiometricEnroll(cipher)
}
