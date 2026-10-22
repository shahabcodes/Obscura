package com.shahaabapps.obscura.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.gallery.formatDuration
import com.shahaabapps.obscura.ui.gallery.rememberThumbnail
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.VaultFile

/** Every sealed audio file as one queue: tap to play, seek, skip, loop and change speed. */
@Composable
fun AudioScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onImport: () -> Unit) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    if (!library.loaded) return
    val tracks = library.audio
    val factory = remember { viewModel.dataSourceFactory() } ?: return
    val player = rememberVaultPlayer(factory)
    val ids = tracks.map { it.id }
    LaunchedEffect(ids) {
        // (Re)build the queue when tracks are added or shredded, keeping the current track.
        val currentId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        player.setMediaItems(tracks.map(::mediaItem), tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0), if (currentId != null) position else 0)
        player.prepare()
    }
    val playback = rememberPlaybackState(player)
    val current = tracks.getOrNull(playback.index)
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Text("Audio", style = PrismType.title, color = Prism.Paper, modifier = Modifier.weight(1f))
                Text("%,d TRACKS".format(tracks.size), style = PrismType.label, color = Prism.Muted)
            }
            if (tracks.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                Text("No audio yet.", style = PrismType.heading, color = Prism.Paper)
                Spacer(Modifier.height(12.dp))
                com.shahaabapps.obscura.ui.components.PillButton("Import audio", onClick = onImport)
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Prism.Mint).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Disc(current, viewModel, spinning = playback.isPlaying)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("NOW PLAYING", style = PrismType.label, color = Prism.Ink)
                    Text(current?.name ?: "—", style = PrismType.heading, color = Prism.Ink, maxLines = 2)
                    current?.let { Text(formatBytes(it.sizeBytes), style = PrismType.label, color = Prism.Ink) }
                }
            }

            playback.error?.let { Text(it, style = PrismType.body, color = Prism.Orange) }

            val duration = playback.durationMs.coerceAtLeast(1)
            Column {
                Slider(
                    value = scrubbing ?: (playback.positionMs.toFloat() / duration),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { player.seekTo((it * duration).toLong()) }
                        scrubbing = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Prism.Paper, activeTrackColor = Prism.Mint, inactiveTrackColor = Prism.SurfaceHigh),
                    modifier = Modifier.semantics { contentDescription = "Seek" },
                )
                Row {
                    Text(formatDuration(scrubbing?.let { (it * duration).toLong() } ?: playback.positionMs), style = PrismType.label, color = Prism.Muted)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(playback.durationMs), style = PrismType.label, color = Prism.Muted)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerButton("Previous track", { player.seekToPreviousMediaItem() }) { Icon(Icons.Filled.SkipPrevious, null, tint = Prism.Paper) }
                Box(Modifier.size(68.dp).clip(CircleShape).background(Prism.Sun).semantics { contentDescription = if (playback.isPlaying) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
                    IconButton(onClick = { player.togglePlayback() }) {
                        Icon(if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Prism.Ink, modifier = Modifier.size(34.dp))
                    }
                }
                PlayerButton("Next track", { player.seekToNextMediaItem() }) { Icon(Icons.Filled.SkipNext, null, tint = Prism.Paper) }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (playback.repeat) Prism.Mint else Prism.Surface)
                        .semantics { contentDescription = if (playback.repeat) "Stop repeating" else "Repeat track" },
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { player.toggleRepeat() }) { Icon(Icons.Outlined.Repeat, null, tint = if (playback.repeat) Prism.Ink else Prism.Paper) }
                }
            }
            SpeedChips(playback.speed) { player.setPlaybackSpeed(it) }
        }

        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                val playing = index == playback.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (playing) Prism.Surface else Prism.Ink)
                        .clickable(role = Role.Button, onClickLabel = "Play") {
                            player.seekTo(index, 0)
                            player.play()
                        }
                        .semantics { contentDescription = "Track ${index + 1}, ${track.name}" }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(index + 1), style = PrismType.label, color = if (playing) Prism.Mint else Prism.Muted)
                    Spacer(Modifier.width(12.dp))
                    Text(track.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = if (playing) Prism.Mint else Prism.Paper, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(track.durationMs?.let(::formatDuration) ?: formatBytes(track.sizeBytes), style = PrismType.label, color = Prism.Muted)
                }
            }
        }
    }
}

/** Embedded cover art when the file has one, otherwise a record drawn in Prism colours. */
@Composable
private fun Disc(track: VaultFile?, viewModel: LibraryViewModel, spinning: Boolean) {
    val art = track?.let { rememberThumbnail(it, viewModel::thumbnail) }
    Box(Modifier.size(92.dp).clip(CircleShape).background(Prism.Ink), contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Canvas(Modifier.fillMaxSize()) {
                listOf(0.82f, 0.66f, 0.5f).forEach { drawCircle(Prism.Line, radius = size.minDimension / 2 * it, style = Stroke(1.5.dp.toPx())) }
                drawCircle(if (spinning) Prism.Sun else Prism.Muted, radius = size.minDimension * 0.17f)
                drawCircle(Prism.Ink, radius = size.minDimension * 0.03f, center = Offset(size.width / 2, size.height / 2))
            }
        }
    }
}
