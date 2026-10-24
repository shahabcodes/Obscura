package com.shahaabapps.obscura.ui.security

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/** A bundled component and the licence file (in assets/licences) it ships under. */
data class Component(val name: String, val by: String, val licence: String, val file: String)

/** Everything bundled in the APK that isn't Obscura's own code. */
val Components = listOf(
    Component("Space Grotesk", "The Space Grotesk Project Authors", "SIL Open Font License 1.1", "ofl-space-grotesk.txt"),
    Component("JetBrains Mono", "The JetBrains Mono Project Authors", "SIL Open Font License 1.1", "ofl-jetbrains-mono.txt"),
    Component("SQLCipher for Android", "Zetetic LLC", "BSD-style licence", "sqlcipher.txt"),
    Component("Bouncy Castle", "The Legion of the Bouncy Castle Inc.", "MIT licence", "bouncy-castle.txt"),
    Component("Tink", "Google LLC", "Apache License 2.0", "apache-2.0.txt"),
    Component("Lottie for Android", "Airbnb, Inc.", "Apache License 2.0", "apache-2.0.txt"),
    Component("AndroidX, Jetpack Compose, Media3 and Room", "The Android Open Source Project", "Apache License 2.0", "apache-2.0.txt"),
    Component("Hilt and Dagger", "The Dagger Authors", "Apache License 2.0", "apache-2.0.txt"),
    Component("Kotlin and kotlinx.coroutines", "JetBrains s.r.o. and Kotlin contributors", "Apache License 2.0", "apache-2.0.txt"),
)

/**
 * Licence files are wrapped at about 80 columns; on a phone that leaves ragged half-lines.
 * Joins the lines of each paragraph, keeping blank lines, rules and list items as they are.
 */
fun reflow(text: String): String {
    val listItem = Regex("""^\s*(\d+[.)]|[a-z][.)]|\(\w+\)|\*|-)\s""")
    val rule: (String) -> Boolean = { line -> line.isNotBlank() && line.trim().all { it == '-' } }
    return text.replace("\r\n", "\n").split("\n\n").joinToString("\n\n") { paragraph ->
        val out = StringBuilder()
        var previous = ""
        for (line in paragraph.lines()) {
            val keepBreak = out.isEmpty() || rule(line) || rule(previous) || listItem.containsMatchIn(line)
            if (keepBreak) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            } else {
                out.append(' ').append(line.trim())
            }
            previous = line
        }
        out.toString()
    }
}

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().background(Prism.Ink).safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.height(16.dp))
            Text("Open-source licences", style = PrismType.title, color = Prism.Paper, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Obscura is built on these projects. Their licence texts ship inside the app, so this works offline.",
                style = PrismType.body,
                color = Prism.Muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        items(Components, key = { it.name }) { component ->
            val expanded = open == component.name
            val text by remember(component.file, expanded) {
                mutableStateOf(if (expanded) reflow(context.assets.open("licences/${component.file}").bufferedReader().use { it.readText() }) else "")
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .prismBlock(Prism.Surface, 22)
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide licence" else "Show licence") {
                        open = if (expanded) null else component.name
                    }
                    .animateContentSize()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(component.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
                        Text(component.by, style = PrismType.body, color = Prism.Muted)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(component.licence.uppercase(), style = PrismType.label, color = Prism.Sun)
                }
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(text, style = PrismType.label.copy(letterSpacing = PrismType.body.letterSpacing), color = Prism.Paper)
                    }
                }
            }
        }
    }
}
