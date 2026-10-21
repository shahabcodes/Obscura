package com.shahaabapps.obscura.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shahaabapps.obscura.ui.theme.Prism

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Dashboard),
    Tab(Routes.SEARCH, "Search", Icons.Outlined.Search),
    Tab(Routes.IMPORT, "Import files", Icons.Outlined.Add),
    Tab(Routes.ALBUMS, "Albums", Icons.Outlined.Folder),
    Tab(Routes.SECURITY, "Security", Icons.Outlined.Shield),
)

/** The floating paper-white pill from the Prism prototype; the active tab sits in a black disc. */
@Composable
fun PrismBottomBar(current: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .fillMaxWidth()
            .height(72.dp)
            .shadow(16.dp, CircleShape)
            .clip(CircleShape)
            .background(Prism.Paper)
            .selectableGroup()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == current
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (selected) Prism.Ink else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) Prism.Sun else Prism.Ink,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
