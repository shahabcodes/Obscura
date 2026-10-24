package com.shahaabapps.obscura.ui.unlock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.Motion
import com.shahaabapps.obscura.ui.components.MotionArt
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.PinDots
import com.shahaabapps.obscura.ui.components.PinPad
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.components.showBiometricPrompt
import com.shahaabapps.obscura.ui.setup.SealingIndicator
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import kotlinx.coroutines.delay

/** Main lock screen: PIN pad when a PIN exists, otherwise a passphrase field. Fingerprint when enrolled. */
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    onUsePassphrase: () -> Unit,
    onUseRecovery: () -> Unit,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    fun promptFingerprint() {
        val cipher = viewModel.biometricCipher() ?: return
        activity ?: return
        showBiometricPrompt(
            activity = activity,
            title = "Unlock Obscura",
            subtitle = "Touch the fingerprint sensor",
            negativeText = if (state.hasPin) "Use PIN" else "Use passphrase",
            cipher = cipher,
            onSuccess = { viewModel.onBiometricAuthorized(it) },
            onFailure = { message -> if (message != null) viewModel.onBiometricError(message) },
        )
    }

    // Offer the fingerprint sheet straight away, like the system lock screen does.
    LaunchedEffect(Unit) {
        if (state.biometricAvailable && !state.lockedOut) promptFingerprint()
    }

    UnlockLayers(state, viewModel, onUnlocked) {
        if (!state.hasPin) {
            PassphraseUnlockContent(state, viewModel, onBack = null, onUseRecovery = onUseRecovery, onFingerprint = if (state.biometricAvailable) ::promptFingerprint else null)
        } else {
            PinUnlockContent(state, viewModel, onUsePassphrase, onUseRecovery, onFingerprint = if (state.biometricAvailable) ::promptFingerprint else null)
        }
    }
}

@Composable
private fun PinUnlockContent(
    state: UnlockUiState,
    viewModel: UnlockViewModel,
    onUsePassphrase: () -> Unit,
    onUseRecovery: () -> Unit,
    onFingerprint: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockEmblem()
        Spacer(Modifier.height(20.dp))
        Text(if (state.busy) "Unlocking…" else "Locked.", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(6.dp))
        Text("Enter your 6-digit PIN", style = PrismType.body, color = Prism.Muted)
        Spacer(Modifier.height(26.dp))
        if (state.lockedOut) {
            LockoutBlock(state.lockoutMs, state.attemptsBeforeWipe)
        } else {
            PinDots(filled = state.pin.length, error = state.wrong)
            Spacer(Modifier.height(12.dp))
            Message(state.message)
            Spacer(Modifier.height(12.dp))
            PinPad(
                onDigit = { viewModel.onPinDigit(it) },
                onDelete = viewModel::onPinDelete,
                onBiometric = onFingerprint,
                enabled = !state.busy,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onUseRecovery) { Text("Forgot? Use recovery key", style = PrismType.body, color = Prism.Muted) }
            TextButton(onClick = onUsePassphrase) { Text("Use passphrase", style = PrismType.body, color = Prism.Sun) }
        }
    }
}

@Composable
fun PassphraseUnlockScreen(
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
    onUseRecovery: () -> Unit,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UnlockLayers(state, viewModel, onUnlocked) {
        PassphraseUnlockContent(state, viewModel, onBack, onUseRecovery, onFingerprint = null)
    }
}

@Composable
private fun PassphraseUnlockContent(
    state: UnlockUiState,
    viewModel: UnlockViewModel,
    onBack: (() -> Unit)?,
    onUseRecovery: () -> Unit,
    onFingerprint: (() -> Unit)?,
) {
    UnlockFrame(onBack) {
        LockEmblem()
        Spacer(Modifier.height(20.dp))
        Text("Enter your passphrase.", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(24.dp))
        if (state.lockedOut) {
            LockoutBlock(state.lockoutMs, state.attemptsBeforeWipe)
        } else {
            SecretField(
                label = "Passphrase",
                value = state.passphrase,
                onValueChange = viewModel::onPassphrase,
                accent = if (state.wrong) Prism.Orange else Prism.Line,
                onImeAction = viewModel::submitPassphrase,
            )
            Spacer(Modifier.height(10.dp))
            Message(state.message)
            Spacer(Modifier.height(20.dp))
            if (state.busy) {
                SealingIndicator("Unlocking…")
            } else {
                PillButton("Unlock", onClick = viewModel::submitPassphrase)
            }
            if (onFingerprint != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onFingerprint, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Use fingerprint", style = PrismType.body, color = Prism.Sun)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onUseRecovery, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Forgot? Use recovery key", style = PrismType.body, color = Prism.Muted)
        }
    }
}

@Composable
fun RecoveryUnlockScreen(
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UnlockLayers(state, viewModel, onUnlocked) { RecoveryContent(state, viewModel, onBack) }
}

@Composable
private fun RecoveryContent(state: UnlockUiState, viewModel: UnlockViewModel, onBack: () -> Unit) {
    UnlockFrame(onBack) {
        Text("Use your recovery key.", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        Text(
            "Type the 8 groups of 4 characters you wrote down. Dashes and spaces are optional; O and 0, I and 1 are treated the same.",
            style = PrismType.body,
            color = Prism.Muted,
        )
        Spacer(Modifier.height(24.dp))
        if (state.lockedOut) {
            LockoutBlock(state.lockoutMs, state.attemptsBeforeWipe)
        } else {
            SecretField(
                label = "Recovery key",
                value = state.recovery,
                onValueChange = viewModel::onRecovery,
                placeholder = "XXXX-XXXX-XXXX-…",
                revealable = false,
                keyboardType = KeyboardType.Ascii,
                accent = if (state.wrong) Prism.Orange else Prism.Line,
                onImeAction = viewModel::submitRecovery,
            )
            Spacer(Modifier.height(10.dp))
            Message(state.message)
            Spacer(Modifier.height(20.dp))
            if (state.busy) SealingIndicator("Unlocking…") else PillButton("Unlock", onClick = viewModel::submitRecovery)
        }
    }
}

/**
 * Puts the unlock and panic animations over an unlock screen. After a successful unlock the
 * shackle animation plays, then [onUnlocked] runs. After a panic lock, once the phone is face-up
 * again, a short pink confirmation shows that the vault locked.
 */
@Composable
private fun UnlockLayers(state: UnlockUiState, viewModel: UnlockViewModel, onUnlocked: () -> Unit, content: @Composable () -> Unit) {
    val panicNotice by viewModel.panicNotice.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        when {
            state.unlocked -> Overlay(Prism.Ink) {
                MotionArt(Motion.UNLOCK, Modifier.size(176.dp), onFinished = onUnlocked)
                Spacer(Modifier.height(8.dp))
                Text("Unlocked.", style = PrismType.title, color = Prism.Paper)
            }
            panicNotice -> {
                var played by remember { mutableStateOf(false) }
                LaunchedEffect(played) {
                    if (played) {
                        delay(700)
                        viewModel.panicShown()
                    }
                }
                Overlay(Prism.Pink, onTap = viewModel::panicShown) {
                    MotionArt(Motion.PANIC, Modifier.size(176.dp), onFinished = { played = true })
                    Spacer(Modifier.height(8.dp))
                    Text("Locked instantly.", style = PrismType.title, color = Prism.Ink)
                    Text("Panic lock", style = PrismType.label, color = Prism.Ink)
                }
            }
        }
    }
}

/** Full-screen layer that swallows touches, so nothing underneath is pressed by accident. */
@Composable
private fun Overlay(color: androidx.compose.ui.graphics.Color, onTap: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(color)
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun UnlockFrame(onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.height(24.dp))
        }
        content()
    }
}

@Composable
private fun Message(text: String?) {
    Text(
        text ?: " ",
        style = PrismType.body,
        color = Prism.Orange,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The orange frozen-vault card with a live countdown. */
@Composable
fun LockoutBlock(remainingMs: Long, attemptsBeforeWipe: Int? = null) {
    val totalSeconds = (remainingMs + 999) / 1000
    val clock = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    Column(
        Modifier
            .fillMaxWidth()
            .prismBlock(Prism.Orange, 32)
            .padding(22.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("VAULT FROZEN", style = PrismType.label, color = Prism.Ink)
        Spacer(Modifier.height(18.dp))
        Text(clock, style = PrismType.stat.copy(fontFamily = PrismType.label.fontFamily, fontSize = PrismType.stat.fontSize), color = Prism.Ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "Too many wrong attempts. Each new mistake makes the wait longer. Restarting the phone does not skip it.",
            style = PrismType.body,
            color = Prism.Ink,
        )
        if (attemptsBeforeWipe != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "$attemptsBeforeWipe more wrong ${if (attemptsBeforeWipe == 1) "attempt" else "attempts"} and the vault is erased for good.",
                style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                color = Prism.Ink,
            )
        }
    }
}

/** Sun disc over a cobalt horizon with a black diamond keyhole, matching the app icon. */
@Composable
private fun LockEmblem() {
    Canvas(Modifier.size(88.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Prism.Sun, r, c)
        drawArc(Prism.Cobalt, 0f, 180f, true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
        val d = r * 0.42f
        rotate(45f, pivot = c) {
            drawRect(Prism.Ink, topLeft = Offset(c.x - d / 2, c.y - d / 2), size = Size(d, d))
        }
        drawCircle(Prism.Pink, r * 0.12f, c)
    }
}
