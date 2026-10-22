package com.shahaabapps.obscura.media

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.shahaabapps.obscura.vault.VaultFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * An ExoPlayer that reads sealed files. It pauses whenever the app leaves the screen
 * (media never keeps playing in the background with the vault open) and is released when
 * the screen goes away, including when the vault locks.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberVaultPlayer(factory: DataSource.Factory): ExoPlayer {
    val context = LocalContext.current
    val player = remember(factory) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    return player
}

fun mediaItem(file: VaultFile): MediaItem =
    MediaItem.Builder().setUri(SealedDataSource.uriFor(file)).setMediaId(file.id).build()

/** Snapshot of the player for the UI, refreshed a few times a second while playing. */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val index: Int = 0,
    val videoSize: VideoSize = VideoSize.UNKNOWN,
    val speed: Float = 1f,
    val repeat: Boolean = false,
    val error: String? = null,
)

@Composable
fun rememberPlaybackState(player: Player): PlaybackState {
    var state by remember(player) { mutableStateOf(PlaybackState()) }
    fun snapshot() = PlaybackState(
        isPlaying = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0),
        durationMs = player.duration.takeIf { it > 0 } ?: 0,
        index = player.currentMediaItemIndex,
        videoSize = player.videoSize,
        speed = player.playbackParameters.speed,
        repeat = player.repeatMode != Player.REPEAT_MODE_OFF,
        error = player.playerError?.let { "This file couldn't be played (${it.errorCodeName})." },
    )
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = snapshot()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, state.isPlaying) {
        while (isActive && state.isPlaying) {
            state = snapshot()
            delay(250)
        }
    }
    return state
}
