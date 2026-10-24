package com.shahaabapps.obscura.ui.organize

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.EmptyState
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.gallery.GalleryLogic
import com.shahaabapps.obscura.ui.gallery.MediaTile
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.AlbumSummary
import com.shahaabapps.obscura.vault.VaultFile

@Composable
fun AlbumsScreen(onOpenAlbum: (String) -> Unit, viewModel: AlbumsViewModel = hiltViewModel()) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Albums", style = PrismType.title, color = Prism.Paper, modifier = Modifier.weight(1f))
                Surface(onClick = { creating = !creating }, shape = CircleShape, color = Prism.Sun, contentColor = Prism.Ink, modifier = Modifier.height(44.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
                    }
                }
            }
        }
        if (creating) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AlbumEditor(initialName = "", initialColor = AlbumsViewModel.COLORS[3], action = "Create") { name, color ->
                    viewModel.create(name, color)
                    creating = false
                }
            }
        }
        val list = albums
        if (list != null && list.isEmpty() && !creating) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("No albums yet.", Modifier.padding(top = 12.dp), text = "Create one, then add photos and videos from the viewer.")
            }
        }
        items(list.orEmpty(), key = { it.id }) { album ->
            AlbumCard(album, viewModel) { onOpenAlbum(album.id) }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumSummary, viewModel: AlbumsViewModel, onClick: () -> Unit) {
    val cover by produceState<android.graphics.Bitmap?>(null, album.coverId) {
        value = album.coverId?.let { viewModel.file(it) }?.let { viewModel.thumbnail(it) }
    }
    val color = Color(album.color)
    Column(
        Modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "Album ${album.name}, ${album.count} items" },
    ) {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(color.copy(alpha = 0.4f)))
            Box(Modifier.padding(start = 7.dp, end = 7.dp, top = 8.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(color.copy(alpha = 0.7f)))
            Box(Modifier.padding(top = 16.dp).fillMaxSize().clip(RoundedCornerShape(20.dp)).background(color)) {
                cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 1)
        Text("%,d items · %s".format(album.count, formatBytes(album.bytes)), style = PrismType.label, color = Prism.Muted)
    }
}

/** Name + colour form, used to create and to rename albums. */
@Composable
fun AlbumEditor(initialName: String, initialColor: Long, action: String, onSubmit: (String, Long) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableLongStateOf(initialColor) }
    Column(Modifier.fillMaxWidth().prismBlock(Prism.Surface, 24).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecretField(
            label = "Album name",
            value = name,
            onValueChange = { name = it.take(60) },
            placeholder = "e.g. Holidays 2026",
            revealable = false,
            monospace = false,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
            onImeAction = { onSubmit(name, color) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumsViewModel.COLORS.forEach { c ->
                val on = c == color
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(3.dp, if (on) Prism.Paper else Color.Transparent, CircleShape)
                        .selectable(selected = on, role = Role.RadioButton) { color = c }
                        .semantics { contentDescription = "Colour" },
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(onClick = { onSubmit(name, color) }, shape = CircleShape, color = Prism.Paper, contentColor = Prism.Ink, modifier = Modifier.height(42.dp)) {
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(action, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight)) }
            }
        }
    }
}

@Composable
fun AlbumScreen(id: String, onBack: () -> Unit, onOpenFile: (VaultFile) -> Unit, viewModel: AlbumsViewModel = hiltViewModel()) {
    val album by remember(id) { viewModel.album(id) }.collectAsState(initial = null)
    val files by remember(id) { viewModel.filesIn(id) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album?.name ?: "", style = PrismType.title, color = Prism.Paper, maxLines = 1)
                        Text("%,d ITEMS".format(files.size), style = PrismType.label, color = Prism.Muted)
                    }
                    RoundIconButton(Icons.Outlined.Edit, "Edit album", { editing = !editing })
                }
                if (editing) album?.let { a ->
                    AlbumEditor(a.name, a.color, "Save") { name, color ->
                        viewModel.rename(a.id, name, color)
                        editing = false
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete album (files stay in the vault)", color = Prism.Orange, style = PrismType.body) }
                }
                if (files.isEmpty()) EmptyState("This album is empty.", text = "Open a photo or video and tap Album to add it here.")
            }
        }
        items(files, key = { it.id }) { file ->
            if (file.kind == com.shahaabapps.obscura.vault.FileKind.PHOTO || file.kind == com.shahaabapps.obscura.vault.FileKind.VIDEO) {
                MediaTile(
                    file, viewModel::thumbnail, badge = null, radius = 8,
                    modifier = Modifier.aspectRatio(1f).semantics { contentDescription = "${GalleryLogic.typeLabel(file)}, ${file.name}" }.clickable(role = Role.Button) { onOpenFile(file) },
                )
            } else {
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Prism.Surface).clickable(role = Role.Button) { onOpenFile(file) }.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(file.name, style = PrismType.label, color = Prism.Paper, maxLines = 3) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Prism.Surface,
            title = { Text("Delete this album?", style = PrismType.heading, color = Prism.Paper) },
            text = { Text("The album goes away; its ${files.size} files stay sealed in the vault.", style = PrismType.body, color = Prism.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(id)
                    onBack()
                }) { Text("Delete album", color = Prism.Orange, style = PrismType.button) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep", color = Prism.Paper, style = PrismType.button) } },
        )
    }
}

/** Bottom-sheet content: put one file into an album (or take it out), or create a new album for it. */
@Composable
fun AlbumPicker(fileId: String, currentAlbumId: String?, onDone: () -> Unit, viewModel: AlbumsViewModel = hiltViewModel()) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Add to album", style = PrismType.heading, color = Prism.Paper)
        if (creating) {
            AlbumEditor("", AlbumsViewModel.COLORS[0], "Create & add") { name, color ->
                viewModel.create(name, color, thenAdd = fileId)
                onDone()
            }
        } else {
            PillButton("New album", onClick = { creating = true }, container = Prism.SurfaceHigh, content = Prism.Paper)
        }
        LazyColumn(Modifier.height(320.dp)) {
            items(albums.orEmpty(), key = { it.id }) { album ->
                val inIt = album.id == currentAlbumId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) {
                            viewModel.moveTo(fileId, if (inIt) null else album.id)
                            onDone()
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(album.color)))
                    Spacer(Modifier.width(12.dp))
                    Text(album.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, modifier = Modifier.weight(1f))
                    if (inIt) {
                        Icon(Icons.Outlined.Check, null, tint = Prism.Mint)
                        Spacer(Modifier.width(6.dp))
                        Text("Remove", style = PrismType.label, color = Prism.Orange)
                    } else {
                        Text("%,d".format(album.count), style = PrismType.label, color = Prism.Muted)
                    }
                }
            }
        }
    }
}
