package com.shahaabapps.obscura.ui.organize

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.SecretField
import com.shahaabapps.obscura.ui.gallery.MediaTile
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import java.text.DateFormat
import java.util.Date

@Composable
fun SearchScreen(onOpen: (VaultFile) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Search", style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        SecretField(
            label = "Search the vault",
            value = query,
            onValueChange = { viewModel.query.value = it },
            placeholder = "Names, like \"passport\" or \"2024\"",
            revealable = false,
            monospace = false,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
            accent = Prism.Sun,
            trailing = {
                if (query.isNotEmpty()) IconButton(onClick = { viewModel.query.value = "" }) { Icon(Icons.Outlined.Close, "Clear search", tint = Prism.Paper) }
                else Icon(Icons.Outlined.Search, null, tint = Prism.Muted, modifier = Modifier.padding(12.dp))
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchKind.entries.forEach { k ->
                val on = k == kind
                Text(
                    k.label,
                    style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                    color = if (on) Prism.Ink else Prism.Paper,
                    modifier = Modifier.clip(CircleShape).background(if (on) Prism.Paper else Prism.Surface)
                        .selectable(selected = on, role = Role.Tab) { viewModel.kind.value = k }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            query.isBlank() -> Hint("Search runs inside the encrypted index on this phone. Nothing is sent anywhere.")
            results.searched && results.files.isEmpty() -> Hint("Nothing sealed matches \"${query.trim()}\".")
            else -> {
                Text(
                    "%,d RESULT%s".format(results.files.size, if (results.files.size == 1) "" else "S"),
                    style = PrismType.label,
                    color = Prism.Muted,
                )
                LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp)) {
                    items(results.files, key = { it.id }) { file -> ResultRow(file, viewModel) { onOpen(file) } }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(file: VaultFile, viewModel: SearchViewModel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${file.kind.name.lowercase()}, ${file.name}" }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.kind == FileKind.PHOTO || file.kind == FileKind.VIDEO) {
            MediaTile(file, viewModel::thumbnail, badge = null, radius = 12, modifier = Modifier.size(52.dp))
        } else {
            val color = if (file.kind == FileKind.AUDIO) Prism.Mint else Prism.Orange
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
                Text(file.name.substringAfterLast('.', "file").uppercase().take(4), style = PrismType.label, color = Prism.Ink)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 1)
            Text("${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.takenAt))} · ${formatBytes(file.sizeBytes)}", style = PrismType.label, color = Prism.Muted)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = PrismType.body, color = Prism.Muted, modifier = Modifier.padding(top = 24.dp, start = 4.dp, end = 4.dp))
}
