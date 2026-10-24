package com.shahaabapps.obscura.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.Motion
import com.shahaabapps.obscura.ui.components.MotionArt
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.PinDots
import com.shahaabapps.obscura.ui.components.PinPad
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.components.canUseStrongBiometric
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.components.showBiometricPrompt
import com.shahaabapps.obscura.ui.recovery.RecoveryKeyBlock
import com.shahaabapps.obscura.ui.recovery.SaveRecoveryPdf
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

@Composable
private fun SetupFrame(
    step: Int,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) else Spacer(Modifier.size(48.dp))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .width(if (i == step) 28.dp else 12.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(if (i < step) Prism.Mint else if (i == step) Prism.Sun else Prism.Line),
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("STEP ${step + 1} / 4", style = PrismType.label, color = Prism.Muted)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun PassphraseSetupScreen(viewModel: SetupViewModel, onBack: () -> Unit, onCreated: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var generatedShown by remember { mutableStateOf<String?>(null) }
    val strength = state.strength
    val strengthColor = when (strength.score) {
        0 -> Prism.Line
        1 -> Color(0xFFFF5A5A)
        2 -> Prism.Orange
        3 -> Prism.Sun
        else -> Prism.Mint
    }

    SetupFrame(step = 0, onBack = if (state.creating) null else onBack) {
        Text("Choose a passphrase only you know.", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        Text(
            "It never leaves this phone and can't be reset. Four or more random words works best.",
            style = PrismType.body,
            color = Prism.Muted,
        )
        Spacer(Modifier.height(24.dp))
        SecretField(
            label = "Passphrase",
            value = state.passphrase,
            onValueChange = {
                generatedShown = null
                viewModel.onPassphrase(it)
            },
            placeholder = "e.g. lantern orbit velvet canyon",
            accent = if (strength.score >= PassphraseStrength.MIN_SCORE) strengthColor else Prism.Line,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            trailing = {
                Surface(
                    onClick = {
                        viewModel.generate()
                        generatedShown = viewModel.state.value.passphrase
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = Prism.Line,
                    contentColor = Prism.Paper,
                    modifier = Modifier.height(46.dp),
                ) {
                    Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("Generate", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
                    }
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(8.dp).clip(CircleShape)
                        .background(if (i < strength.score) strengthColor else Prism.Line),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(strength.label, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = strengthColor.takeIf { strength.score > 0 } ?: Prism.Muted)
            Spacer(Modifier.weight(1f))
            Text(strength.crackTime, style = PrismType.label, color = Prism.Muted)
        }

        generatedShown?.let { words ->
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().prismBlock(Prism.Sun, 22).padding(16.dp)) {
                Text("YOUR NEW PASSPHRASE", style = PrismType.label, color = Prism.Ink)
                Spacer(Modifier.height(6.dp))
                Text(words, style = PrismType.heading, color = Prism.Ink)
                Spacer(Modifier.height(6.dp))
                Text("Write it on paper before you continue.", style = PrismType.body, color = Prism.Ink)
            }
        }

        Spacer(Modifier.height(20.dp))
        SecretField(
            label = "Repeat passphrase",
            value = state.confirm,
            onValueChange = viewModel::onConfirm,
            accent = if (state.matches) Prism.Mint else Prism.Line,
            onImeAction = { viewModel.create(onCreated) },
        )
        Spacer(Modifier.height(18.dp))
        Rule(strength.score >= PassphraseStrength.MIN_SCORE, "Strong enough (Good or better)")
        Spacer(Modifier.height(8.dp))
        Rule(state.matches, "Both entries match")

        state.error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = PrismType.body, color = Prism.Orange)
        }
        Spacer(Modifier.height(28.dp))
        if (state.creating) {
            SealingIndicator("Sealing your vault…")
        } else {
            PillButton(
                "Create vault",
                onClick = { viewModel.create(onCreated) },
                container = if (state.canCreate) Prism.Sun else Prism.SurfaceHigh,
                content = if (state.canCreate) Prism.Ink else Prism.Muted,
            )
        }
    }
}

@Composable
private fun Rule(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (ok) Prism.Mint else Prism.Line),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = Prism.Ink, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = PrismType.body, color = if (ok) Prism.Paper else Prism.Muted)
    }
}

@Composable
fun SealingIndicator(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).prismBlock(Prism.Surface, 30).padding(start = 9.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The motion kit's sealing loop: a file dropping into the vault, on a sun disc.
        androidx.compose.foundation.layout.Box(Modifier.size(42.dp).prismBlock(Prism.Sun, 21)) {
            MotionArt(Motion.SEAL, Modifier.matchParentSize().padding(3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = PrismType.body, color = Prism.Paper)
    }
}

@Composable
fun RecoveryKeyScreen(viewModel: SetupViewModel, onContinue: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var saved by rememberSaveable { mutableStateOf(false) }
    // The vault already exists here; going back would only lose sight of the key.
    BackHandler { }

    SetupFrame(step = 1, onBack = null) {
        Text("Your recovery key.", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        Text(
            "If you forget your passphrase, this key is the only way back in, and Obscura can't show it again. Write it on paper or save it as a password-protected PDF to print. Never photograph it.",
            style = PrismType.body,
            color = Prism.Muted,
        )
        Spacer(Modifier.height(22.dp))
        state.recoveryKey?.let { key ->
            RecoveryKeyBlock(key)
            Spacer(Modifier.height(12.dp))
            SaveRecoveryPdf(key)
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = saved,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = Prism.Sun, checkmarkColor = Prism.Ink, uncheckedColor = Prism.Muted),
            )
            Spacer(Modifier.width(8.dp))
            Text("I've written this key down or saved the PDF", style = PrismType.body, color = Prism.Paper)
        }
        Spacer(Modifier.height(28.dp))
        PillButton(
            "Continue",
            onClick = {
                if (saved) {
                    viewModel.forgetRecoveryKey()
                    onContinue()
                }
            },
            container = if (saved) Prism.Sun else Prism.SurfaceHigh,
            content = if (saved) Prism.Ink else Prism.Muted,
        )
    }
}

@Composable
fun PinSetupScreen(viewModel: SetupViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { }
    val confirming = state.pinStage == PinStage.CONFIRM
    SetupFrame(step = 2, onBack = null) {
        Text(if (confirming) "Repeat your PIN." else "Choose a 6-digit PIN.", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        Text(
            "For everyday unlocking. It only works on this phone, and your passphrase always works too.",
            style = PrismType.body,
            color = Prism.Muted,
        )
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PinDots(filled = if (confirming) state.pinConfirm.length else state.pinFirst.length, error = state.error != null)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            state.error ?: if (state.savingPin) "Saving…" else " ",
            style = PrismType.body,
            color = if (state.error != null) Prism.Orange else Prism.Muted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(20.dp))
        PinPad(
            onDigit = { viewModel.onPinDigit(it, onDone) },
            onDelete = viewModel::onPinDelete,
            enabled = !state.savingPin,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Skip — unlock with passphrase only", style = PrismType.body, color = Prism.Muted)
        }
    }
}

@Composable
fun BiometricSetupScreen(viewModel: SetupViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val available = remember { canUseStrongBiometric(context) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { }
    LaunchedEffect(available) { if (!available) onDone() }
    if (!available) return

    SetupFrame(step = 3, onBack = null) {
        Box(Modifier.fillMaxWidth().height(220.dp).prismBlock(Prism.Mint), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                val stroke = Stroke(6.dp.toPx())
                listOf(0.95f, 0.75f, 0.55f, 0.35f).forEach { f ->
                    drawArc(Prism.Ink, 200f, 140f, false, topLeft = Offset(size.width * (1 - f) / 2, size.height * (1 - f) / 2), size = androidx.compose.ui.geometry.Size(size.width * f, size.height * f), style = stroke)
                }
                drawCircle(Prism.Ink, radius = 10.dp.toPx(), center = Offset(size.width / 2, size.height / 2 + 6.dp.toPx()))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Unlock with your fingerprint?", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        Text(
            "The key stays in this phone's secure hardware. Adding a new fingerprint later turns this off until you unlock with your PIN or passphrase.",
            style = PrismType.body,
            color = Prism.Muted,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = PrismType.body, color = Prism.Orange)
        }
        Spacer(Modifier.height(28.dp))
        PillButton("Use fingerprint", onClick = {
            val activity = context as? FragmentActivity ?: return@PillButton
            val cipher = try {
                viewModel.biometricEnrollCipher()
            } catch (e: Exception) {
                error = "Fingerprint isn't available: ${e.message}"
                return@PillButton
            }
            showBiometricPrompt(
                activity = activity,
                title = "Enable fingerprint unlock",
                subtitle = "Touch the sensor to link it to your vault",
                negativeText = "Cancel",
                cipher = cipher,
                onSuccess = {
                    try {
                        viewModel.completeBiometric(it)
                        onDone()
                    } catch (e: Exception) {
                        error = "Could not enable fingerprint: ${e.message}"
                    }
                },
                onFailure = { message -> if (message != null) error = message },
            )
        })
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Not now", style = PrismType.body, color = Prism.Muted)
        }
    }
}
