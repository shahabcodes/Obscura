package com.shahaabapps.obscura.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shahaabapps.obscura.BuildConfig
import com.shahaabapps.obscura.ui.screens.HomeScreen
import com.shahaabapps.obscura.ui.screens.PlaceholderScreen
import com.shahaabapps.obscura.ui.screens.WelcomeScreen
import com.shahaabapps.obscura.ui.theme.Prism

object Routes {
    const val WELCOME = "welcome"
    const val SETUP = "setup"
    const val RESTORE = "restore"
    const val HOME = "home"
    const val GALLERY = "gallery"
    const val VIDEOS = "videos"
    const val AUDIO = "audio"
    const val DOCS = "docs"
    const val SEARCH = "search"
    const val IMPORT = "import"
    const val ALBUMS = "albums"
    const val SECURITY = "security"

    /** Destinations that show the floating bottom bar. */
    val tabs = setOf(HOME, SEARCH, IMPORT, ALBUMS, SECURITY)
}

@Composable
fun ObscuraNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Box(Modifier.fillMaxSize().background(Prism.Ink)) {
        NavHost(navController = navController, startDestination = Routes.WELCOME) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onCreate = { navController.navigate(Routes.SETUP) },
                    onRestore = { navController.navigate(Routes.RESTORE) },
                    // Debug builds can look around the UI before setup and unlock exist (phase 3).
                    onPreview = if (BuildConfig.DEBUG) ({ navController.navigateToTab(Routes.HOME) }) else null,
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onLock = {
                        navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                    },
                    onOpen = { route -> navController.navigate(route) },
                )
            }
            placeholder(navController, Routes.SETUP, "Create passphrase", 3, "Passphrase strength meter, recovery key and PIN setup.")
            placeholder(navController, Routes.RESTORE, "Restore", 9, "Bring a vault back from an encrypted USB backup.")
            placeholder(navController, Routes.GALLERY, "Gallery", 5, "Month groups, sort by date, size or type, and Reel mode.")
            placeholder(navController, Routes.VIDEOS, "Videos", 5, "Decrypt-while-playing video with seeking.")
            placeholder(navController, Routes.AUDIO, "Audio", 5, "Waveform player and queue.")
            placeholder(navController, Routes.DOCS, "Documents", 5, "PDFs and documents rendered in memory.")
            placeholder(navController, Routes.SEARCH, "Search", 6, "Instant search over the encrypted index.", showBack = false)
            placeholder(navController, Routes.IMPORT, "Import", 4, "Pick files, encrypt them, remove the originals.", showBack = false)
            placeholder(navController, Routes.ALBUMS, "Albums", 6, "Albums, including ones behind an extra PIN.", showBack = false)
            placeholder(navController, Routes.SECURITY, "Security", 8, "Auto-lock, panic lock, decoy vault and backups.", showBack = false)
        }

        if (current in Routes.tabs) {
            PrismBottomBar(
                current = current,
                onSelect = { navController.navigateToTab(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.placeholder(
    navController: NavHostController,
    route: String,
    title: String,
    phase: Int,
    description: String,
    showBack: Boolean = true,
) {
    composable(route) {
        PlaceholderScreen(
            title = title,
            phase = phase,
            description = description,
            onBack = if (showBack) ({ navController.popBackStack() }) else null,
        )
    }
}

/** Switches tabs without stacking duplicates, keeping Home as the root. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
