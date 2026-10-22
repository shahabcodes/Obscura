package com.shahaabapps.obscura.ui.gallery

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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onReel: (String) -> Unit,
    onImport: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Gallery", style = PrismType.title, color = Prism.Paper)
                    Text("%,d ITEMS · SEALED".format(state.totalMedia), style = PrismType.label, color = Prism.Muted)
                }
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
            if (state.flat.isNotEmpty()) {
                Surface(
                    onClick = { onReel(state.flat.first().id) },
                    shape = RoundedCornerShape(20.dp),
                    color = Prism.Pink,
                    contentColor = Prism.Ink,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Prism.Ink), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Prism.Pink, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Reel mode", style = PrismType.heading)
                            Text("SWIPE THROUGH FULL SCREEN", style = PrismType.label)
                        }
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
            if (!state.loading && state.flat.isEmpty()) {
                EmptyGallery(if (state.totalMedia == 0) "No photos or videos yet." else "Nothing matches this filter.", onImport, showImport = state.totalMedia == 0)
            } else {
                LazyVerticalGrid(
                    state = gridState,
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
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .animateItem()
                                    .semantics { contentDescription = "${GalleryLogic.typeLabel(entry.file)}, ${entry.file.name}" + if (entry.file.favorite) ", favourite" else "" }
                                    .clickable(role = Role.Button, onClickLabel = "Open") { onOpen(entry.file.id) },
                            )
                        }
                    }
                }
                MonthPill(currentMonth, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
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
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = PrismType.heading, color = Prism.Paper)
        if (showImport) {
            Spacer(Modifier.height(18.dp))
            PillButton("Import photos & videos", onClick = onImport)
        }
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
                listOf(3, 4, 5).forEach { n ->
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
