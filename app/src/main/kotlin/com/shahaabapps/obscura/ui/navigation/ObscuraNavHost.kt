package com.shahaabapps.obscura.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.shahaabapps.obscura.ui.screens.HomeScreen
import com.shahaabapps.obscura.ui.screens.HomeViewModel
import com.shahaabapps.obscura.ui.screens.PlaceholderScreen
import com.shahaabapps.obscura.ui.screens.WelcomeScreen
import com.shahaabapps.obscura.ui.setup.BiometricSetupScreen
import com.shahaabapps.obscura.ui.setup.PassphraseSetupScreen
import com.shahaabapps.obscura.ui.setup.PinSetupScreen
import com.shahaabapps.obscura.ui.setup.RecoveryKeyScreen
import com.shahaabapps.obscura.ui.setup.SetupViewModel
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.unlock.PassphraseUnlockScreen
import com.shahaabapps.obscura.ui.unlock.RecoveryUnlockScreen
import com.shahaabapps.obscura.ui.unlock.UnlockScreen
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState

object Routes {
    const val WELCOME = "welcome"
    const val SETUP = "setup"
    const val SETUP_PASSPHRASE = "setup/passphrase"
    const val SETUP_RECOVERY = "setup/recovery"
    const val SETUP_PIN = "setup/pin"
    const val SETUP_BIOMETRIC = "setup/biometric"
    const val RESTORE = "restore"
    const val UNLOCK = "unlock"
    const val UNLOCK_PASSPHRASE = "unlock/passphrase"
    const val UNLOCK_RECOVERY = "unlock/recovery"
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

    /** Destinations reachable while the vault is locked. */
    val lockedSafe = setOf(WELCOME, SETUP_PASSPHRASE, RESTORE, UNLOCK, UNLOCK_PASSPHRASE, UNLOCK_RECOVERY)
}

@Composable
fun ObscuraNavHost(
    session: VaultSession,
    navController: NavHostController = rememberNavController(),
) {
    val vaultState by session.state.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val start = remember {
        when (session.state.value) {
            VaultState.NoVault -> Routes.WELCOME
            VaultState.Locked -> Routes.UNLOCK
            is VaultState.Unlocked -> Routes.HOME
        }
    }

    // Whenever the vault locks (auto-lock, screen off, lock button), drop every screen that
    // showed vault content and return to the lock screen.
    LaunchedEffect(vaultState, current) {
        if (vaultState is VaultState.Locked && current != null && current !in Routes.lockedSafe) {
            navController.navigate(Routes.UNLOCK) { popUpTo(0) { inclusive = true } }
        }
    }

    Box(Modifier.fillMaxSize().background(Prism.Ink)) {
        NavHost(navController = navController, startDestination = start) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onCreate = { navController.navigate(Routes.SETUP) },
                    onRestore = { navController.navigate(Routes.RESTORE) },
                )
            }
            setupGraph(navController)
            unlockScreens(navController)
            composable(Routes.HOME) {
                val home: HomeViewModel = hiltViewModel()
                val state by home.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onLock = session::lock,
                    onOpen = { route -> navController.navigate(route) },
                )
            }
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

private fun NavGraphBuilder.setupGraph(navController: NavHostController) {
    navigation(startDestination = Routes.SETUP_PASSPHRASE, route = Routes.SETUP) {
        composable(Routes.SETUP_PASSPHRASE) { entry ->
            PassphraseSetupScreen(
                viewModel = setupViewModel(navController, entry),
                onBack = { navController.popBackStack() },
                onCreated = {
                    navController.navigate(Routes.SETUP_RECOVERY) { popUpTo(Routes.SETUP_PASSPHRASE) { inclusive = true } }
                },
            )
        }
        composable(Routes.SETUP_RECOVERY) { entry ->
            RecoveryKeyScreen(setupViewModel(navController, entry)) { navController.navigate(Routes.SETUP_PIN) }
        }
        composable(Routes.SETUP_PIN) { entry ->
            PinSetupScreen(setupViewModel(navController, entry)) { navController.navigate(Routes.SETUP_BIOMETRIC) }
        }
        composable(Routes.SETUP_BIOMETRIC) { entry ->
            BiometricSetupScreen(setupViewModel(navController, entry)) {
                navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
            }
        }
    }
}

@Composable
private fun setupViewModel(navController: NavHostController, entry: NavBackStackEntry): SetupViewModel {
    val parent = remember(entry) { navController.getBackStackEntry(Routes.SETUP) }
    return hiltViewModel(parent)
}

private fun NavGraphBuilder.unlockScreens(navController: NavHostController) {
    val toHome: () -> Unit = { navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } }
    composable(Routes.UNLOCK) {
        UnlockScreen(
            onUnlocked = toHome,
            onUsePassphrase = { navController.navigate(Routes.UNLOCK_PASSPHRASE) },
            onUseRecovery = { navController.navigate(Routes.UNLOCK_RECOVERY) },
        )
    }
    composable(Routes.UNLOCK_PASSPHRASE) {
        PassphraseUnlockScreen(
            onUnlocked = toHome,
            onBack = { navController.popBackStack() },
            onUseRecovery = { navController.navigate(Routes.UNLOCK_RECOVERY) },
        )
    }
    composable(Routes.UNLOCK_RECOVERY) {
        RecoveryUnlockScreen(onUnlocked = toHome, onBack = { navController.popBackStack() })
    }
}

private fun NavGraphBuilder.placeholder(
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
