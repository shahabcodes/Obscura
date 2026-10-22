package com.shahaabapps.obscura.ui.backup

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.backup.BackupJob
import com.shahaabapps.obscura.backup.BackupLocation
import com.shahaabapps.obscura.backup.BackupManager
import com.shahaabapps.obscura.backup.BackupOutcome
import com.shahaabapps.obscura.backup.BackupProgress
import com.shahaabapps.obscura.backup.BackupState
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.AutoLock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupInfo(val location: BackupLocation? = null, val lastBackupAt: Long? = null)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val manager: BackupManager,
    private val autoLock: AutoLock,
) : ViewModel() {
    val state: StateFlow<BackupState> = manager.state

    private val _info = MutableStateFlow(load())
    val info: StateFlow<BackupInfo> = _info.asStateFlow()

    private var picking = false

    init {
        // Refresh the remembered location and last backup time whenever a run ends.
        viewModelScope.launch { manager.state.collect { if (it.running == null) _info.value = load() } }
    }

    private fun load() = BackupInfo(manager.location(), manager.lastBackupAt())

    /** Keeps the vault open while the system folder picker is on screen. */
    fun beginPicking() {
        if (!picking) {
            picking = true
            autoLock.hold()
        }
    }

    private fun endPicking() {
        if (picking) {
            picking = false
            autoLock.release()
        }
    }

    /** A folder was picked for backups: remember it and back up straight away. */
    fun locationPicked(tree: Uri?) {
        if (tree != null) {
            manager.setLocation(tree)
            _info.value = load()
            manager.backup()
        }
        endPicking()
    }

    fun backUp(replaceOtherVault: Boolean = false) {
        manager.dismiss()
        manager.backup(replaceOtherVault)
    }

    fun restore(tree: Uri) {
        manager.dismiss()
        manager.restore(tree)
    }

    fun dismiss() = manager.dismiss()

    override fun onCleared() {
        endPicking()
    }
}

/** Asks for notification permission once, so progress can show; the work runs either way. */
@Composable
private fun rememberNotificationGate(): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val withNotifications = rememberNotificationGate()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) withNotifications { viewModel.locationPicked(tree) } else viewModel.locationPicked(null)
    }
    val choose = {
        viewModel.beginPicking()
        pickFolder.launch(null)
    }

    ScreenFrame(title = "Backup", onBack = onBack) {
        when {
            state.running == BackupJob.BACKUP -> ProgressCard("BACKING UP", state.progress, "You can leave the app. The vault stays open until the copy finishes, then locks.")
            state.outcome is BackupOutcome.BackedUp -> {
                val done = state.outcome as BackupOutcome.BackedUp
                ResultCard(
                    color = if (done.verify.ok) Prism.Mint else Prism.Orange,
                    label = if (done.verify.ok) "BACKUP COMPLETE" else "BACKUP NEEDS ATTENTION",
                    headline = if (done.verify.ok) "Checked and complete" else "%,d files didn't copy".format(done.verify.missing + done.verify.mismatched),
                    detail = buildString {
                        append("%,d copied, %,d already there".format(done.result.copied, done.result.unchanged))
                        if (done.result.removed > 0) append(", %,d shredded files removed".format(done.result.removed))
                        append(". ")
                        append(if (done.verify.ok) "Every sealed file is on the drive at the right size." else "Run the backup again to finish it.")
                    },
                )
            }
            state.outcome is BackupOutcome.Failed -> ResultCard(Prism.Orange, "BACKUP FAILED", "Nothing was lost", (state.outcome as BackupOutcome.Failed).message)
        }

        Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 24).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Prism.Cobalt), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Usb, contentDescription = null, tint = Prism.Paper)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("BACKUP LOCATION", style = PrismType.label, color = Prism.Muted)
                    Text(info.location?.name ?: "Not chosen yet", style = PrismType.heading, color = Prism.Paper)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Last backup: " + (info.lastBackupAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "never"),
                style = PrismType.body,
                color = Prism.Muted,
            )
        }

        if (state.running == null) {
            if (info.location != null) {
                PillButton("Back up now", onClick = { withNotifications { viewModel.backUp() } })
                GhostButton("Choose another drive or folder", onClick = choose)
            } else {
                PillButton("Choose a USB drive or folder", onClick = choose)
            }
        }

        Explainer(
            "What's copied" to "The vault exactly as it is stored: every file, thumbnail and the index, all still encrypted. Nothing is decrypted on the way, so the backup can only be opened with your passphrase or recovery key.",
            "Updates are quick" to "After the first backup, only new files are copied and shredded ones are removed from the drive.",
            "Not included" to "PIN and fingerprint unlock, which only work on this phone. Set them up again after restoring.",
        )
    }

    if (state.outcome == BackupOutcome.OtherVaultThere) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            containerColor = Prism.Surface,
            title = { Text("Replace the backup there?", style = PrismType.heading, color = Prism.Paper) },
            text = {
                Text(
                    "This folder already holds an Obscura backup of a different vault. Backing up here replaces it, and the old backup can't be restored afterwards.",
                    style = PrismType.body,
                    color = Prism.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.backUp(replaceOtherVault = true) }) { Text("Replace", color = Prism.Orange, style = PrismType.button) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Keep it", color = Prism.Paper, style = PrismType.button) }
            },
        )
    }
}

@Composable
fun RestoreScreen(onBack: () -> Unit, onRestored: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val withNotifications = rememberNotificationGate()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) withNotifications { viewModel.restore(tree) }
    }

    ScreenFrame(title = "Restore", onBack = if (state.running == null) onBack else null) {
        when {
            state.running == BackupJob.RESTORE -> ProgressCard("RESTORING", state.progress, "Keep the drive connected. Your files stay encrypted the whole time.")
            state.outcome == BackupOutcome.Restored -> {
                ResultCard(Prism.Mint, "VAULT RESTORED", "Unlock to continue", "Use your passphrase or recovery key. Then set up PIN and fingerprint unlock again in Security.")
                PillButton("Unlock", onClick = {
                    viewModel.dismiss()
                    onRestored()
                })
            }
            else -> {
                (state.outcome as? BackupOutcome.Failed)?.let { ResultCard(Prism.Orange, "RESTORE FAILED", "Nothing changed", it.message) }
                Text(
                    "Plug in the drive with your Obscura backup, then pick the drive or the \"Obscura Backup\" folder on it.",
                    style = PrismType.body,
                    color = Prism.Muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                PillButton("Choose the backup", onClick = { pickFolder.launch(null) })
                Explainer(
                    "What you need" to "The passphrase or recovery key of the vault that was backed up. The backup is useless without one of them.",
                    "What happens" to "The encrypted vault is copied back onto this phone. Nothing is decrypted until you unlock it.",
                )
            }
        }
    }
}

@Composable
private fun ScreenFrame(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) else Spacer(Modifier.height(48.dp))
        Text(title, style = PrismType.title, color = Prism.Paper, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        content()
    }
}

@Composable
private fun ProgressCard(label: String, progress: BackupProgress, note: String) {
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Sun).padding(20.dp)) {
        Row {
            Text(label, style = PrismType.label, color = Prism.Ink)
            Spacer(Modifier.weight(1f))
            if (progress.filesTotal > 0) Text("%,d / %,d".format(progress.filesDone, progress.filesTotal), style = PrismType.label, color = Prism.Ink)
        }
        Spacer(Modifier.height(10.dp))
        Text("${(progress.fraction * 100).toInt()}%", style = PrismType.stat, color = Prism.Ink)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Prism.Ink)) {
            Box(Modifier.fillMaxWidth(progress.fraction).height(12.dp).background(Prism.Paper))
        }
        Spacer(Modifier.height(10.dp))
        if (progress.bytesTotal > 0) Text("${formatBytes(progress.bytesDone)} of ${formatBytes(progress.bytesTotal)}", style = PrismType.body, color = Prism.Ink)
        if (progress.phase.isNotEmpty()) Text(progress.phase, style = PrismType.body, color = Prism.Ink)
        Spacer(Modifier.height(12.dp))
        Text(note, style = PrismType.body.copy(fontSize = PrismType.label.fontSize), color = Prism.Ink)
    }
}

@Composable
private fun ResultCard(color: Color, label: String, headline: String, detail: String) {
    Column(Modifier.fillMaxWidth().prismBlock(color).padding(20.dp)) {
        Text(label, style = PrismType.label, color = Prism.Ink)
        Spacer(Modifier.height(8.dp))
        Text(headline, style = PrismType.heading, color = Prism.Ink)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = PrismType.body, color = Prism.Ink)
    }
}

@Composable
private fun Explainer(vararg items: Pair<String, String>) {
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 24).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((title, text) in items) {
            Column {
                Text(title.uppercase(), style = PrismType.label, color = Prism.Sun)
                Spacer(Modifier.height(4.dp))
                Text(text, style = PrismType.body, color = Prism.Muted)
            }
        }
    }
}
