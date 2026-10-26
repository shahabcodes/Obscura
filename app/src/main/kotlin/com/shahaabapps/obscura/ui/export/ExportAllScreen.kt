package com.shahaabapps.obscura.ui.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.export.ExportProgress
import com.shahaabapps.obscura.ui.backup.rememberNotificationGate
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/**
 * Saving a decrypted copy of the whole vault into a folder of the user's choosing.
 *
 * This is the one place where everything leaves the vault's protection at once, so it says so
 * plainly before it starts, and says what it is doing while it runs: a vault of a few hundred
 * gigabytes takes hours, and the user needs to know they can leave the app, that it can be
 * stopped, and that running it again picks up where it left off.
 */
@Composable
fun ExportAllScreen(onBack: () -> Unit = {}, viewModel: ExportAllViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    // An export runs for hours in the background; without a notification there is nothing to
    // show it is going and no way to stop it.
    val withNotifications = rememberNotificationGate()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) withNotifications { viewModel.startInto(tree) } else viewModel.endPicking()
    }

    ScreenFrame(title = "Export everything", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                progress.running -> RunningCard(progress, onCancel = viewModel::cancel)
                progress.finished -> FinishedCard(
                    progress = progress,
                    canContinue = state.canContinue,
                    onContinue = { withNotifications { viewModel.continueExport() } },
                    onDone = viewModel::clear,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.prismBlock(Prism.Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("A readable copy of everything", style = PrismType.heading, color = Prism.Paper)
                        Text(
                            "%,d %s · %s".format(
                                state.count,
                                if (state.count == 1) "file" else "files",
                                formatBytes(state.bytes),
                            ),
                            style = PrismType.label,
                            color = Prism.Muted,
                        )
                        Text(
                            "The folder you pick needs about %s free.".format(formatBytes(state.bytes)),
                            style = PrismType.body,
                            color = Prism.Muted,
                        )
                        Text(
                            "Copies are written out of the vault as ordinary files, into a folder called " +
                                "\"Obscura export\". They are no longer encrypted: anyone who has that folder, " +
                                "drive or phone can open them.",
                            style = PrismType.body,
                            color = Prism.Orange,
                        )
                        Text(
                            "A list called \"Obscura index.csv\" is written beside them, because a copy cannot " +
                                "carry everything on its own. It names every file, with the date it was taken, " +
                                "its album and whether it was a favourite — in plain text, like the copies.",
                            style = PrismType.body,
                            color = Prism.Orange,
                        )
                        Text(
                            "The vault stays open until the export finishes, so it will not lock itself in the " +
                                "meantime. You can leave the app while it runs.",
                            style = PrismType.body,
                            color = Prism.Muted,
                        )
                        if (state.bytes > 50L * 1024 * 1024 * 1024) {
                            Text(
                                "This much will take hours. If Android stops it before it is done, open this " +
                                    "screen again and carry on — anything already copied is left alone.",
                                style = PrismType.body,
                                color = Prism.Muted,
                            )
                        }
                    }
                    PillButton(
                        "Choose a folder and start",
                        onClick = {
                            viewModel.beginPicking()
                            pickFolder.launch(null)
                        },
                    )
                    if (state.canContinue) {
                        GhostButton(
                            "Carry on into the last folder",
                            onClick = { withNotifications { viewModel.continueExport() } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningCard(progress: ExportProgress, onCancel: () -> Unit) {
    Column(Modifier.prismBlock(Prism.Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("EXPORTING", style = PrismType.label, color = Prism.Muted)
        Text(
            if (progress.total > 0) "%,d of %,d files".format(progress.done, progress.total) else "Counting…",
            style = PrismType.heading,
            color = Prism.Paper,
        )
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Prism.SurfaceHigh)) {
            Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(Prism.Mint))
        }
        Text(
            "%s of %s · %d%%".format(
                formatBytes(progress.bytesDone),
                formatBytes(progress.bytesTotal),
                (progress.fraction * 100).toInt(),
            ),
            style = PrismType.label,
            color = Prism.Muted,
        )
        if (progress.skipped > 0) {
            Text("%,d already there".format(progress.skipped), style = PrismType.label, color = Prism.Muted)
        }
        Text(
            "You can leave the app. The vault stays open until this finishes.",
            style = PrismType.body,
            color = Prism.Muted,
        )
        GhostButton("Stop", onClick = onCancel)
    }
}

@Composable
private fun FinishedCard(
    progress: ExportProgress,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.prismBlock(Prism.Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val stopped = progress.cancelled || progress.paused
        Text(
            when {
                progress.paused -> "PAUSED"
                progress.cancelled -> "STOPPED"
                progress.failed > 0 -> "FINISHED, WITH PROBLEMS"
                else -> "DONE"
            },
            style = PrismType.label,
            color = if (progress.failed > 0 || stopped) Prism.Orange else Prism.Mint,
        )
        Text(
            "%,d copied%s".format(
                progress.exported,
                if (progress.skipped > 0) ", %,d already there".format(progress.skipped) else "",
            ),
            style = PrismType.heading,
            color = Prism.Paper,
        )
        if (progress.failed > 0) {
            Text("%,d could not be copied".format(progress.failed), style = PrismType.body, color = Prism.Orange)
        }
        progress.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }
        if (progress.shortBy > 0) {
            Text(
                "About %s more is needed. Free some space, or choose a drive with more room — what has already been copied will be left alone.".format(formatBytes(progress.shortBy)),
                style = PrismType.body,
                color = Prism.Muted,
            )
        }
        if (progress.paused) {
            Text(
                "Android only lets a background job run for so long. Everything copied so far is in place; " +
                    "carrying on skips it.",
                style = PrismType.body,
                color = Prism.Muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stopped && canContinue) PillButton("Carry on", onClick = onContinue)
            GhostButton(if (stopped && canContinue) "Not now" else "Done", onClick = onDone)
        }
    }
}

/** The same plain frame the backup screens use: a way back, a title, then the content. */
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
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        } else {
            Spacer(Modifier.height(48.dp))
        }
        Text(title, style = PrismType.title, color = Prism.Paper, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        content()
    }
}
