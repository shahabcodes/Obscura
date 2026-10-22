package com.shahaabapps.obscura.ui.gallery

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile

/** Decrypts a thumbnail off the main thread and fades it in over a shimmer-coloured block. */
@Composable
fun rememberThumbnail(file: VaultFile, load: suspend (VaultFile) -> Bitmap?): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.id) { value = load(file) }
    return bitmap
}

@Composable
fun MediaTile(
    file: VaultFile,
    load: suspend (VaultFile) -> Bitmap?,
    badge: String?,
    radius: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberThumbnail(file, load)
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(260), label = "thumb")
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Prism.SurfaceHigh)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(alpha),
            )
        }
        if (file.kind == FileKind.VIDEO) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape).background(Prism.Ink.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Prism.Paper, modifier = Modifier.size(12.dp))
                file.durationMs?.let { Text(formatDuration(it), style = PrismType.label.copy(fontSize = PrismType.label.fontSize * 0.85f), color = Prism.Paper) }
            }
        }
        if (file.favorite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Prism.Pink,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(14.dp),
            )
        }
        badge?.let {
            Text(
                it,
                style = PrismType.label.copy(fontSize = PrismType.label.fontSize * 0.85f),
                color = Prism.Paper,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(7.dp)).background(Prism.Ink.copy(alpha = 0.8f)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
