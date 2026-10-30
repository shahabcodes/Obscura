package com.shahaabapps.obscura.media

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.shahaabapps.obscura.ui.components.ImmersiveMode
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.SensitiveWindow
import com.shahaabapps.obscura.ui.gallery.ShredDialog
import com.shahaabapps.obscura.ui.gallery.formatDuration
import com.shahaabapps.obscura.ui.gallery.rememberThumbnail
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.VaultFile

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(viewModel: LibraryViewModel, startId: String, onBack: () -> Unit) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    if (!library.loaded) return
    // Swiping and "Up next" follow the gallery the video was opened from -- its order, its
    // filter, its length -- so the next video is the next one on that screen. Opened from
    // somewhere the gallery is not showing it, a search or an album, every video is in reach.
    // Settled once: the list under a playing video must not change shape beneath it.
    val fromGallery = remember { library.galleryVideos.any { it.id == startId } }
    val videos = if (fromGallery) library.galleryVideos else library.videos
    val factory = remember { viewModel.dataSourceFactory() } ?: return
    val player = rememberVaultPlayer(factory)
    var started by remember { mutableStateOf(false) }
    // Asked for a video that is no longer here — shredded while the vault was locked, say.
    // Playing whatever happens to be first instead would be answering a different question.
    val wanted = videos.indexOfFirst { it.id == startId }
    LaunchedEffect(videos.isNotEmpty(), wanted) {
        if (videos.isNotEmpty() && wanted < 0) {
            onBack()
            return@LaunchedEffect
        }
        if (!started && videos.isNotEmpty()) {
            started = true
            player.setMediaItems(videos.map(::mediaItem), wanted, 0)
            player.prepare()
            player.play()
        }
    }
    val playback = rememberPlaybackState(player)
    val current = videos.getOrNull(playback.index) ?: videos.firstOrNull() ?: return
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    // Tap the picture to get everything else out of the way; tap again to bring it back.
    var chrome by rememberSaveable { mutableStateOf(true) }
    var confirmShred by remember { mutableStateOf<VaultFile?>(null) }
    // Kept across a turn of the phone, which rebuilds the screen: losing the sheet mid-GIF
    // would leave the work running with no progress and no word of where it went.
    var makingGif by rememberSaveable { mutableStateOf(false) }
    var gifFromId by rememberSaveable { mutableStateOf<String?>(null) }
    var gifFromMs by rememberSaveable { mutableStateOf(0L) }
    val gif by viewModel.gif.collectAsStateWithLifecycle()

    ImmersiveMode(!chrome)
    // The player reports the true shape once it has read the file; until then, and if it never
    // does, the dimensions recorded when the file was sealed keep the picture from being squeezed.
    val storedAspect = current.width?.let { w -> current.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h } }
    val aspect = playback.videoSize.aspectOrNull() ?: storedAspect
    // Sideways to the next video or back to the last, as with photos. Past either end the
    // swipe simply does nothing, rather than wrapping round to the far end of the list.
    val swipe: (Boolean) -> Unit = { forward ->
        val moved = if (forward) player.hasNextMediaItem() else player.hasPreviousMediaItem()
        if (moved) {
            if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
            player.play()
        }
    }

    if (!chrome) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoStage(player, playback, aspect, Modifier.fillMaxSize(), onSwipe = swipe) { chrome = true }
        }
        BackHandler { chrome = true }
        return
    }

    Column(
        Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("VIDEOS", style = PrismType.label, color = Prism.Muted, modifier = Modifier.weight(1f))
            Text(
                "DECRYPTING IN RAM",
                style = PrismType.label,
                color = Prism.Mint,
                modifier = Modifier.clip(CircleShape).background(Prism.Surface).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        val ratio = aspect ?: (16f / 9f)
        VideoStage(
            player = player,
            playback = playback,
            aspect = aspect,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).aspectRatio(ratio.coerceIn(0.5f, 2.4f)).clip(RoundedCornerShape(26.dp)),
            onSwipe = swipe,
            onTap = { chrome = false },
        )

        playback.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current.name, style = PrismType.heading, color = Prism.Paper, maxLines = 2)
                Text(
                    listOfNotNull(current.durationMs?.let(::formatDuration), formatBytes(current.sizeBytes), current.width?.let { "${it}×${current.height}" }).joinToString(" · "),
                    style = PrismType.label,
                    color = Prism.Muted,
                )
            }
            Spacer(Modifier.width(10.dp))
            RoundIconButton(Icons.Outlined.Gif, "Make a GIF", {
                viewModel.dismissGif()
                // Pinned here: the player moves on by itself, and the GIF must come from the clip
                // and the moment the user was actually looking at. The playhead is read from the
                // player rather than from the screen's state, which is a snapshot up to a quarter
                // of a second old, so the GIF starts where the picture really stopped.
                player.pause()
                gifFromId = current.id
                gifFromMs = player.currentPosition.coerceAtLeast(0)
                makingGif = true
            })
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Outlined.DeleteForever, "Shred", { confirmShred = current }, content = Prism.Orange)
        }

        Column {
            val duration = playback.durationMs.coerceAtLeast(1)
            Slider(
                value = scrubbing ?: (playback.positionMs.toFloat() / duration),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { player.seekTo((it * duration).toLong()) }
                    scrubbing = null
                },
                colors = SliderDefaults.colors(thumbColor = Prism.Paper, activeTrackColor = Prism.Sun, inactiveTrackColor = Prism.SurfaceHigh),
                modifier = Modifier.semantics { contentDescription = "Seek" },
            )
            Row {
                Text(formatDuration(scrubbing?.let { (it * duration).toLong() } ?: playback.positionMs), style = PrismType.label, color = Prism.Muted)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(playback.durationMs), style = PrismType.label, color = Prism.Muted)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerButton("Back 10 seconds", { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Outlined.Replay10, null, tint = Prism.Paper) }
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Prism.Sun)
                    .semantics { contentDescription = if (playback.isPlaying) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { player.togglePlayback() }) {
                    Icon(if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Prism.Ink, modifier = Modifier.size(32.dp))
                }
            }
            PlayerButton("Forward 10 seconds", { player.seekTo(player.currentPosition + 10_000) }) { Icon(Icons.Outlined.Forward10, null, tint = Prism.Paper) }
            Spacer(Modifier.weight(1f))
            SpeedChips(playback.speed) { player.setPlaybackSpeed(it) }
        }

        val upNext = videos.drop(playback.index + 1).take(3)
        if (upNext.isNotEmpty()) {
            Text("Up next", style = PrismType.heading, color = Prism.Paper)
            upNext.forEach { file ->
                UpNextRow(file, viewModel) { player.seekTo(videos.indexOf(file), 0); player.play() }
            }
        }
    }

    // Still shown after a turn of the phone while there is something to say, since the work
    // itself lives in the view model and carries on regardless.
    if (makingGif || gif.running || gif.saved != null) {
        // Read inside the refusal below, so it sees how things stand now rather than how they
        // stood when the sheet opened.
        val running by rememberUpdatedState(gif.running)
        ModalBottomSheet(
            onDismissRequest = { if (!running) makingGif = false },
            // Swiping down settles the sheet out of sight whether the request is honoured or not,
            // which would leave the GIF being made with no way back to its progress. While it is
            // running the sheet simply refuses to go.
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !running },
            ),
            containerColor = Prism.Surface,
        ) {
            SensitiveWindow()
            val source = videos.firstOrNull { it.id == gifFromId } ?: current
            val from = if (gifFromId != null) gifFromMs else playback.positionMs
            MakeGifSheet(
                state = gif,
                name = source.name,
                fromMs = from,
                onCreate = { length -> viewModel.makeGif(source, from, length) },
                onClose = {
                    makingGif = false
                    gifFromId = null
                    viewModel.dismissGif()
                },
            )
        }
    }

    confirmShred?.let { file ->
        ShredDialog(file, onDismiss = { confirmShred = null }) {
            confirmShred = null
            // Leaving the queue behind: the player reloads from what is left.
            player.pause()
            viewModel.shred(file) { if (videos.size <= 1) onBack() }
        }
    }
}

/** Picks how much of the video to turn into a GIF, then shows how it is getting on. */
@Composable
private fun MakeGifSheet(state: GifMaking, name: String, fromMs: Long, onCreate: (Long) -> Unit, onClose: () -> Unit) {
    var length by remember { mutableStateOf(GifRequest.lengths[1]) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Make a GIF", style = PrismType.heading, color = Prism.Paper)
        when {
            state.saved != null -> {
                Text(
                    "Saved as \"${state.saved}\" in your vault, under GIFs. It is sealed like everything else.",
                    style = PrismType.body,
                    color = Prism.Mint,
                )
                PillButton("Done", onClick = onClose, container = Prism.Mint)
            }
            state.running -> {
                Text("Reading the frames and choosing colours…", style = PrismType.body, color = Prism.Muted)
                Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Prism.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Prism.Sun))
                }
                Text("${(state.fraction * 100).toInt()}%", style = PrismType.label, color = Prism.Muted)
            }
            else -> {
                Text(
                    "From \"$name\" at ${formatDuration(fromMs)}, where you paused it. The GIF is sealed into your vault; nothing leaves the phone.",
                    style = PrismType.body,
                    color = Prism.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GifRequest.lengths.forEach { option ->
                        val on = length == option
                        Text(
                            "${option / 1000}s",
                            style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight),
                            color = if (on) Prism.Ink else Prism.Paper,
                            modifier = Modifier.clip(CircleShape).background(if (on) Prism.Paper else Prism.SurfaceHigh)
                                .selectable(selected = on, role = Role.RadioButton) { length = option }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
                state.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }
                PillButton("Make the GIF", onClick = { onCreate(length) })
            }
        }
    }
}

/**
 * The picture itself: pinch to zoom and drag to move once zoomed, swipe sideways for the next
 * or the last video, tap to hide everything else. The surface is marked secure, so frames never
 * reach a screenshot or a recording.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoStage(
    player: androidx.media3.exoplayer.ExoPlayer,
    playback: PlaybackState,
    aspect: Float?,
    modifier: Modifier,
    onSwipe: (forward: Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // How far a sideways swipe has carried the picture, so it follows the finger.
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .background(Color.Black)
            .testTag("Video stage")
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        // Double tap is the quick way back to a plain, unzoomed picture.
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                // Only at normal size: zoomed in, a drag moves around the picture instead. It
                // waits for the finger to have moved further sideways than up or down, so the
                // page around it still scrolls.
                val far = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (scale == 1f && kotlin.math.abs(drag) > far) onSwipe(drag < 0)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    if (scale == 1f) {
                        drag += amount
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        FitVideo(aspect, Modifier.fillMaxSize()) { fitted ->
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        setSecure(true) // keep decrypted frames out of screenshots and recordings
                        player.setVideoSurfaceView(this)
                    }
                },
                modifier = fitted.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x + drag
                    translationY = offset.y
                },
            )
        }
        if (!playback.isPlaying) {
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(Prism.Ink.copy(alpha = 0.8f))
                    .clickable(role = Role.Button, onClickLabel = "Play") { player.togglePlayback() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Prism.Sun, modifier = Modifier.size(34.dp))
            }
        }
    }
}

@Composable
private fun UpNextRow(file: VaultFile, viewModel: LibraryViewModel, onClick: () -> Unit) {
    val thumb = rememberThumbnail(file, viewModel::thumbnail)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 84.dp, height = 52.dp).clip(RoundedCornerShape(12.dp)).background(Prism.SurfaceHigh)) {
            thumb?.let {
                androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper, maxLines = 1)
            Text(listOfNotNull(file.durationMs?.let(::formatDuration), formatBytes(file.sizeBytes)).joinToString(" · "), style = PrismType.label, color = Prism.Muted)
        }
    }
}

@Composable
fun PlayerButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Prism.Surface).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick) { icon() }
    }
}

@Composable
fun SpeedChips(current: Float, onPick: (Float) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Prism.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
            val on = current == speed
            Text(
                if (speed == 1f) "1×" else "${speed}×",
                style = PrismType.label,
                color = if (on) Prism.Ink else Prism.Paper,
                modifier = Modifier.clip(CircleShape).background(if (on) Prism.Sun else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(speed) }
                    .padding(horizontal = 9.dp, vertical = 9.dp),
            )
        }
    }
}

/** Repeat toggle helper for the audio player. */
fun Player.toggleRepeat() {
    repeatMode = if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
}

/**
 * Play/pause that also works after the end: a finished player ignores play() until it is
 * rewound, which made the Play button look broken once a track or video had ended.
 */
fun Player.togglePlayback() {
    when {
        isPlaying -> pause()
        playbackState == Player.STATE_ENDED -> {
            seekTo(currentMediaItemIndex, 0)
            play()
        }
        else -> play()
    }
}
