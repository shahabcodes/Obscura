package com.shahaabapps.obscura.ui.guide

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.components.RoundIconButton
import com.shahaabapps.obscura.ui.components.prismBlock
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.theme.PrismType

/** One control or gesture, and what it does. */
private data class Step(val icon: ImageVector?, val name: String, val what: String)

/** A part of the app, and the controls in it. */
private data class Part(val title: String, val blurb: String, val tint: Color, val steps: List<Step>)

private val Tour = listOf(
    Part(
        title = "Getting things in",
        blurb = "Everything is encrypted as it is read. Nothing is ever uploaded: the app has no way to reach the internet at all.",
        tint = Prism.Mint,
        steps = listOf(
            Step(Icons.Outlined.Folder, "Import files", "Pick photos and videos, any other files, or a whole folder or USB drive — subfolders and all."),
            Step(null, "Skip duplicates", "Leaves a file alone if the same one is already sealed, so importing twice costs nothing."),
            Step(null, "Remove location from photos", "Strips GPS out of JPEGs as they are sealed."),
            Step(null, "Remove the originals", "Offered once everything is sealed. Until you say yes, the readable copies are still on your phone — importing copies, it does not move."),
        ),
    ),
    Part(
        title = "Finding things",
        blurb = "The gallery holds photos, videos and GIFs. Audio and documents have their own places on the home screen.",
        tint = Prism.Sun,
        steps = listOf(
            Step(null, "Pinch the grid", "Spread two fingers for fewer, bigger tiles; pinch for more. Two to six across."),
            Step(Icons.Outlined.CalendarMonth, "Jump to a month", "Every month you have files from, under its year. Follows whichever filter you are on."),
            Step(Icons.AutoMirrored.Outlined.Sort, "Sort", "By date, size or type; tap again to turn the order around."),
            Step(null, "Filters", "All, Photos, Videos, GIFs, Screens, Favourites — across the top of the gallery."),
            Step(null, "Video length", "On the Videos filter, set a length and keep the clips under or over it."),
            Step(Icons.Outlined.Search, "Search", "By name, from the home screen."),
            Step(Icons.Outlined.ContentCopy, "Duplicates", "Finds files sealed twice, and look-alike photos, so you can free the space."),
        ),
    ),
    Part(
        title = "Looking at something",
        blurb = "Tap any tile to open it. Swipe sideways for the next one.",
        tint = Prism.Pink,
        steps = listOf(
            Step(null, "Tap the picture", "Hides everything but the picture. Tap again to bring the controls back."),
            Step(Icons.Outlined.ZoomIn, "Pinch to zoom", "In photos and in videos. Drag to move around; double-tap to go back to normal."),
            Step(Icons.Outlined.Info, "Info", "Size, date, dimensions and where it came from."),
            Step(Icons.Outlined.Share, "Share", "Hands a temporary, read-only link to another app. It dies the moment the vault locks."),
            Step(Icons.Outlined.Save, "Save copy", "Writes one readable copy wherever you choose."),
            Step(Icons.Outlined.DeleteForever, "Shred", "Destroys the file for good. There is no bin to recover it from."),
        ),
    ),
    Part(
        title = "Videos and GIFs",
        blurb = "Video is decrypted as it plays, a piece at a time — never written out to be played.",
        tint = Prism.Mint,
        steps = listOf(
            Step(Icons.Filled.PlayArrow, "Play, seek, skip", "Drag the bar, or jump ten seconds either way. Speed runs from 0.5× to 2×."),
            Step(null, "Next video", "Swipe the picture sideways for the next video in the gallery, or back to the one before. It follows the gallery's order and filters."),
            Step(Icons.Outlined.Gif, "Make a GIF", "Pause where you want it to start, then pick 2, 3 or 5 seconds. The GIF is sealed back into the vault."),
            Step(null, "Reel mode", "Plays through everything by itself, three, five or ten seconds each. Swipe up and down to move, tap to clear the screen."),
        ),
    ),
    Part(
        title = "Getting things out",
        blurb = "Copies leave the vault's protection. Once they are out, anyone holding that folder or drive can read them.",
        tint = Prism.Orange,
        steps = listOf(
            Step(Icons.Outlined.CheckCircle, "Select", "Press and drag across tiles to pick several, then export, favourite, album or shred them together."),
            Step(Icons.Outlined.PhotoLibrary, "Export everything", "In Security. Writes a readable copy of the whole vault into a folder you choose, with a list beside it holding each file's date, album and favourite. It keeps going while you do something else, and running it again carries on where it stopped."),
            Step(null, "Backup", "A copy that stays encrypted, for a drive or folder. Only a backup can be restored into the app again."),
        ),
    ),
    Part(
        title = "Keeping it shut",
        blurb = "The vault locks itself the moment the screen goes off, and the keys leave memory when it does.",
        tint = Prism.Mint,
        steps = listOf(
            Step(Icons.Outlined.Lock, "Lock now", "Top left of the home screen, any time."),
            Step(null, "Auto-lock", "How long after leaving the app it locks: instantly, 30 seconds, a minute or five."),
            Step(null, "Panic lock", "Lay the phone face-down and it locks at once."),
            Step(Icons.Outlined.Shield, "Decoy vault", "A second passphrase that opens a separate, harmless vault. Anyone made to watch you unlock sees an ordinary one."),
            Step(null, "Wipe after 10 wrong attempts", "Off unless you turn it on. When on, it destroys the vault for good."),
            Step(null, "Recovery key", "The only way back in if you forget your passphrase. Save it somewhere away from this phone."),
        ),
    ),
)

/**
 * A walk through the app: every control, what it does, and the gestures that are not obvious.
 *
 * Obscura hides its controls deliberately — a tap clears the screen, pinching changes the grid —
 * which is pleasant once you know and invisible until you do. This is where it is written down.
 */
@Composable
fun GuideScreen(onBack: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prism.Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        Text(
            "How Obscura works",
            style = PrismType.title,
            color = Prism.Paper,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Text(
            "Everything here happens on this phone. There is no account, no server and no internet " +
                "permission, so nothing can leave by accident.",
            style = PrismType.body,
            color = Prism.Muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Tour.forEach { part -> PartCard(part) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PartCard(part: Part) {
    Column(
        Modifier.fillMaxWidth().prismBlock(Prism.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(part.title, style = PrismType.heading, color = part.tint)
        Text(part.blurb, style = PrismType.body, color = Prism.Muted)
        part.steps.forEach { step -> StepRow(step, part.tint) }
    }
}

@Composable
private fun StepRow(step: Step, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Prism.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (step.icon != null) {
                Icon(step.icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            } else {
                // Gestures and settings have no button of their own to show.
                Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(step.name, style = PrismType.body.copy(fontWeight = PrismType.button.fontWeight), color = Prism.Paper)
            Text(step.what, style = PrismType.body, color = Prism.Muted)
        }
    }
}
