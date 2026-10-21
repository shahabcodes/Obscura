package com.shahaabapps.obscura.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.StatusChip
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.navigation.Routes
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/**
 * The bento home screen. Counts are empty until the vault index exists (phase 2);
 * the layout and navigation are final.
 */
@Composable
fun HomeScreen(onLock: () -> Unit, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("Vault", style = PrismType.title.copy(fontSize = PrismType.hero.fontSize), color = Prism.Paper)
            Text(".", style = PrismType.title.copy(fontSize = PrismType.hero.fontSize), color = Prism.Sun)
            Spacer(Modifier.weight(1f))
            StatusChip("OFFLINE", dot = Prism.Mint)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Outlined.Lock, "Lock now", onLock, container = Prism.Paper, content = Prism.Ink)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(Prism.Surface)
                .clickable(role = Role.Button, onClick = { onOpen(Routes.SEARCH) })
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Prism.Muted)
            Spacer(Modifier.width(10.dp))
            Text("Search sealed files", style = PrismType.body, color = Prism.Muted)
        }

        StorageCard()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhotosTile(Modifier.weight(1f).height(258.dp)) { onOpen(Routes.GALLERY) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorTile("Videos", "No videos yet", Icons.Outlined.PlayArrow, Prism.Pink, Modifier.height(124.dp)) { onOpen(Routes.VIDEOS) }
                ColorTile("Audio", "No audio yet", Icons.Outlined.GraphicEq, Prism.Mint, Modifier.height(124.dp)) { onOpen(Routes.AUDIO) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .prismBlock(Prism.Paper)
                .clickable(role = Role.Button, onClick = { onOpen(Routes.DOCS) })
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).prismBlock(Prism.Orange, 16), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = Prism.Ink)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Documents", style = PrismType.heading, color = Prism.Ink)
                Text("NO DOCUMENTS YET", style = PrismType.label, color = Prism.Ink)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Prism.Ink)
        }
    }
}

@Composable
private fun StorageCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .prismBlock(Prism.Sun)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Canvas(Modifier.size(120.dp).align(Alignment.TopEnd).padding(start = 20.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
            drawCircle(Prism.Ink, radius = size.minDimension / 2, style = stroke)
            drawCircle(Prism.Ink, radius = size.minDimension / 3, style = stroke)
            drawCircle(Prism.Ink, radius = size.minDimension / 6)
        }
        Column {
            Text("SEALED ON THIS PHONE", style = PrismType.label, color = Prism.Ink)
            Spacer(Modifier.height(8.dp))
            Text("0 B", style = PrismType.stat, color = Prism.Ink)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Prism.Ink.copy(alpha = 0.18f)))
            Spacer(Modifier.height(8.dp))
            Text("Vault is empty · import files to begin", style = PrismType.body.copy(fontSize = PrismType.label.fontSize), color = Prism.Ink)
        }
    }
}

@Composable
private fun PhotosTile(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .prismBlock(Prism.Cobalt)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(Prism.Sun, radius = w * 0.22f, center = Offset(w * 0.69f, h * 0.24f))
            val hills = Path().apply {
                moveTo(0f, h * 0.66f)
                lineTo(w * 0.33f, h * 0.46f)
                lineTo(w * 0.58f, h * 0.58f)
                lineTo(w, h * 0.37f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(hills, Color(0xFF2440D6))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("Photos", style = PrismType.heading.copy(fontSize = PrismType.title.fontSize), color = Prism.Paper)
            Text("NO PHOTOS YET", style = PrismType.label, color = Prism.Paper)
        }
    }
}

@Composable
private fun ColorTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .prismBlock(color)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = Prism.Ink, modifier = Modifier.size(28.dp))
        Column {
            Text(title, style = PrismType.heading, color = Prism.Ink)
            Text(subtitle.uppercase(), style = PrismType.label, color = Prism.Ink)
        }
    }
}
