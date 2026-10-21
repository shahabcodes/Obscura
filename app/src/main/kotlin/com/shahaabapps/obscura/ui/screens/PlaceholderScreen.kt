package com.shahaabapps.obscura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/** Stand-in for screens scheduled in later build phases. */
@Composable
fun PlaceholderScreen(title: String, phase: Int, description: String, onBack: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .padding(20.dp),
    ) {
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.height(28.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Text("COMING IN PHASE $phase", style = PrismType.label, color = Prism.Sun)
        Spacer(Modifier.height(8.dp))
        Text(title, style = PrismType.title, color = Prism.Paper)
        Spacer(Modifier.height(12.dp))
        Text(description, style = PrismType.body, color = Prism.Muted)
    }
}
