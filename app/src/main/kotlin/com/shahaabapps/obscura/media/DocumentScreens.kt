package com.shahaabapps.obscura.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.DecryptingIndicator
import com.shahaabapps.obscura.ui.components.EmptyState
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.gallery.ShredDialog
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.VaultFile
import java.text.DateFormat
import java.util.Date

private enum class DocSort(val label: String) { DATE("Date"), NAME("Name"), SIZE("Size") }

@Composable
fun DocumentsScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onImport: () -> Unit) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(DocSort.DATE) }
    val docs = remember(library.documents, sort) {
        when (sort) {
            DocSort.DATE -> library.documents.sortedByDescending { it.takenAt }
            DocSort.NAME -> library.documents.sortedBy { it.name.lowercase() }
            DocSort.SIZE -> library.documents.sortedByDescending { it.sizeBytes }
        }
    }
    Column(Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Documents", style = PrismType.title, color = Prism.Paper)
                Text("%,d FILES".format(docs.size), style = PrismType.label, color = Prism.Muted)
            }
        }
        if (library.loaded && docs.isEmpty()) {
            EmptyState("No documents yet.", Modifier.padding(24.dp), action = { PillButton("Import files", onClick = onImport) })
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp).clip(CircleShape).background(Prism.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DocSort.entries.forEach { s ->
                val on = sort == s
                Text(
                    s.label,
                    style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                    color = if (on) Prism.Ink else Prism.Paper,
                    modifier = Modifier.clip(CircleShape).background(if (on) Prism.Sun else Color.Transparent)
                        .selectable(selected = on, role = Role.RadioButton) { sort = s }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(docs, key = { it.id }) { doc ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open") { onOpen(doc.id) }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .semantics(mergeDescendants = true) { },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (label, color) = badge(doc)
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(color), contentAlignment = Alignment.Center) {
                        Text(label, style = PrismType.label, color = Prism.Ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(doc.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 1)
                        Text("${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.takenAt))} · ${formatBytes(doc.sizeBytes)}", style = PrismType.label, color = Prism.Muted)
                    }
                }
            }
        }
    }
}

private fun badge(file: VaultFile): Pair<String, Color> {
    val ext = file.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val color = when {
        file.mimeType == "application/pdf" -> Prism.Pink
        file.mimeType.contains("word") || ext == "DOC" || ext == "DOCX" -> Prism.Cobalt
        file.mimeType.contains("sheet") || ext == "XLS" || ext == "XLSX" || ext == "CSV" -> Prism.Mint
        file.mimeType.startsWith("text/") -> Prism.Paper
        else -> Prism.Orange
    }
    return ext to color
}

/** Opens one document: PDFs and text in-app, anything else shows its details. */
@Composable
fun DocumentScreen(viewModel: LibraryViewModel, id: String, onBack: () -> Unit) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val file = library.documents.firstOrNull { it.id == id }
    if (!library.loaded) return
    if (file == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    var confirmShred by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 1)
                Text(formatBytes(file.sizeBytes), style = PrismType.label, color = Prism.Muted)
            }
            RoundIconButton(Icons.Outlined.DeleteForever, "Shred", { confirmShred = true }, content = Prism.Orange)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                file.mimeType == "application/pdf" && SealedPdf.supported -> PdfView(file, viewModel)
                file.mimeType == "application/pdf" -> Unsupported("PDF preview needs Android 11 or newer. The file is safely sealed in the vault.")
                TextPreview.isText(file) -> TextView(file, viewModel)
                else -> Unsupported("There's no in-app preview for this kind of file yet. It is safely sealed in the vault.")
            }
        }
    }
    if (confirmShred) {
        ShredDialog(file, onDismiss = { confirmShred = false }) {
            confirmShred = false
            viewModel.shred(file, onBack)
        }
    }
}

@Composable
private fun PdfView(file: VaultFile, viewModel: LibraryViewModel) {
    val pdf by produceState<Result<SealedPdf>?>(null, file.id) {
        value = runCatching { SealedPdf.open { checkNotNull(viewModel.open(file)) { "Vault is locked" } } }
    }
    DisposableEffect(pdf) { onDispose { pdf?.getOrNull()?.close() } }
    when (val result = pdf) {
        null -> Loading()
        else -> result.fold(
            onSuccess = { document ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtMost(2000)
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(document.pageCount) { index ->
                            val page by produceState<Bitmap?>(null, index, widthPx) { value = document.render(index, widthPx) }
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(page?.let { it.width.toFloat() / it.height } ?: 0.707f)
                                    .clip(RoundedCornerShape(8.dp)).background(Color.White)
                                    .semantics { contentDescription = "Page ${index + 1} of ${document.pageCount}" },
                            ) {
                                page?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            },
            onFailure = { Unsupported("This PDF couldn't be opened: ${it.message}") },
        )
    }
}

@Composable
private fun TextView(file: VaultFile, viewModel: LibraryViewModel) {
    val text by produceState<Pair<String, Boolean>?>(null, file.id) {
        value = runCatching { TextPreview.read { checkNotNull(viewModel.open(file)) { "Vault is locked" } } }.getOrElse { ("Couldn't read this file: ${it.message}") to false }
    }
    val content = text ?: return Loading()
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
            Text(content.first, style = PrismType.label.copy(fontSize = PrismType.body.fontSize, letterSpacing = PrismType.body.letterSpacing), color = Prism.Paper)
            if (content.second) {
                Spacer(Modifier.height(12.dp))
                Text("Showing the first 512 KB.", style = PrismType.label, color = Prism.Sun)
            }
        }
    }
}

@Composable
private fun Loading() {
    DecryptingIndicator(Modifier.fillMaxSize())
}

@Composable
private fun Unsupported(text: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, style = PrismType.body, color = Prism.Muted)
    }
}
