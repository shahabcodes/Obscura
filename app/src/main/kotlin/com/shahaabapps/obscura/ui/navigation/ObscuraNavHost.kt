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
import com.shahaabapps.obscura.duplicates.DuplicatesScreen
import com.shahaabapps.obscura.media.AudioScreen
import com.shahaabapps.obscura.media.DocumentScreen
import com.shahaabapps.obscura.media.DocumentsScreen
import com.shahaabapps.obscura.media.VideoPlayerScreen
import com.shahaabapps.obscura.ui.backup.BackupScreen
import com.shahaabapps.obscura.ui.backup.RestoreScreen
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.gallery.GalleryScreen
import com.shahaabapps.obscura.ui.gallery.GalleryViewModel
import com.shahaabapps.obscura.ui.gallery.ReelScreen
import com.shahaabapps.obscura.ui.gallery.ViewerScreen
import com.shahaabapps.obscura.ui.importer.ImportScreen
import com.shahaabapps.obscura.ui.organize.AlbumScreen
import com.shahaabapps.obscura.ui.organize.AlbumsScreen
import com.shahaabapps.obscura.ui.organize.SearchScreen
import com.shahaabapps.obscura.ui.recovery.NewRecoveryKeyScreen
import com.shahaabapps.obscura.ui.screens.HomeScreen
import com.shahaabapps.obscura.ui.screens.HomeViewModel
import com.shahaabapps.obscura.ui.screens.WelcomeScreen
import com.shahaabapps.obscura.ui.security.LicencesScreen
import com.shahaabapps.obscura.ui.security.SecurityScreen
import com.shahaabapps.obscura.ui.setup.BiometricSetupScreen
import com.shahaabapps.obscura.ui.setup.PassphraseSetupScreen
import com.shahaabapps.obscura.ui.setup.PinSetupScreen
import com.shahaabapps.obscura.ui.setup.RecoveryKeyScreen
import com.shahaabapps.obscura.ui.setup.SetupViewModel
import com.shahaabapps.obscura.ui.theme.Prism
import com.shahaabapps.obscura.ui.unlock.PassphraseUnlockScreen
import com.shahaabapps.obscura.ui.unlock.RecoveryUnlockScreen
import com.shahaabapps.obscura.ui.unlock.UnlockScreen
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
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
    const val VIEWER = "viewer"
    const val REEL = "reel"
    const val VIDEO = "video"
    const val DOCUMENT = "document"
    const val ALBUM = "album"
    const val DUPLICATES = "duplicates"
    const val VIDEOS = "videos"
    const val GIFS = "gifs"
    const val AUDIO = "audio"
    const val DOCS = "docs"
    const val SEARCH = "search"
    const val IMPORT = "import"
    const val ALBUMS = "albums"
    const val SECURITY = "security"
    const val BACKUP = "backup"
    const val LICENCES = "licences"
    const val RECOVERY_KEY = "security/recovery"

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
        // Wipe-after-10 destroyed the vault: start over from the welcome screen.
        if (vaultState is VaultState.NoVault && current != null && current != Routes.WELCOME && !current.startsWith("setup") && current != Routes.RESTORE) {
            navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
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
                val gallery: GalleryViewModel = hiltViewModel()
                val state by home.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onLock = session::lock,
                    onOpen = { route ->
                        // The Photos tile opens the whole gallery, whatever filter was used last.
                        if (route == Routes.GALLERY) gallery.update { it.copy(filter = GalleryFilter.ALL) }
                        navController.navigate(route)
                    },
                )
            }
            composable(Routes.RESTORE) {
                RestoreScreen(
                    onBack = { navController.popBackStack() },
                    onRestored = { navController.navigate(Routes.UNLOCK) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable(Routes.BACKUP) { BackupScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.GALLERY) {
                GalleryScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate("${Routes.VIEWER}/$id") },
                    onPlay = { id -> navController.navigate("${Routes.VIDEO}/$id") },
                    onReel = { id -> navController.navigate("${Routes.REEL}/$id") },
                    onImport = { navController.navigateToTab(Routes.IMPORT) },
                    onDuplicates = { navController.navigate(Routes.DUPLICATES) },
                )
            }
            composable(Routes.DUPLICATES) { DuplicatesScreen(onBack = { navController.popBackStack() }) }
            composable("${Routes.VIEWER}/{id}") { entry ->
                ViewerScreen(
                    viewModel = hiltViewModel(),
                    startId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onReel = { id -> navController.navigate("${Routes.REEL}/$id") },
                    onPlay = { id -> navController.navigate("${Routes.VIDEO}/$id") },
                )
            }
            composable("${Routes.VIDEO}/{id}") { entry ->
                VideoPlayerScreen(hiltViewModel(), entry.arguments?.getString("id").orEmpty(), onBack = { navController.popBackStack() })
            }
            composable(Routes.AUDIO) {
                AudioScreen(hiltViewModel(), onBack = { navController.popBackStack() }, onImport = { navController.navigateToTab(Routes.IMPORT) })
            }
            composable(Routes.DOCS) {
                DocumentsScreen(
                    hiltViewModel(),
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate("${Routes.DOCUMENT}/$id") },
                    onImport = { navController.navigateToTab(Routes.IMPORT) },
                )
            }
            composable("${Routes.DOCUMENT}/{id}") { entry ->
                DocumentScreen(hiltViewModel(), entry.arguments?.getString("id").orEmpty(), onBack = { navController.popBackStack() })
            }
            composable("${Routes.REEL}/{id}") { entry ->
                ReelScreen(
                    viewModel = hiltViewModel(),
                    startId = entry.arguments?.getString("id").orEmpty(),
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.VIDEOS) {
                // Opens the gallery filtered to videos.
                val gallery: GalleryViewModel = hiltViewModel()
                LaunchedEffect(Unit) {
                    gallery.update { it.copy(filter = GalleryFilter.VIDEOS) }
                    navController.navigate(Routes.GALLERY) { popUpTo(Routes.VIDEOS) { inclusive = true } }
                }
            }
            composable(Routes.GIFS) {
                val gallery: GalleryViewModel = hiltViewModel()
                LaunchedEffect(Unit) {
                    gallery.update { it.copy(filter = GalleryFilter.GIFS) }
                    navController.navigate(Routes.GALLERY) { popUpTo(Routes.GIFS) { inclusive = true } }
                }
            }
            composable(Routes.SEARCH) {
                val gallery: GalleryViewModel = hiltViewModel()
                SearchScreen(onOpen = { file -> navController.openFile(file, gallery) })
            }
            composable(Routes.ALBUMS) {
                AlbumsScreen(onOpenAlbum = { id -> navController.navigate("${Routes.ALBUM}/$id") })
            }
            composable("${Routes.ALBUM}/{id}") { entry ->
                val gallery: GalleryViewModel = hiltViewModel()
                AlbumScreen(
                    id = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenFile = { file -> navController.openFile(file, gallery) },
                )
            }
            composable(Routes.SECURITY) {
                SecurityScreen(
                    onBackup = { navController.navigate(Routes.BACKUP) },
                    onLicences = { navController.navigate(Routes.LICENCES) },
                    onRecoveryKey = { navController.navigate(Routes.RECOVERY_KEY) },
                )
            }
            composable(Routes.RECOVERY_KEY) { NewRecoveryKeyScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.LICENCES) { LicencesScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.IMPORT) { ImportScreen() }
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

/** Opens a file in the right screen for its type (from search results or an album). */
private fun NavHostController.openFile(file: VaultFile, gallery: GalleryViewModel) {
    when (file.kind) {
        FileKind.PHOTO, FileKind.GIF -> {
            // The viewer pages through the gallery list, so make sure it isn't filtered away.
            gallery.update { it.copy(filter = GalleryFilter.ALL) }
            navigate("${Routes.VIEWER}/${file.id}")
        }
        FileKind.VIDEO -> navigate("${Routes.VIDEO}/${file.id}")
        FileKind.AUDIO -> navigate(Routes.AUDIO)
        FileKind.DOCUMENT, FileKind.OTHER -> navigate("${Routes.DOCUMENT}/${file.id}")
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
