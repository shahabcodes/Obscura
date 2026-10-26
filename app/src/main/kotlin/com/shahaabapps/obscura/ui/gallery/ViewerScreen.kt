package com.shahaabapps.obscura.ui.gallery

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.AnimatedGif
import com.shahaabapps.obscura.ui.components.DecryptingIndicator
import com.shahaabapps.obscura.ui.components.ImmersiveMode
import com.shahaabapps.obscura.ui.components.Motion
import com.shahaabapps.obscura.ui.components.MotionArt
import com.shahaabapps.obscura.ui.components.SensitiveWindow
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(viewModel: GalleryViewModel, startId: String, onBack: () -> Unit, onReel: (String) -> Unit, onPlay: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(Modifier.fillMaxSize().background(Prism.Ink))
        return
    }
    val files = state.flat
    if (files.isEmpty()) {
        // The last photo was shredded or filtered away.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val start = remember { files.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { files.size }
    var zoomed by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // Tap the picture to get out of the way of it; tap again to bring the controls back.
    var chrome by rememberSaveable { mutableStateOf(true) }
    var confirmShred by remember { mutableStateOf<VaultFile?>(null) }
    var pickingAlbum by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf<Pair<BulkAction, VaultFile>?>(null) }
    val context = LocalContext.current
    val export by viewModel.export.collectAsStateWithLifecycle()
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    val saveCopy = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        viewModel.exportFile(uri, exportingId?.let { id -> files.firstOrNull { it.id == id } })
        exportingId = null
    }
    val current = files.getOrNull(pager.currentPage.coerceAtMost(files.lastIndex)) ?: return

    ImmersiveMode(!chrome)
    var burst by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        HorizontalPager(state = pager, key = { files[it].id }, userScrollEnabled = !zoomed, beyondViewportPageCount = 1) { page ->
            ZoomableMedia(
                file = files[page],
                viewModel = viewModel,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onPlay = { onPlay(files[page].id) },
                onTap = {
                    chrome = !chrome
                    if (!chrome) showInfo = false
                },
                controlsVisible = chrome,
                isCurrent = page == pager.currentPage,
            )
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Prism.Paper) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current.takenAt)), style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
                    Text("%,d OF %,d · %s".format(pager.currentPage + 1, files.size, formatBytes(current.sizeBytes)), style = PrismType.label, color = Prism.Muted)
                }
                CircleButton(onClick = {
                    if (!current.favorite) burst++
                    viewModel.setFavorite(current, !current.favorite)
                }) {
                    Icon(
                        if (current.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (current.favorite) "Remove from favourites" else "Add to favourites",
                        tint = if (current.favorite) Prism.Pink else Prism.Paper,
                    )
                }
            }
        }

        }

        if (burst > 0) {
            key(burst) {
                var playing by remember { mutableStateOf(true) }
                if (playing) {
                    MotionArt(Motion.FAVORITE, Modifier.align(Alignment.Center).size(200.dp), onFinished = { playing = false })
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)) {
            AnimatedVisibility(showInfo, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                InfoPanel(current)
            }
            ExportStatus(export, viewModel::cancelExport, viewModel::dismissExport, Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().height(68.dp).clip(CircleShape).background(Prism.Surface.copy(alpha = 0.95f)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction(Icons.Outlined.Info, "Info", active = showInfo) { showInfo = !showInfo }
                BarAction(Icons.Outlined.Slideshow, "Reel", active = false) { onReel(current.id) }
                BarAction(Icons.Outlined.Folder, "Album", active = current.albumId != null) { pickingAlbum = true }
                BarAction(Icons.Outlined.Share, "Share", active = false) { confirmOut = BulkAction.SHARE to current }
                BarAction(Icons.Outlined.Download, "Save copy", active = false) { confirmOut = BulkAction.EXPORT to current }
                BarAction(Icons.Outlined.DeleteForever, "Shred", active = false, tint = Prism.Orange) { confirmShred = current }
            }
        }
        }
    }

    confirmOut?.let { (action, file) ->
        BulkConfirm(
            action = action,
            count = 1,
            onConfirm = {
                confirmOut = null
                if (action == BulkAction.SHARE) {
                    context.startActivity(viewModel.share(listOf(file)))
                } else {
                    exportingId = file.id
                    viewModel.beginPicking()
                    saveCopy.launch(file.name to file.mimeType)
                }
            },
            onDismiss = { confirmOut = null },
        )
    }

    confirmShred?.let { file ->
        ShredDialog(file, onDismiss = { confirmShred = null }) {
            confirmShred = null
            viewModel.shred(file)
        }
    }
    if (pickingAlbum) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { pickingAlbum = false },
            containerColor = Prism.Surface,
        ) {
            SensitiveWindow()
            com.shahaabapps.obscura.ui.organize.AlbumPicker(listOf(current.id), current.albumId, onDone = { pickingAlbum = false })
        }
    }
}

@Composable
fun ShredDialog(file: VaultFile, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Prism.Surface,
        title = { Text("Shred this file?", style = PrismType.heading, color = Prism.Paper) },
        text = {
            SensitiveWindow()
            Text(
                "\"${file.name}\" will be removed from the vault for good. Its key is destroyed, so the encrypted data can never be recovered. There is no trash.",
                style = PrismType.body,
                color = Prism.Muted,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Shred", color = Prism.Orange, style = PrismType.button) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it", color = Prism.Paper, style = PrismType.button) } },
    )
}

@Composable
private fun ZoomableMedia(
    file: VaultFile,
    viewModel: GalleryViewModel,
    onZoomChanged: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onTap: () -> Unit,
    controlsVisible: Boolean,
    isCurrent: Boolean,
) {
    val maxSide = with(LocalDensity.current) {
        val config = LocalConfiguration.current
        (maxOf(config.screenWidthDp, config.screenHeightDp).dp.toPx() * 1.5f).toInt().coerceAtMost(4096)
    }
    val preview = rememberThumbnail(file, viewModel::thumbnail)
    val full by produceState<Bitmap?>(null, file.id) { value = viewModel.display(file, maxSide) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(file.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f
                    }
                    onZoomChanged(scale > 1f)
                })
            }
            .pointerInput(file.id) {
                // Pinch with two fingers, or pan once zoomed in. A single-finger swipe at normal
                // size is left unconsumed so the pager still turns pages.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            onZoomChanged(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val zoomLayer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        val bitmap = full ?: preview
        if (file.kind == FileKind.GIF) {
            // The whole GIF is decrypted in memory, because the frames can't be streamed.
            // Only this page's GIF is decrypted: the pager keeps a neighbour alive either side,
            // and three large GIFs at once would exhaust the phone's memory.
            val gif by produceState<ByteArray?>(null, file.id, isCurrent) {
                if (isCurrent) value = viewModel.bytes(file)
            }
            if (gif == null && bitmap == null) DecryptingIndicator()
            if (gif == null && bitmap != null) {
                Image(bitmap.asImageBitmap(), file.name, contentScale = ContentScale.Fit, modifier = zoomLayer)
            }
            AnimatedGif(gif, file.name, modifier = zoomLayer, playing = isCurrent)
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = zoomLayer,
            )
        } else {
            DecryptingIndicator()
        }
        if (file.kind == FileKind.VIDEO && controlsVisible) {
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(Prism.Sun),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onPlay, modifier = Modifier.size(80.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play video", tint = Prism.Ink, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(file: VaultFile) {
    val rows = buildList {
        add("TAKEN" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.takenAt)))
        add("SIZE" to formatBytes(file.sizeBytes))
        if (file.width != null && file.height != null) add("DIMENSIONS" to "${file.width} × ${file.height}")
        file.durationMs?.let { add("LENGTH" to formatDuration(it)) }
        add("SEALED" to DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.sealedAt)))
        add("ENCRYPTION" to "AES-256-GCM, own key")
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Prism.Ink.copy(alpha = 0.92f)).padding(16.dp)) {
        Text(file.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                pair.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = PrismType.label, color = Prism.Muted)
                        Text(v, style = PrismType.body, color = Prism.Paper)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Prism.Ink.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick) { content() }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, tint: androidx.compose.ui.graphics.Color = Prism.Paper, onClick: () -> Unit) {
    // Icon over a short label, so six actions fit across a phone.
    Column(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (active) Prism.Sun else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Prism.Ink else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = PrismType.label.copy(letterSpacing = PrismType.body.letterSpacing), color = if (active) Prism.Ink else tint, maxLines = 1)
    }
}
