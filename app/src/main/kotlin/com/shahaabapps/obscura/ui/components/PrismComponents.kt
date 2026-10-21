package com.shahaabapps.obscura.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/** Full-width pill button, the primary action on most screens. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Prism.Sun,
    content: Color = Prism.Ink,
    trailing: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 26.dp, end = if (trailing != null) 8.dp else 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (trailing != null) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            Text(text, style = PrismType.button)
            if (trailing != null) {
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(Prism.Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(trailing, contentDescription = null, tint = container)
                }
            }
        }
    }
}

/** Outlined secondary pill. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Prism.Paper,
        border = BorderStroke(1.5.dp, Prism.Line),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight))
        }
    }
}

/** 44dp round icon button with an accessible label. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Transparent,
    content: Color = Prism.Paper,
    outlined: Boolean = container == Color.Transparent,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (outlined) BorderStroke(1.5.dp, Prism.Line) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}

/** Small mono status chip, e.g. the green OFFLINE badge. */
@Composable
fun StatusChip(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Prism.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = PrismType.label, color = dot)
    }
}

/** Rounded block used for bento tiles and cards. */
fun Modifier.prismBlock(color: Color, radius: Int = 28) =
    clip(RoundedCornerShape(radius.dp)).background(color)

/** Hairline outline for dark cards. */
fun Modifier.hairline(radius: Int = 24) = border(1.dp, Prism.Line, RoundedCornerShape(radius.dp))
