package com.shahaabapps.obscura.ui.gallery

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.Motion
import com.shahaabapps.obscura.ui.components.MotionArt
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Full-screen, auto-advancing slideshow through the gallery in its current order. */
@Composable
fun ReelScreen(viewModel: GalleryViewModel, startId: String, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val files = state.flat
    if (files.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val start = remember { files.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { files.size }
    val scope = rememberCoroutineScope()
    var paused by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableIntStateOf(5) }
    var burstKey by remember { mutableIntStateOf(0) }
    var confirmShred by remember { mutableStateOf<VaultFile?>(null) }
    val progress = remember { Animatable(0f) }
    val current = files[pager.currentPage.coerceAtMost(files.lastIndex)]

    // Auto-advance: fill the progress bar, then move to the next item (wrapping at the end).
    // Keyed on settledPage, not currentPage: currentPage changes halfway through a scroll, and
    // restarting there would cancel the scroll itself (leaving the reel stuck between items).
    // One effect owns the progress animation; a second one resetting it would cancel the timer.
    var shownPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pager.settledPage, paused, seconds, files.size) {
        val page = pager.settledPage
        if (shownPage != page) {
            shownPage = page
            progress.snapTo(0f)
        }
        if (paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * seconds * 1000).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        pager.animateScrollToPage((page + 1) % files.size)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, key = { files[it].id }, beyondViewportPageCount = 1) { page ->
            ReelPage(files[page], viewModel, onDoubleTap = {
                if (!files[page].favorite) viewModel.setFavorite(files[page], true)
                burstKey++
            })
        }

        HeartBurst(burstKey)

        // Top: progress, position, pause, close
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Prism.Paper.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().background(Prism.Paper))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundGlass(onClick = onClose, label = "Close reel") { Icon(Icons.Outlined.Close, contentDescription = null, tint = Prism.Paper) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Reel", style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
                    Text("%,d OF %,d · EVERY %dS".format(pager.currentPage + 1, files.size, seconds), style = PrismType.label, color = Prism.Paper.copy(alpha = 0.8f))
                }
                RoundGlass(onClick = { paused = !paused }, label = if (paused) "Resume reel" else "Pause reel") {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null, tint = Prism.Paper)
                }
            }
        }

        // Right: actions
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RoundGlass(onClick = {
                viewModel.setFavorite(current, !current.favorite)
                if (!current.favorite) burstKey++
            }, label = if (current.favorite) "Remove from favourites" else "Add to favourites") {
                Icon(if (current.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null, tint = if (current.favorite) Prism.Pink else Prism.Paper)
            }
            RoundGlass(onClick = { confirmShred = current }, label = "Shred") {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = Prism.Orange)
            }
        }

        // Bottom: details, previous/next, speed
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .safeDrawingPadding().padding(16.dp),
        ) {
            Text(
                if (current.kind == FileKind.VIDEO) "VIDEO ${current.durationMs?.let(::formatDuration) ?: ""}" else GalleryLogic.typeLabel(current),
                style = PrismType.label,
                color = Prism.Ink,
                modifier = Modifier.clip(CircleShape).background(Prism.Sun).padding(horizontal = 9.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(current.name, style = PrismType.heading, color = Prism.Paper, maxLines = 1)
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.takenAt))} · ${formatBytes(current.sizeBytes)}",
                style = PrismType.body,
                color = Prism.Paper.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(CircleShape).background(Prism.Surface.copy(alpha = 0.92f)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundGlass(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + files.size) % files.size) } }, label = "Previous") {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, tint = Prism.Paper)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    listOf(3, 5, 10).forEach { s ->
                        val on = seconds == s
                        Text(
                            "${s}s",
                            style = PrismType.label,
                            color = if (on) Prism.Ink else Prism.Paper,
                            modifier = Modifier.padding(horizontal = 3.dp).clip(CircleShape).background(if (on) Prism.Paper else Color.Transparent)
                                .selectable(selected = on, role = Role.RadioButton) { seconds = s }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(Prism.Sun), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % files.size) } }) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next", tint = Prism.Ink)
                    }
                }
            }
        }
    }

    confirmShred?.let { file ->
        ShredDialog(file, onDismiss = { confirmShred = null }) {
            confirmShred = null
            viewModel.shred(file)
        }
    }
}

@Composable
private fun ReelPage(file: VaultFile, viewModel: GalleryViewModel, onDoubleTap: () -> Unit) {
    val maxSide = with(LocalDensity.current) {
        val config = LocalConfiguration.current
        (maxOf(config.screenWidthDp, config.screenHeightDp).dp.toPx()).toInt().coerceAtMost(3072)
    }
    val preview = rememberThumbnail(file, viewModel::thumbnail)
    val full by produceState<Bitmap?>(null, file.id) { value = viewModel.display(file, maxSide) }
    // A slow push-in while the item is on screen keeps stills alive, like a Ken Burns pan.
    val zoomAnim = remember(file.id) { Animatable(1f) }
    LaunchedEffect(file.id) { zoomAnim.animateTo(1.08f, tween(8_000, easing = LinearEasing)) }
    val zoom = zoomAnim.value
    Box(
        Modifier.fillMaxSize().pointerInput(file.id) { detectTapGestures(onDoubleTap = { onDoubleTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        (full ?: preview)?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
            )
        }
        if (file.kind == FileKind.VIDEO) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Prism.Ink.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Prism.Paper, modifier = Modifier.size(36.dp))
            }
        }
    }
}

/** The motion kit's heart burst over the centre of the screen; each new key plays it again. */
@Composable
private fun HeartBurst(key: Int) {
    if (key == 0) return
    var visible by remember(key) { mutableStateOf(true) }
    if (!visible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.runtime.key(key) {
            MotionArt(Motion.FAVORITE, Modifier.size(220.dp), onFinished = { visible = false })
        }
    }
}

@Composable
private fun RoundGlass(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Prism.Ink.copy(alpha = 0.55f)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) { content() }
    }
}
