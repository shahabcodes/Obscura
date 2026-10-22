package com.shahaabapps.obscura.duplicates

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.gallery.MediaTile
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import java.text.DateFormat
import java.util.Date

@Composable
fun DuplicatesScreen(onBack: () -> Unit, viewModel: DuplicatesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("Duplicates", style = PrismType.title, color = Prism.Paper, modifier = Modifier.weight(1f))
            if (ui is DuplicatesUi.Results || ui is DuplicatesUi.Done) {
                TextButton(onClick = viewModel::scan) { Text("Rescan", color = Prism.Paper, style = PrismType.body) }
            }
        }
        when (val state = ui) {
            is DuplicatesUi.Scanning -> Scanning(state)
            is DuplicatesUi.Results -> Results(state, viewModel, onShred = { confirm = true })
            is DuplicatesUi.Shredding -> Scanning(DuplicatesUi.Scanning(state.done, state.total), title = "Shredding copies…")
            is DuplicatesUi.Done -> Done(state, onBack)
            is DuplicatesUi.Failed -> Text(state.message, style = PrismType.body, color = Prism.Orange, modifier = Modifier.padding(24.dp))
        }
    }

    val results = ui as? DuplicatesUi.Results
    if (confirm && results != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Prism.Surface,
            title = { Text("Shred ${results.markedFiles.size} copies?", style = PrismType.heading, color = Prism.Paper) },
            text = { Text("Frees ${formatBytes(results.markedBytes)}. Their keys are destroyed, so they can't be recovered. The copy marked BEST in each set is kept unless you marked it too.", style = PrismType.body, color = Prism.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.shredMarked()
                }) { Text("Shred", color = Prism.Orange, style = PrismType.button) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = Prism.Paper, style = PrismType.button) } },
        )
    }
}

@Composable
private fun Scanning(state: DuplicatesUi.Scanning, title: String = "Comparing photos on this phone…") {
    val sweep = rememberInfiniteTransition(label = "radar")
    val angle by sweep.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val pct = if (state.total == 0) 0 else state.done * 100 / state.total
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(240.dp).semantics { contentDescription = "Scanning, $pct percent" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Prism.Surface, r)
                listOf(0.75f, 0.5f, 0.25f).forEach { drawCircle(Prism.Line, r * it, style = Stroke(1.5.dp.toPx())) }
            }
            Canvas(Modifier.fillMaxSize().rotate(angle)) {
                val r = size.minDimension / 2
                drawArc(Prism.Orange.copy(alpha = 0.4f), -90f, 50f, true, Offset.Zero, Size(r * 2, r * 2))
                drawLine(Prism.Orange, Offset(r, r), Offset(r, 0f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            Box(Modifier.size(18.dp).clip(CircleShape).background(Prism.Paper))
        }
        Spacer(Modifier.height(24.dp))
        Text("$pct%", style = PrismType.stat, color = Prism.Paper)
        Text(if (state.total > 0) "%,d / %,d PHOTOS".format(state.done, state.total) else "READING THE INDEX", style = PrismType.label, color = Prism.Muted)
        Spacer(Modifier.height(16.dp))
        Text(title, style = PrismType.body, color = Prism.Muted)
    }
}

@Composable
private fun Results(state: DuplicatesUi.Results, viewModel: DuplicatesViewModel, onShred: () -> Unit) {
    if (state.groups.isEmpty()) {
        Column(Modifier.padding(24.dp)) {
            Text("No duplicates found.", style = PrismType.heading, color = Prism.Paper)
            Spacer(Modifier.height(8.dp))
            Text("Every file in your vault is unique.", style = PrismType.body, color = Prism.Muted)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().prismBlock(Prism.Orange, 26).padding(18.dp)) {
                Text("YOU CAN FREE", style = PrismType.label, color = Prism.Ink)
                Text(formatBytes(state.markedBytes), style = PrismType.stat, color = Prism.Ink)
                Text("${state.markedFiles.size} marked across ${state.groups.size} sets · best copy kept", style = PrismType.label, color = Prism.Ink)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<DuplicateKind?>(null) + DuplicateKind.entries).forEach { kind ->
                    val on = state.filter == kind
                    val count = if (kind == null) state.groups.size else state.groups.count { it.kind == kind }
                    Text(
                        "${kind?.label ?: "All"} · $count",
                        style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                        color = if (on) Prism.Ink else Prism.Paper,
                        modifier = Modifier.clip(CircleShape).background(if (on) Prism.Paper else Prism.Surface)
                            .selectable(selected = on, role = Role.Tab) { viewModel.filter(kind) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.visible, key = { g -> g.files.joinToString { it.id } }) { group -> GroupCard(group, state.marked, viewModel) }
        }
        Box(Modifier.padding(16.dp)) {
            PillButton(
                "Shred ${state.markedFiles.size} · ${formatBytes(state.markedBytes)}",
                onClick = onShred,
                container = if (state.markedFiles.isEmpty()) Prism.SurfaceHigh else Prism.Sun,
                content = if (state.markedFiles.isEmpty()) Prism.Muted else Prism.Ink,
            )
        }
    }
}

@Composable
private fun GroupCard(group: DuplicateGroup, marked: Set<String>, viewModel: DuplicatesViewModel) {
    val kindColor = when (group.kind) {
        DuplicateKind.EXACT -> Prism.Orange
        DuplicateKind.LOOK_ALIKE -> Prism.Cobalt
        DuplicateKind.BURST -> Prism.Pink
    }
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 22).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.kind.label.uppercase(), style = PrismType.label, color = Prism.Ink, modifier = Modifier.clip(CircleShape).background(kindColor).padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.width(8.dp))
            Text(group.best.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 1, modifier = Modifier.weight(1f))
            Text("${group.files.size} COPIES", style = PrismType.label, color = Prism.Muted)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            group.files.forEach { file -> CopyTile(file, isBest = file.id == group.best.id, isMarked = file.id in marked, viewModel) }
        }
    }
}

@Composable
private fun CopyTile(file: VaultFile, isBest: Boolean, isMarked: Boolean, viewModel: DuplicatesViewModel) {
    val ring = when {
        isMarked -> Prism.Orange
        isBest -> Prism.Mint
        else -> Prism.Line
    }
    Column(
        Modifier.width(96.dp)
            .toggleable(value = isMarked, role = Role.Checkbox) { viewModel.toggle(file) }
            .semantics { contentDescription = "${file.name}, ${formatBytes(file.sizeBytes)}${if (isBest) ", best copy" else ""}${if (isMarked) ", marked to shred" else ", kept"}" },
    ) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).border(2.5.dp, ring, RoundedCornerShape(14.dp))) {
            if (file.kind == FileKind.PHOTO || file.kind == FileKind.VIDEO) {
                MediaTile(file, viewModel::thumbnail, badge = null, radius = 14, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Prism.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Text(file.name.substringAfterLast('.', "FILE").uppercase().take(4), style = PrismType.label, color = Prism.Paper)
                }
            }
            if (isBest) Text("BEST", style = PrismType.label, color = Prism.Ink, modifier = Modifier.padding(4.dp).clip(CircleShape).background(Prism.Mint).padding(horizontal = 6.dp, vertical = 2.dp))
            if (isMarked) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Prism.Orange), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, null, tint = Prism.Ink, modifier = Modifier.size(14.dp))
            }
        }
        Text(formatBytes(file.sizeBytes), style = PrismType.label, color = Prism.Muted)
        Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.takenAt)), style = PrismType.label, color = Prism.Muted)
    }
}

@Composable
private fun Done(state: DuplicatesUi.Done, onBack: () -> Unit) {
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(700)) }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(170.dp)) {
            drawCircle(Prism.Mint, size.minDimension / 2 * (0.6f + 0.4f * draw.value))
            val w = size.width
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.3f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.66f)
                lineTo(w * 0.72f, w * 0.38f)
            }
            val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
            val partial = androidx.compose.ui.graphics.Path()
            measure.getSegment(0f, measure.length * draw.value, partial, true)
            drawPath(partial, Prism.Ink, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(22.dp))
        Text("${formatBytes(state.bytes)} freed", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(6.dp))
        Text("${state.count} duplicate copies shredded. Their keys are destroyed, so they can't come back.", style = PrismType.body, color = Prism.Muted)
        Spacer(Modifier.height(24.dp))
        GhostButton("Back to gallery", onClick = onBack)
    }
}
