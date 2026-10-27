package com.shahaabapps.obscura.ui.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.shahaabapps.obscura.export.ExportManager
import com.shahaabapps.obscura.export.ExportProgress
import com.shahaabapps.obscura.export.ShareGrants
import com.shahaabapps.obscura.export.shareIntent
import com.shahaabapps.obscura.ui.components.MAX_ANIMATED_BYTES
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.BULK_CHUNK
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import com.shahaabapps.obscura.vault.whileOpen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
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

/** Remembers how the gallery is sorted and laid out, shared by the grid, viewer and reel. */
@Singleton
class GallerySettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        GallerySettings(
            sortBy = runCatching { SortBy.valueOf(prefs.getString("sortBy", null) ?: "DATE") }.getOrDefault(SortBy.DATE),
            descending = prefs.getBoolean("descending", true),
            groupByMonth = prefs.getBoolean("groupByMonth", true),
            columns = prefs.getInt("columns", 3).coerceIn(ColumnRange.MIN, ColumnRange.MAX),
            filter = runCatching { GalleryFilter.valueOf(prefs.getString("filter", null) ?: "ALL") }.getOrDefault(GalleryFilter.ALL),
            // Nothing stored means no limit, so an install from before this shows every length.
            // A stored value of the wrong type, or outside what the sheet can produce, falls
            // back to no limit rather than taking the gallery down with it: this is built on
            // the way to the first screen, where there would be no way back.
            duration = runCatching { prefs.getInt("durationSeconds", 0) }.getOrDefault(0)
                .takeIf { it in 1..DurationLimit.MAX_SECONDS }
                ?.let { DurationLimit(it, runCatching { prefs.getBoolean("durationLonger", false) }.getOrDefault(false)) },
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
            .putInt("durationSeconds", next.duration?.seconds ?: 0)
            .putBoolean("durationLonger", next.duration?.longer ?: false)
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
    private val exportManager: ExportManager,
    private val shareGrants: ShareGrants,
    private val autoLock: AutoLock,
) : ViewModel() {

    val settings: StateFlow<GallerySettings> = settingsStore.settings

    @OptIn(ExperimentalCoroutinesApi::class)
    private val allFiles = session.state.flatMapLatest { state ->
        if (state is VaultState.Unlocked) {
            state.vault.database.files().observeAll().whileOpen(emptyList())
        } else {
            flowOf(emptyList())
        }
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

    /** Feeds the reel's video playback, or null when the vault is locked. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun dataSourceFactory(): androidx.media3.datasource.DataSource.Factory? {
        val vault = session.openVault ?: return null
        // Looked up afresh each time, and only while this very vault is still open.
        return com.shahaabapps.obscura.media.SealedDataSource.Factory(vault.keys.fileCipher, vault.files) { id ->
            if (session.openVault !== vault) null else state.value.flat.firstOrNull { it.id == id }
        }
    }

    /** The whole file decrypted in memory, for formats that can't be streamed (GIF). */
    suspend fun bytes(file: VaultFile): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Capped, and never fatal: too big or unreadable falls back to the still preview.
        if (file.sizeBytes > MAX_ANIMATED_BYTES) return@withContext null
        runCatching {
            // Read into an array of the known size: growing one would peak at twice the file.
            val out = ByteArray(file.sizeBytes.toInt())
            session.openVault?.files?.open(file)?.use { input ->
                var read = 0
                while (read < out.size) {
                    val n = input.read(out, read, out.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == out.size) out else null
            }
        }.getOrNull()
    }

    suspend fun display(file: VaultFile, maxSide: Int): Bitmap? = session.openVault?.thumbnails?.display(file, maxSide)

    fun setFavorite(file: VaultFile, favorite: Boolean) {
        viewModelScope.launch { session.openVault?.database?.files()?.setFavorite(file.id, favorite) }
    }

    // ---- Multi-select ----

    private val _selecting = MutableStateFlow(false)
    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    /** Selection mode is on (even with nothing picked yet, after tapping Select). */
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    fun startSelecting() {
        _selecting.value = true
    }

    fun endSelecting() {
        _selecting.value = false
        _selection.value = emptySet()
    }

    fun setSelection(ids: Set<String>) {
        _selection.value = ids
        if (ids.isNotEmpty()) _selecting.value = true
    }

    fun toggle(id: String) = setSelection(SelectionLogic.toggle(_selection.value, id))

    /** Selects everything visible, or clears the selection when it already is everything. */
    fun selectAll() {
        val all = state.value.flat.map { it.id }.toSet()
        setSelection(if (_selection.value.containsAll(all)) emptySet() else all)
    }

    fun selectedFiles(): List<VaultFile> = _selection.value.let { ids -> state.value.flat.filter { it.id in ids } }

    /** Favourites them all, or un-favourites them when every one already is. */
    fun favoriteSelected() {
        val files = selectedFiles()
        val favorite = !files.all { it.favorite }
        viewModelScope.launch {
            val dao = session.openVault?.database?.files() ?: return@launch
            files.map { it.id }.chunked(BULK_CHUNK).forEach { dao.setFavoriteAll(it, favorite) }
        }
    }

    fun shredSelected() {
        val files = selectedFiles()
        endSelecting()
        viewModelScope.launch {
            val vault = session.openVault ?: return@launch
            // One transaction for the index, so shredding thousands of files stays quick.
            vault.database.withTransaction { files.forEach { vault.shred(it) } }
        }
    }

    // ---- Export and share ----

    val export: StateFlow<ExportProgress> = exportManager.progress

    private var picking = false

    /** Keeps the vault open while the system picker is on screen. */
    fun beginPicking() {
        if (!picking) {
            picking = true
            autoLock.hold()
        }
    }

    private fun endPicking() {
        if (picking) {
            picking = false
            autoLock.release()
        }
    }

    /** Saves decrypted copies of [files] into the picked folder. */
    fun exportTo(tree: Uri?, files: List<VaultFile>) {
        endPicking()
        if (tree != null && exportManager.exportToFolder(tree, files)) endSelecting()
    }

    /**
     * Saves one decrypted copy to the document the user named. The picker's hold always ends;
     * if the file is gone (shredded meanwhile), the empty document the picker made is removed.
     */
    fun exportFile(target: Uri?, file: VaultFile?) {
        endPicking()
        if (target == null) return
        if (file == null || !exportManager.exportFile(target, file)) exportManager.discard(target)
    }

    fun cancelExport() = exportManager.cancel()

    fun dismissExport() = exportManager.reset()

    /** A chooser that hands [files] to one app through links that die when the vault locks. */
    fun share(files: List<VaultFile>): Intent = shareIntent(shareGrants.grant(files), files)

    override fun onCleared() = endPicking()

    fun shred(file: VaultFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            session.openVault?.shred(file)
            onDone()
        }
    }
}
