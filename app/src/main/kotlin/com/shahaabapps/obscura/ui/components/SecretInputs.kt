package com.shahaabapps.obscura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/**
 * Pill-shaped secret input. The keyboard is told this is a password field and not to learn
 * from it (no suggestions, no autocorrect), so typed secrets stay out of the keyboard's memory.
 */
@Composable
fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    accent: Color = Prism.Line,
    revealable: Boolean = true,
    monospace: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Password,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label.uppercase(), style = PrismType.label, color = Prism.Muted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Prism.Surface)
                .border(1.5.dp, accent, RoundedCornerShape(22.dp))
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = if (monospace) PrismType.label.copy(fontSize = PrismType.body.fontSize) else PrismType.body, color = Prism.Muted.copy(alpha = 0.6f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = (if (monospace) PrismType.label.copy(fontSize = PrismType.body.fontSize, letterSpacing = PrismType.label.letterSpacing) else PrismType.body).copy(color = Prism.Paper),
                    cursorBrush = SolidColor(Prism.Sun),
                    visualTransformation = if (revealable && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (visible) KeyboardType.Ascii else keyboardType,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = imeAction,
                    ),
                    keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(label)
                        .semantics { contentDescription = label },
                )
            }
            if (revealable) {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Hide $label" else "Show $label",
                        tint = Prism.Muted,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** Row of PIN dots; filled ones take the accent colour. */
@Composable
fun PinDots(filled: Int, total: Int = 6, accent: Color = Prism.Sun, error: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.semantics { contentDescription = "$filled of $total digits entered" },
    ) {
        repeat(total) { i ->
            val on = i < filled
            val color = if (error) Prism.Orange else accent
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (on) color else Color.Transparent)
                    .border(2.dp, if (on || error) color else Prism.Line, CircleShape),
            )
        }
    }
}

/** 3×4 keypad: digits, an optional fingerprint key and backspace. */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onBiometric: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val rows = listOf("123", "456", "789")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { digit -> PadKey(Modifier.weight(1f), digit.toString(), enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBiometric != null) {
                PadIcon(Modifier.weight(1f), "Unlock with fingerprint", enabled, Prism.Sun, onBiometric) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            PadKey(Modifier.weight(1f), "0", enabled) { onDigit('0') }
            PadIcon(Modifier.weight(1f), "Delete digit", enabled, Prism.Paper, onDelete) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun PadKey(modifier: Modifier, label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(68.dp),
        shape = CircleShape,
        color = Prism.Surface,
        contentColor = Prism.Paper,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = PrismType.heading.copy(fontSize = PrismType.title.fontSize, fontWeight = PrismType.body.fontWeight))
        }
    }
}

@Composable
private fun PadIcon(
    modifier: Modifier,
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(68.dp).semantics { contentDescription = label },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}
