package com.shahaabapps.obscura.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.setup.SealingIndicator
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewRecoveryKey(
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** The new key, shown once; cleared when the screen closes. */
    val key: String? = null,
)

@HiltViewModel
class NewRecoveryKeyViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {
    private val _state = MutableStateFlow(NewRecoveryKey())
    val state: StateFlow<NewRecoveryKey> = _state.asStateFlow()

    fun onPassphrase(value: String) = _state.update { it.copy(passphrase = value, error = null) }

    fun create() {
        val s = _state.value
        if (s.passphrase.isEmpty() || s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val (result, key) = session.newRecoveryKey(s.passphrase.toCharArray())
            _state.update {
                when (result) {
                    UnlockResult.Success -> NewRecoveryKey(key = key?.format()).also { key.wipe() }
                    is UnlockResult.Wrong -> it.copy(busy = false, passphrase = "", error = "That passphrase is wrong. ${result.freeAttemptsLeft} tries before a wait.")
                    is UnlockResult.LockedOut -> it.copy(busy = false, passphrase = "", error = "Too many wrong attempts. Try again in ${(result.remainingMs + 59_999) / 60_000} min.")
                    is UnlockResult.Failed -> it.copy(busy = false, error = result.message)
                    UnlockResult.Wiped -> NewRecoveryKey()
                }
            }
        }
    }

    private fun com.shahaabapps.obscura.crypto.RecoveryKey?.wipe() = this?.wipe()

    override fun onCleared() {
        _state.value = NewRecoveryKey()
    }
}

/**
 * Security → Recovery key. Keys are never stored, so the current one can't be shown; this makes
 * a new one (after the passphrase) and the old one stops working.
 */
@Composable
fun NewRecoveryKeyScreen(onBack: () -> Unit, viewModel: NewRecoveryKeyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        Spacer(Modifier.height(4.dp))
        val key = state.key
        if (key == null) {
            Text("New recovery key", style = PrismType.title, color = Prism.Paper)
            Text(
                "Obscura never stores your recovery key, so it can't show the one you got at setup. You can make a new one instead: the old key stops working straight away, and your passphrase, PIN and fingerprint stay the same.",
                style = PrismType.body,
                color = Prism.Muted,
            )
            Spacer(Modifier.height(4.dp))
            SecretField(
                "Current passphrase",
                state.passphrase,
                viewModel::onPassphrase,
                accent = if (state.error != null) Prism.Orange else Prism.Line,
                onImeAction = viewModel::create,
            )
            state.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }
            if (state.busy) {
                SealingIndicator("Sealing a new recovery key…")
            } else {
                PillButton(
                    "Create a new recovery key",
                    onClick = viewModel::create,
                    container = if (state.passphrase.isNotEmpty()) Prism.Sun else Prism.SurfaceHigh,
                    content = if (state.passphrase.isNotEmpty()) Prism.Ink else Prism.Muted,
                )
            }
        } else {
            Text("Your new recovery key.", style = PrismType.title, color = Prism.Paper)
            Text(
                "The old key no longer works. Save this one now: once you leave this screen, Obscura can't show it again.",
                style = PrismType.body,
                color = Prism.Muted,
            )
            RecoveryKeyBlock(key)
            SaveRecoveryPdf(key)
            GhostButton("Done", onClick = onBack)
        }
    }
}
