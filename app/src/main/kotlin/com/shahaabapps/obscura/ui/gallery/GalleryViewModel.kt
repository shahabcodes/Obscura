package com.shahaabapps.obscura.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers how the gallery is sorted and laid out, shared by the grid, viewer and reel. */
@Singleton
class GallerySettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        GallerySettings(
            sortBy = runCatching { SortBy.valueOf(prefs.getString("sortBy", null) ?: "DATE") }.getOrDefault(SortBy.DATE),
            descending = prefs.getBoolean("descending", true),
            groupByMonth = prefs.getBoolean("groupByMonth", true),
            columns = prefs.getInt("columns", 3).coerceIn(3, 5),
            filter = runCatching { GalleryFilter.valueOf(prefs.getString("filter", null) ?: "ALL") }.getOrDefault(GalleryFilter.ALL),
        ),
    )
    val settings: StateFlow<GallerySettings> = _settings.asStateFlow()

    fun update(transform: (GallerySettings) -> GallerySettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString("sortBy", next.sortBy.name)
            .putBoolean("descending", next.descending)
            .putBoolean("groupByMonth", next.groupByMonth)
            .putInt("columns", next.columns)
            .putString("filter", next.filter.name)
            .apply()
    }
}

data class GalleryUiState(
    val loading: Boolean = true,
    val entries: List<GalleryEntry> = emptyList(),
    val flat: List<VaultFile> = emptyList(),
    val totalMedia: Int = 0,
)

/** Shared by the gallery grid, the photo viewer and reel mode. */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val session: VaultSession,
    private val settingsStore: GallerySettingsStore,
) : ViewModel() {

    val settings: StateFlow<GallerySettings> = settingsStore.settings

    @OptIn(ExperimentalCoroutinesApi::class)
    private val allFiles = session.state.flatMapLatest { state ->
        if (state is VaultState.Unlocked) state.vault.database.files().observeAll() else flowOf(emptyList())
    }

    val state: StateFlow<GalleryUiState> = combine(allFiles, settingsStore.settings) { files, settings ->
        val entries = GalleryLogic.build(files, settings)
        GalleryUiState(
            loading = false,
            entries = entries,
            flat = GalleryLogic.flat(entries),
            totalMedia = GalleryLogic.media(files, GalleryFilter.ALL).size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    fun update(transform: (GallerySettings) -> GallerySettings) = settingsStore.update(transform)

    suspend fun thumbnail(file: VaultFile): Bitmap? = session.openVault?.thumbnails?.thumbnail(file)

    suspend fun display(file: VaultFile, maxSide: Int): Bitmap? = session.openVault?.thumbnails?.display(file, maxSide)

    fun setFavorite(file: VaultFile, favorite: Boolean) {
        viewModelScope.launch { session.openVault?.database?.files()?.setFavorite(file.id, favorite) }
    }

    fun shred(file: VaultFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            session.openVault?.shred(file)
            onDone()
        }
    }
}
