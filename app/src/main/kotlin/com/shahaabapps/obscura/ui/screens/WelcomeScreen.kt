package com.shahaabapps.obscura.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.components.GhostButton
import com.shahaabapps.obscura.ui.components.PillButton
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

@Composable
fun WelcomeScreen(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .clip(RoundedCornerShape(32.dp))
                .background(Prism.Cobalt),
        ) {
            BauhausArt(Modifier.fillMaxSize())
            Text(
                "OBSCURA / V0.1 / OFFLINE",
                style = PrismType.label,
                color = Prism.Paper,
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            buildAnnotatedString {
                append("Your archive.\n")
                withStyle(SpanStyle(color = Prism.Sun)) { append("Off the grid.") }
            },
            style = PrismType.hero,
            color = Prism.Paper,
        )

        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Point("01", Prism.Sun, "No internet permission. Nothing ever leaves this phone.")
            Point("02", Prism.Pink, "Every file sealed with AES-256 before it is stored.")
            Point("03", Prism.Mint, "Invisible to galleries, file managers and USB.")
        }

        Spacer(Modifier.height(24.dp))
        PillButton("Create my vault", onClick = onCreate, trailing = Icons.AutoMirrored.Outlined.ArrowForward)
        Spacer(Modifier.height(10.dp))
        GhostButton("Restore from a backup drive", onClick = onRestore)
        if (onPreview != null) {
            TextButton(onClick = onPreview, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Preview app UI (debug build)", style = PrismType.label, color = Prism.Muted)
            }
        }
    }
}

@Composable
private fun Point(number: String, color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(number, style = PrismType.label, color = color, modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = PrismType.body, color = Prism.Paper.copy(alpha = 0.85f))
    }
}

/** The welcome card's Bauhaus composition: sun disc, pink quarter circle, tilted black tile. */
@Composable
private fun BauhausArt(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val unit = minOf(w, h)

        drawCircle(Prism.Sun, radius = unit * 0.26f, center = Offset(w * 0.71f, h * 0.31f))
        drawArc(
            color = Prism.Pink,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = true,
            topLeft = Offset(-unit * 0.46f, h - unit * 0.46f),
            size = Size(unit * 0.92f, unit * 0.92f),
        )
        val tile = unit * 0.27f
        val tileCenter = Offset(w * 0.56f, h * 0.72f)
        rotate(14f, pivot = tileCenter) {
            drawRoundRect(
                Prism.Ink,
                topLeft = Offset(tileCenter.x - tile / 2, tileCenter.y - tile / 2),
                size = Size(tile, tile),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            )
        }
        drawCircle(Prism.Mint, radius = tile * 0.21f, center = tileCenter)

        val stroke = 3.dp.toPx()
        listOf(0.42f, 0.34f, 0.26f).forEachIndexed { i, length ->
            val y = 40.dp.toPx() + i * 16.dp.toPx()
            drawLine(Prism.Paper, Offset(30.dp.toPx(), y), Offset(30.dp.toPx() + w * length, y), strokeWidth = stroke)
        }
        drawCircle(Prism.Paper, radius = 14.dp.toPx(), center = Offset(w - 40.dp.toPx(), h - 40.dp.toPx()), style = Stroke(stroke))
    }
}
