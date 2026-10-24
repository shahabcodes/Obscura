package com.shahaabapps.obscura.ui.recovery

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.recovery.RecoveryKeyPdf
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.setup.PassphraseStrength
import com.shahaabapps.obscura.ui.setup.SealingIndicator
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PdfExport(
    val asking: Boolean = false,
    val password: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val strength get() = PassphraseStrength.evaluate(password)
    val canSave get() = strength.score >= MIN_PDF_SCORE && password == confirm && !busy

    companion object {
        /** "Fair" or better: the PDF is offline-guessable, so it needs a real password. */
        const val MIN_PDF_SCORE = 2
    }
}

/** Writes the recovery key to a password-protected PDF wherever the user chooses. */
@HiltViewModel
class RecoveryPdfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val autoLock: AutoLock,
) : ViewModel() {

    private val _state = MutableStateFlow(PdfExport())
    val state: StateFlow<PdfExport> = _state.asStateFlow()

    private var holding = false

    fun open() {
        _state.value = PdfExport(asking = true)
    }

    fun dismiss() {
        if (!_state.value.busy) _state.update { PdfExport(saved = it.saved) }
    }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onConfirm(value: String) = _state.update { it.copy(confirm = value, error = null) }

    /**
     * Checks the password, then returns true when the save picker may open. The vault stays open
     * while the picker is on screen, so the key on screen isn't lost to auto-lock.
     */
    suspend fun ready(): Boolean {
        val s = _state.value
        if (!s.canSave) return false
        _state.update { it.copy(busy = true, error = null) }
        if (session.isVaultPassphrase(s.password.toCharArray())) {
            _state.update { it.copy(busy = false, error = "Use a different password from your vault passphrase. This page has to work when that passphrase is forgotten.") }
            return false
        }
        if (!holding) {
            holding = true
            autoLock.hold()
        }
        return true
    }

    fun save(target: Uri?, recoveryKey: String) {
        release()
        if (target == null) {
            _state.update { it.copy(busy = false) }
            return
        }
        val password = _state.value.password.toCharArray()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val vaultId = session.openVaultId() ?: error("The vault locked. Unlock it and try again.")
                    val bytes = RecoveryKeyPdf.build(recoveryKey, vaultId, password)
                    (context.contentResolver.openOutputStream(target, "wt") ?: error("That location can't be written to"))
                        .use { it.write(bytes) }
                }
            }
            password.fill(' ')
            _state.value = if (result.isSuccess) PdfExport(saved = true)
            else _state.value.copy(busy = false, error = "The PDF couldn't be saved: ${result.exceptionOrNull()?.message}")
        }
    }

    private fun release() {
        if (holding) {
            holding = false
            autoLock.release()
        }
    }

    override fun onCleared() = release()
}

/** The 8 groups of a recovery key on a pink block, with show and hide. */
@Composable
fun RecoveryKeyBlock(recoveryKey: String) {
    var revealed by rememberSaveable { mutableStateOf(true) }
    val groups = recoveryKey.split("-")
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Pink).padding(16.dp)) {
        groups.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, group ->
                    Row(
                        Modifier.weight(1f).prismBlock(Prism.Ink, 14).padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(row * 2 + col + 1), style = PrismType.label, color = Prism.Muted)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (revealed) group else "••••",
                            style = PrismType.heading.copy(fontFamily = PrismType.label.fontFamily, letterSpacing = PrismType.label.letterSpacing),
                            color = Prism.Paper,
                        )
                    }
                }
            }
            if (row < 3) Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            onClick = { revealed = !revealed },
            shape = RoundedCornerShape(16.dp),
            color = Prism.Ink,
            contentColor = Prism.Pink,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (revealed) "Hide key" else "Show key", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
            }
        }
    }
}

/** "Save as protected PDF": a password sheet, then the system save picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveRecoveryPdf(recoveryKey: String, viewModel: RecoveryPdfViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        viewModel.save(uri, recoveryKey)
    }
    val scope = rememberCoroutineScope()
    val choose: () -> Unit = {
        scope.launch { if (viewModel.ready()) picker.launch("Obscura recovery key.pdf") }
    }

    Surface(
        onClick = viewModel::open,
        shape = RoundedCornerShape(30.dp),
        color = Prism.Paper,
        contentColor = Prism.Ink,
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Save as protected PDF", style = PrismType.button)
        }
    }
    if (state.saved) {
        Text(
            "Saved. Open it with your PDF password, print it, then delete the file if you only want the paper copy.",
            style = PrismType.body,
            color = Prism.Mint,
            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (state.asking) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Prism.Surface,
        ) {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Protect the PDF", style = PrismType.heading, color = Prism.Paper)
                Text(
                    "The PDF is encrypted with AES-256 and opens only with this password. Keep it apart from the printed page, and don't reuse your vault passphrase.",
                    style = PrismType.body,
                    color = Prism.Muted,
                )
                SecretField(
                    "PDF password",
                    state.password,
                    viewModel::onPassword,
                    accent = if (state.strength.score >= PdfExport.MIN_PDF_SCORE) Prism.Mint else Prism.Line,
                )
                Text("${state.strength.label} · ${state.strength.crackTime}", style = PrismType.label, color = Prism.Muted)
                SecretField(
                    "Repeat PDF password",
                    state.confirm,
                    viewModel::onConfirm,
                    accent = if (state.confirm.isNotEmpty() && state.confirm == state.password) Prism.Mint else Prism.Line,
                    onImeAction = choose,
                )
                state.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }
                if (state.busy) {
                    SealingIndicator("Encrypting the PDF…")
                } else {
                    PillButton(
                        "Choose where to save",
                        onClick = choose,
                        container = if (state.canSave) Prism.Sun else Prism.SurfaceHigh,
                        content = if (state.canSave) Prism.Ink else Prism.Muted,
                    )
                }
            }
        }
    }
}
