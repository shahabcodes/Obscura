package com.shahaabapps.obscura.ui.security

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.PinDots
import com.shahaabapps.obscura.ui.components.PinPad
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.components.showBiometricPrompt
import com.shahaabapps.obscura.ui.setup.PassphraseStrength
import com.shahaabapps.obscura.ui.setup.SealingIndicator
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(viewModel: SecurityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var changingPassphrase by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Security", style = PrismType.title, color = Prism.Paper, modifier = Modifier.padding(horizontal = 4.dp))

        HealthCard(state.score)

        state.message?.let {
            Text(it, style = PrismType.body, color = Prism.Mint, modifier = Modifier.padding(horizontal = 4.dp))
        }

        Section("UNLOCKING") {
            SwitchRow(
                title = "Fingerprint unlock",
                subtitle = when {
                    !state.fingerprintAvailable -> "No fingerprint is set up on this phone"
                    state.fingerprintOn -> "Key sealed in this phone's secure hardware"
                    else -> "Unlock with a touch instead of typing"
                },
                checked = state.fingerprintOn,
                enabled = state.fingerprintAvailable,
            ) { on ->
                if (!on) {
                    viewModel.disableFingerprint()
                } else {
                    val activity = context as? FragmentActivity ?: return@SwitchRow
                    val cipher = runCatching { viewModel.enrollCipher() }.getOrElse {
                        viewModel.showMessage("Fingerprint isn't available: ${it.message}")
                        return@SwitchRow
                    }
                    showBiometricPrompt(
                        activity, "Enable fingerprint unlock", "Touch the sensor to link it to your vault", "Cancel", cipher,
                        onSuccess = { viewModel.completeEnroll(it) },
                        onFailure = { msg -> if (msg != null) viewModel.showMessage(msg) },
                    )
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("6-digit PIN", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
                    Text(if (state.pinOn) "Only works on this phone" else "Off: unlock with your passphrase", style = PrismType.label, color = Prism.Muted)
                }
                if (state.pinOn) {
                    TextButton(onClick = { settingPin = true }) { Text("Change", color = Prism.Sun, style = PrismType.body) }
                    TextButton(onClick = viewModel::removePin) { Text("Remove", color = Prism.Orange, style = PrismType.body) }
                } else {
                    TextButton(onClick = { settingPin = true }) { Text("Set a PIN", color = Prism.Sun, style = PrismType.body) }
                }
            }
        }

        Section("AUTO-LOCK") {
            Text("Lock after leaving the app", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
            Text("The vault always locks the moment the screen turns off.", style = PrismType.label, color = Prism.Muted)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.clip(CircleShape).background(Prism.Ink).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0L to "Instantly", 30_000L to "30 s", 60_000L to "1 min", 300_000L to "5 min").forEach { (ms, label) ->
                    val on = state.autoLockMs == ms
                    Text(
                        label,
                        style = PrismType.label,
                        color = if (on) Prism.Ink else Prism.Paper,
                        modifier = Modifier.clip(CircleShape).background(if (on) Prism.Sun else Color.Transparent)
                            .selectable(selected = on, role = Role.RadioButton) { viewModel.setAutoLock(ms) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }

        Section("PASSPHRASE") {
            Text("Change passphrase", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
            Text("Files aren't re-encrypted, and your recovery key keeps working.", style = PrismType.label, color = Prism.Muted)
            Spacer(Modifier.height(10.dp))
            GhostButton("Change passphrase", onClick = {
                viewModel.resetChange()
                changingPassphrase = true
            })
        }

        PillButton("Lock now", onClick = viewModel::lockNow, container = Prism.Orange)

        Section("ABOUT") {
            AboutRow("Version", state.version)
            AboutRow("Network", "None: no internet permission")
            AboutRow("Files", "AES-256-GCM, a separate key for each")
            AboutRow("Passphrase", "Argon2id, 64 MB, 3 passes")
            AboutRow("Index", "SQLCipher, key derived from the vault")
        }
    }

    if (changingPassphrase) {
        ModalBottomSheet(
            onDismissRequest = { changingPassphrase = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Prism.Surface,
        ) {
            ChangePassphraseSheet(viewModel) { changingPassphrase = false }
        }
    }
    if (settingPin) {
        ModalBottomSheet(
            onDismissRequest = { settingPin = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Prism.Surface,
        ) {
            PinSheet(onDone = { pin -> viewModel.setPin(pin) { settingPin = false } })
        }
    }
}

@Composable
private fun ChangePassphraseSheet(viewModel: SecurityViewModel, onClose: () -> Unit) {
    val change by viewModel.change.collectAsStateWithLifecycle()
    LaunchedEffect(change.done) { if (change.done) onClose() }
    Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Change passphrase", style = PrismType.heading, color = Prism.Paper)
        SecretField("Current passphrase", change.current, viewModel::onCurrent)
        SecretField("New passphrase", change.replacement, viewModel::onReplacement, accent = if (change.strength.score >= PassphraseStrength.MIN_SCORE) Prism.Mint else Prism.Line)
        Text("${change.strength.label} · ${change.strength.crackTime}", style = PrismType.label, color = Prism.Muted)
        SecretField("Repeat new passphrase", change.confirm, viewModel::onConfirm, accent = if (change.confirm.isNotEmpty() && change.confirm == change.replacement) Prism.Mint else Prism.Line, onImeAction = viewModel::submitChange)
        change.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }
        if (change.busy) SealingIndicator("Re-sealing your master key…")
        else PillButton(
            "Change passphrase",
            onClick = viewModel::submitChange,
            container = if (change.canSubmit) Prism.Sun else Prism.SurfaceHigh,
            content = if (change.canSubmit) Prism.Ink else Prism.Muted,
        )
    }
}

/** Enter a new PIN twice. */
@Composable
private fun PinSheet(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val confirming = first.length == 6
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (confirming) "Repeat the new PIN" else "Choose a new 6-digit PIN", style = PrismType.heading, color = Prism.Paper)
        Spacer(Modifier.height(18.dp))
        PinDots(filled = if (confirming) second.length else first.length, error = mismatch)
        Spacer(Modifier.height(8.dp))
        Text(if (mismatch) "PINs didn't match. Start again." else " ", style = PrismType.body, color = Prism.Orange)
        Spacer(Modifier.height(12.dp))
        PinPad(
            onDigit = { d ->
                mismatch = false
                if (!confirming) first += d
                else if (second.length < 6) {
                    second += d
                    if (second.length == 6) {
                        if (second == first) onDone(first) else {
                            mismatch = true
                            first = ""
                            second = ""
                        }
                    }
                }
            },
            onDelete = { if (confirming && second.isNotEmpty()) second = second.dropLast(1) else if (!confirming) first = first.dropLast(1) },
        )
    }
}

@Composable
private fun HealthCard(score: Int) {
    Row(Modifier.fillMaxWidth().prismBlock(Prism.Sun).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(78.dp).semantics { contentDescription = "Vault health $score out of 100" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2
                drawArc(Prism.Ink.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(Prism.Ink, -90f, 360f * score / 100, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("$score", style = PrismType.heading, color = Prism.Ink)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("VAULT HEALTH", style = PrismType.label, color = Prism.Ink)
            Text(
                when {
                    score >= 90 -> "Fortress mode"
                    score >= 80 -> "Well protected"
                    else -> "Could be stronger"
                },
                style = PrismType.heading,
                color = Prism.Ink,
            )
            Text("No internet permission · keys in secure hardware", style = PrismType.label, color = Prism.Ink)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 24).padding(16.dp)) {
        Text(title, style = PrismType.label, color = Prism.Muted)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = if (enabled) Prism.Paper else Prism.Muted)
            Text(subtitle, style = PrismType.label, color = Prism.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Prism.Ink, checkedTrackColor = Prism.Sun, uncheckedTrackColor = Prism.SurfaceHigh),
        )
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Prism.Line))

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = PrismType.label, color = Prism.Muted, modifier = Modifier.width(110.dp))
        Text(value, style = PrismType.body, color = Prism.Paper)
    }
}
