package com.shahaabapps.obscura.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.DecryptingIndicator
import com.shahaabapps.obscura.ui.components.EmptyState
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.FileKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onReel: (String) -> Unit,
    onImport: () -> Unit,
    onDuplicates: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val selecting by viewModel.selecting.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<BulkAction?>(null) }
    var pickingAlbum by remember { mutableStateOf(false) }
    val exportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        viewModel.exportTo(tree, viewModel.selectedFiles())
    }
    BackHandler(enabled = selecting) { viewModel.endSelecting() }
    val ids = remember(state.flat) { state.flat.map { it.id } }

    // Floating month label while scrolling, standing in for sticky headers.
    val currentMonth by remember(state.entries, settings.groupByMonth) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            if (!settings.groupByMonth || first == 0) null
            else state.entries.take(first + 1).lastOrNull { it is GalleryEntry.Header } as? GalleryEntry.Header
        }
    }

    Column(Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selecting) {
                SelectionHeader(
                    count = selection.size,
                    allSelected = state.flat.isNotEmpty() && selection.containsAll(state.flat.map { it.id }),
                    onClose = viewModel::endSelecting,
                    onSelectAll = viewModel::selectAll,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Gallery", style = PrismType.title, color = Prism.Paper)
                        Text("%,d ITEMS · SEALED".format(state.totalMedia), style = PrismType.label, color = Prism.Muted)
                    }
                    RoundIconButton(Icons.Outlined.CheckCircle, "Select", viewModel::startSelecting)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { sheetOpen = true },
                        shape = CircleShape,
                        color = Prism.Paper,
                        contentColor = Prism.Ink,
                        modifier = Modifier.height(44.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${settings.sortBy.label} ${if (settings.descending) "↓" else "↑"}", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
                        }
                    }
                }
            }
            if (state.flat.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutTile("Reel mode", "SWIPE THROUGH", Prism.Pink, Modifier.weight(1f), { onReel(state.flat.first().id) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Prism.Pink, modifier = Modifier.size(18.dp))
                    }
                    ShortcutTile("Duplicates", "FIND & FREE SPACE", Prism.Orange, Modifier.weight(1f), onDuplicates) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Prism.Orange, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GalleryFilter.entries.forEach { filter ->
                    val on = settings.filter == filter
                    Text(
                        filter.label,
                        style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                        color = if (on) Prism.Ink else Prism.Paper.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) Prism.Paper else Prism.Surface)
                            .selectable(selected = on, role = Role.Tab) { viewModel.update { it.copy(filter = filter) } }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.loading) {
                DecryptingIndicator(Modifier.fillMaxSize())
            } else if (state.flat.isEmpty()) {
                EmptyGallery(if (state.totalMedia == 0) "No photos or videos yet." else "Nothing matches this filter.", onImport, showImport = state.totalMedia == 0)
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    modifier = Modifier.fillMaxSize()
                        .pinchToChangeColumns(settings.columns) { n -> viewModel.update { it.copy(columns = n) } }
                        .dragToSelect(gridState, selecting, ids, selection, viewModel::setSelection),
                    columns = GridCells.Fixed(settings.columns),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = state.entries,
                        key = { it.key },
                        span = { if (it is GalleryEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        contentType = { if (it is GalleryEntry.Header) 0 else 1 },
                    ) { entry ->
                        when (entry) {
                            is GalleryEntry.Header -> MonthHeader(entry)
                            is GalleryEntry.Item -> MediaTile(
                                file = entry.file,
                                load = viewModel::thumbnail,
                                badge = when (settings.sortBy) {
                                    SortBy.SIZE -> formatBytes(entry.file.sizeBytes)
                                    SortBy.TYPE -> GalleryLogic.typeLabel(entry.file)
                                    SortBy.DATE -> if (settings.columns == 3) dayOf(entry.file.takenAt) else null
                                },
                                radius = if (settings.columns >= 5) 4 else 8,
                                selected = if (selecting) entry.file.id in selection else null,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .animateItem()
                                    .semantics {
                                        contentDescription = "${GalleryLogic.typeLabel(entry.file)}, ${entry.file.name}" + if (entry.file.favorite) ", favourite" else ""
                                        if (selecting) this.selected = entry.file.id in selection
                                    }
                                    .clickable(
                                        role = if (selecting) Role.Checkbox else Role.Button,
                                        onClickLabel = when {
                                            selecting -> if (entry.file.id in selection) "Deselect" else "Select"
                                            entry.file.kind == FileKind.VIDEO -> "Play"
                                            else -> "Open"
                                        },
                                    ) {
                                        when {
                                            selecting -> viewModel.toggle(entry.file.id)
                                            entry.file.kind == FileKind.VIDEO -> onPlay(entry.file.id)
                                            else -> onOpen(entry.file.id)
                                        }
                                    },
                            )
                        }
                    }
                }
                MonthPill(currentMonth, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            ExportStatus(export, viewModel::cancelExport, viewModel::dismissExport, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
        if (selecting) {
            SelectionActions(
                enabled = selection.isNotEmpty(),
                allFavourite = viewModel.selectedFiles().let { it.isNotEmpty() && it.all { f -> f.favorite } },
                onShare = { confirm = BulkAction.SHARE },
                onExport = { confirm = BulkAction.EXPORT },
                onAlbum = { pickingAlbum = true },
                onFavourite = viewModel::favoriteSelected,
                onShred = { confirm = BulkAction.SHRED },
            )
        }
    }

    confirm?.let { action ->
        val count = selection.size
        BulkConfirm(
            action = action,
            count = count,
            onConfirm = {
                confirm = null
                when (action) {
                    BulkAction.SHARE -> context.startActivity(viewModel.share(viewModel.selectedFiles()))
                    BulkAction.EXPORT -> {
                        viewModel.beginPicking()
                        exportFolder.launch(null)
                    }
                    BulkAction.SHRED -> viewModel.shredSelected()
                }
            },
            onDismiss = { confirm = null },
        )
    }

    if (pickingAlbum) {
        ModalBottomSheet(
            onDismissRequest = { pickingAlbum = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Prism.Surface,
        ) {
            val files = viewModel.selectedFiles()
            com.shahaabapps.obscura.ui.organize.AlbumPicker(
                fileIds = files.map { it.id },
                currentAlbumId = files.map { it.albumId }.distinct().singleOrNull(),
                onDone = {
                    pickingAlbum = false
                    viewModel.endSelecting()
                },
            )
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Prism.Surface,
        ) {
            SortSheet(settings, viewModel::update, onDone = { sheetOpen = false }, visible = state.flat.size)
        }
    }
}

@Composable
private fun ShortcutTile(title: String, tag: String, color: Color, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = color, contentColor = Prism.Ink, modifier = modifier.height(56.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Prism.Ink), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
                Text(tag, style = PrismType.label.copy(fontSize = PrismType.label.fontSize * 0.85f))
            }
        }
    }
}

/** Floating "October 2026" label shown while scrolled past a month's header. */
@Composable
private fun MonthPill(header: GalleryEntry.Header?, modifier: Modifier) {
    AnimatedVisibility(header != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        header?.let {
            Text(
                "${it.month} ${it.year}",
                style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                color = Prism.Ink,
                modifier = Modifier.clip(CircleShape).background(Prism.Sun).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MonthHeader(header: GalleryEntry.Header) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(header.month, style = PrismType.heading.copy(fontSize = PrismType.title.fontSize * 0.7f), color = Prism.Paper)
        Spacer(Modifier.width(8.dp))
        Text(header.year, style = PrismType.label, color = Prism.Muted, modifier = Modifier.padding(bottom = 3.dp))
        Spacer(Modifier.weight(1f))
        Text("%,d · %s".format(header.count, formatBytes(header.bytes)), style = PrismType.label, color = Prism.Muted, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun EmptyGallery(text: String, onImport: () -> Unit, showImport: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        EmptyState(
            title = text,
            action = if (showImport) ({ PillButton("Import photos & videos", onClick = onImport) }) else null,
        )
    }
}

@Composable
private fun SortSheet(settings: GallerySettings, update: ((GallerySettings) -> GallerySettings) -> Unit, onDone: () -> Unit, visible: Int) {
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sort & group", style = PrismType.heading, color = Prism.Paper, modifier = Modifier.weight(1f))
            Surface(onClick = { update { it.copy(descending = !it.descending) } }, shape = CircleShape, color = Color.Transparent, contentColor = Prism.Paper,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Prism.Line)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(directionLabel(settings), style = PrismType.body)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortBy.entries.forEach { sort ->
                val on = settings.sortBy == sort
                Column(
                    Modifier
                        .weight(1f)
                        .height(92.dp)
                        .prismBlock(if (on) Prism.Sun else Prism.Ink, 22)
                        .selectable(selected = on, role = Role.RadioButton) { update { it.copy(sortBy = sort, descending = true) } }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(when (sort) { SortBy.DATE -> "◷"; SortBy.SIZE -> "⇅"; SortBy.TYPE -> "◧" }, style = PrismType.heading, color = if (on) Prism.Ink else Prism.Paper)
                    Column {
                        Text(sort.label, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = if (on) Prism.Ink else Prism.Paper)
                        Text(when (sort) { SortBy.DATE -> "When taken"; SortBy.SIZE -> "Biggest files"; SortBy.TYPE -> "Photo, video…" }, style = PrismType.label, color = if (on) Prism.Ink else Prism.Muted)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().prismBlock(Prism.Ink, 20)
                .toggleable(value = settings.groupByMonth, role = Role.Switch) { v -> update { it.copy(groupByMonth = v) } }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Group by month", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
                Text("Month headers with counts and sizes", style = PrismType.label, color = Prism.Muted)
            }
            Switch(
                checked = settings.groupByMonth,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = Prism.Ink, checkedTrackColor = Prism.Sun, uncheckedTrackColor = Prism.SurfaceHigh),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Grid density", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, modifier = Modifier.weight(1f))
            Row(Modifier.clip(CircleShape).background(Prism.Ink).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColumnRange.choices.forEach { n ->
                    val on = settings.columns == n
                    Box(
                        Modifier.size(width = 46.dp, height = 38.dp).clip(CircleShape).background(if (on) Prism.Paper else Color.Transparent)
                            .selectable(selected = on, role = Role.RadioButton) { update { it.copy(columns = n) } },
                        contentAlignment = Alignment.Center,
                    ) { Text("$n", style = PrismType.label, color = if (on) Prism.Ink else Prism.Paper) }
                }
            }
        }
        PillButton("Show %,d items".format(visible), onClick = onDone)
    }
}

private fun directionLabel(settings: GallerySettings) = when (settings.sortBy) {
    SortBy.DATE -> if (settings.descending) "Newest first" else "Oldest first"
    SortBy.SIZE -> if (settings.descending) "Largest first" else "Smallest first"
    SortBy.TYPE -> if (settings.descending) "A → Z" else "Z → A"
}

private val dayFormat = SimpleDateFormat("d", Locale.getDefault())

private fun dayOf(millis: Long): String = dayFormat.format(Date(millis))

enum class BulkAction { SHARE, EXPORT, SHRED }

@Composable
private fun SelectionHeader(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Outlined.Close, "Stop selecting", onClose)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (count == 0) "Select items" else "%,d selected".format(count), style = PrismType.title, color = Prism.Paper)
            Text("TAP, OR DRAG ACROSS TILES", style = PrismType.label, color = Prism.Muted)
        }
        Surface(onClick = onSelectAll, shape = CircleShape, color = Prism.Paper, contentColor = Prism.Ink, modifier = Modifier.height(44.dp)) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(if (allSelected) "Deselect all" else "Select all", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
            }
        }
    }
}

@Composable
private fun SelectionActions(
    enabled: Boolean,
    allFavourite: Boolean,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onAlbum: () -> Unit,
    onFavourite: () -> Unit,
    onShred: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).prismBlock(Prism.Paper, 30).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionButton(Icons.Outlined.Share, "Share", enabled, onShare)
        ActionButton(Icons.Outlined.Download, "Save copy", enabled, onExport)
        ActionButton(Icons.Outlined.CreateNewFolder, "Album", enabled, onAlbum)
        ActionButton(if (allFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (allFavourite) "Unfavourite" else "Favourite", enabled, onFavourite)
        ActionButton(Icons.Outlined.DeleteForever, "Shred", enabled, onShred, tint = Prism.Orange)
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, tint: Color = Prism.Ink) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val color = if (enabled) (if (tint == Prism.Orange) Color(0xFFD9541A) else tint) else Prism.Muted
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = PrismType.label.copy(fontSize = PrismType.label.fontSize * 0.9f, letterSpacing = PrismType.body.letterSpacing), color = color)
    }
}

@Composable
internal fun BulkConfirm(action: BulkAction, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val files = if (count == 1) "1 file" else "%,d files".format(count)
    val (title, text, button) = when (action) {
        BulkAction.SHARE -> Triple(
            "Share $files?",
            "The app you pick gets a readable copy, which Obscura can't protect or take back. The links stop working when Obscura locks.",
            "Share",
        )
        BulkAction.EXPORT -> Triple(
            "Save $files outside the vault?",
            "Decrypted copies are saved where you choose. They aren't protected: other apps, cloud backup and anyone using your phone can see them. The originals stay sealed.",
            "Choose folder",
        )
        BulkAction.SHRED -> Triple(
            "Shred $files?",
            "Their keys are destroyed, so they can't be recovered. Copies on a backup drive are removed at the next backup.",
            "Shred",
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Prism.Surface,
        title = { Text(title, style = PrismType.heading, color = Prism.Paper) },
        text = { Text(text, style = PrismType.body, color = Prism.Muted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(button, color = if (action == BulkAction.SHARE) Prism.Sun else Prism.Orange, style = PrismType.button)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = Prism.Paper, style = PrismType.button) }
        },
    )
}

/** Progress of a running export, then a short summary. */
@Composable
fun ExportStatus(progress: com.shahaabapps.obscura.export.ExportProgress, onCancel: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!progress.running && !progress.finished) return
    Row(
        modifier.fillMaxWidth().prismBlock(if (progress.running) Prism.Sun else Prism.Mint, 24).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    progress.running -> "SAVING COPIES · ${(progress.fraction * 100).toInt()}%"
                    progress.cancelled -> "EXPORT STOPPED"
                    else -> "COPIES SAVED"
                },
                style = PrismType.label,
                color = Prism.Ink,
            )
            Text(
                if (progress.running) "%,d of %,d · %s of %s".format(progress.done, progress.total, formatBytes(progress.bytesDone), formatBytes(progress.bytesTotal))
                else "%,d saved".format(progress.exported) + if (progress.failed > 0) ", %,d failed".format(progress.failed) else "",
                style = PrismType.body,
                color = Prism.Ink,
            )
        }
        androidx.compose.material3.TextButton(onClick = if (progress.running) onCancel else onDismiss) {
            Text(if (progress.running) "Cancel" else "Done", style = PrismType.button, color = Prism.Ink)
        }
    }
}
