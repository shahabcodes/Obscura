package com.shahaabapps.obscura.ui.importer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.importer.ImportProgress
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.setup.SealingIndicator
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

@Composable
fun ImportScreen(viewModel: ImportViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Ask once for notifications so the progress notification can show; import works either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) withNotificationPermission { viewModel.importDocuments(uris) } else viewModel.pickCancelled()
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) withNotificationPermission { viewModel.importDocuments(uris) } else viewModel.pickCancelled()
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) withNotificationPermission { viewModel.importFolder(tree) } else viewModel.pickCancelled()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bring files in", style = PrismType.title, color = Prism.Paper, modifier = Modifier.padding(horizontal = 4.dp))

        when {
            progress.running -> ProgressCard(progress, onCancel = viewModel::cancel)
            progress.finished -> SummaryCard(
                progress = progress,
                deletable = viewModel.deletableOriginals,
                deleting = state.deleting,
                onDelete = { confirmDelete = true },
                onDone = viewModel::reset,
            )
            state.scanning -> SealingIndicator("Looking for files…")
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceTile("Photos & videos", "FROM YOUR GALLERY", Icons.Outlined.PhotoLibrary, Prism.Cobalt, Prism.Paper, Modifier.weight(1f)) {
                        viewModel.beginPicking()
                        pickMedia.launch(arrayOf("image/*", "video/*"))
                    }
                    SourceTile("Any files", "DOCUMENTS, AUDIO…", Icons.Outlined.Description, Prism.Pink, Prism.Ink, Modifier.weight(1f)) {
                        viewModel.beginPicking()
                        pickFiles.launch(arrayOf("*/*"))
                    }
                }
                SourceTile("A whole folder or USB drive", "EVERYTHING INSIDE, INCLUDING SUBFOLDERS", Icons.Outlined.Usb, Prism.Mint, Prism.Ink, Modifier.fillMaxWidth()) {
                    viewModel.beginPicking()
                    pickFolder.launch(null)
                }
            }
        }

        state.message?.let {
            Text(it, style = PrismType.body, color = Prism.Sun, modifier = Modifier.padding(horizontal = 4.dp))
        }

        Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 24).padding(horizontal = 16.dp, vertical = 4.dp)) {
            OptionRow(
                title = "Skip duplicates",
                subtitle = "Files already in the vault are not sealed twice",
                checked = state.options.skipDuplicates,
                enabled = !progress.running,
                onChange = viewModel::setSkipDuplicates,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Prism.Line))
            OptionRow(
                title = "Remove location from photos",
                subtitle = "Strips GPS from JPEG photos as they are sealed",
                checked = state.options.stripLocation,
                enabled = !progress.running,
                onChange = viewModel::setStripLocation,
            )
        }

        Text(
            "Files are encrypted on this phone while they're read. Nothing is uploaded anywhere.",
            style = PrismType.body,
            color = Prism.Muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Prism.Surface,
            title = { Text("Remove originals?", style = PrismType.heading, color = Prism.Paper) },
            text = {
                Text(
                    "This deletes the %,d files you just sealed from their original location, so they no longer appear in your gallery or file manager. The sealed copies stay in the vault.".format(viewModel.deletableOriginals),
                    style = PrismType.body,
                    color = Prism.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteOriginals()
                }) { Text("Remove", color = Prism.Orange, style = PrismType.button) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep them", color = Prism.Paper, style = PrismType.button) }
            },
        )
    }
}

@Composable
private fun SourceTile(
    title: String,
    tag: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier.height(132.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = container, contentColor = content) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Column {
                Text(tag, style = PrismType.label)
                Spacer(Modifier.height(4.dp))
                Text(title, style = PrismType.heading)
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: ImportProgress, onCancel: () -> Unit) {
    val percent = (progress.fraction * 100).toInt()
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Sun).padding(20.dp)) {
        Row {
            Text("SEALING", style = PrismType.label, color = Prism.Ink)
            Spacer(Modifier.weight(1f))
            Text("%,d / %,d".format(progress.processed, progress.total), style = PrismType.label, color = Prism.Ink)
        }
        Spacer(Modifier.height(10.dp))
        Text("$percent%", style = PrismType.stat, color = Prism.Ink)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Prism.Ink)) {
            Box(Modifier.fillMaxWidth(progress.fraction).height(12.dp).background(Prism.Paper))
        }
        Spacer(Modifier.height(10.dp))
        if (progress.bytesTotal > 0) {
            Text("${formatBytes(progress.bytesDone)} of ${formatBytes(progress.bytesTotal)}", style = PrismType.body, color = Prism.Ink)
        }
        progress.current?.let { Text("Encrypting $it", style = PrismType.body, color = Prism.Ink, maxLines = 1) }
        Spacer(Modifier.height(14.dp))
        Text(
            "You can leave the app. The vault stays open until this finishes, then locks.",
            style = PrismType.body.copy(fontSize = PrismType.label.fontSize),
            color = Prism.Ink,
        )
        Spacer(Modifier.height(14.dp))
        PillButton("Cancel import", onClick = onCancel, container = Prism.Ink, content = Prism.Sun)
    }
}

@Composable
private fun SummaryCard(
    progress: ImportProgress,
    deletable: Int,
    deleting: Boolean,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Mint).padding(20.dp)) {
        Text(if (progress.cancelled) "IMPORT CANCELLED" else "ALL SEALED", style = PrismType.label, color = Prism.Ink)
        Spacer(Modifier.height(10.dp))
        Text("%,d".format(progress.sealed), style = PrismType.stat, color = Prism.Ink)
        Text(if (progress.sealed == 1) "file sealed" else "files sealed", style = PrismType.heading, color = Prism.Ink)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (progress.duplicates > 0) Chip("${progress.duplicates} duplicate${if (progress.duplicates == 1) "" else "s"} skipped")
            if (progress.locationRemoved > 0) Chip("Location removed from ${progress.locationRemoved}")
            if (progress.failed > 0) Chip("${progress.failed} failed")
        }
        progress.error?.takeIf { progress.failed > 0 }?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last problem: $it", style = PrismType.body.copy(fontSize = PrismType.label.fontSize), color = Prism.Ink)
        }
    }
    if (deletable > 0 && progress.originalsDeleted == 0) {
        Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 24).padding(18.dp)) {
            Text("Remove the originals?", style = PrismType.heading, color = Prism.Paper)
            Spacer(Modifier.height(6.dp))
            Text(
                "The %,d files are now sealed, but readable copies are still on your phone.".format(deletable),
                style = PrismType.body,
                color = Prism.Muted,
            )
            Spacer(Modifier.height(14.dp))
            if (deleting) SealingIndicator("Removing originals…")
            else PillButton("Remove %,d originals".format(deletable), onClick = onDelete, container = Prism.Orange)
        }
    }
    GhostButton("Import more", onClick = onDone)
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = PrismType.label,
        color = Prism.Mint,
        modifier = Modifier.clip(CircleShape).background(Prism.Ink).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
            Text(subtitle, style = PrismType.body.copy(fontSize = PrismType.label.fontSize), color = Prism.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Prism.Ink,
                checkedTrackColor = Prism.Sun,
                uncheckedThumbColor = Prism.Muted,
                uncheckedTrackColor = Prism.SurfaceHigh,
            ),
        )
    }
}
