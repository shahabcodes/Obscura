package com.shahaabapps.obscura.ui.organize

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.vault.Album
import com.shahaabapps.obscura.vault.AlbumSummary
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.FileStore
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import com.shahaabapps.obscura.vault.likePattern
import com.shahaabapps.obscura.vault.whileOpen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SearchKind(val label: String, val kinds: Set<FileKind>?) {
    ALL("All", null),
    PHOTOS("Photos", setOf(FileKind.PHOTO)),
    VIDEOS("Videos", setOf(FileKind.VIDEO)),
    AUDIO("Audio", setOf(FileKind.AUDIO)),
    DOCS("Docs", setOf(FileKind.DOCUMENT, FileKind.OTHER)),
}

data class SearchResults(val query: String = "", val kind: SearchKind = SearchKind.ALL, val files: List<VaultFile> = emptyList(), val searched: Boolean = false)

/** Searches file names inside the encrypted index; nothing leaves the database unencrypted. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {
    val query = MutableStateFlow("")
    val kind = MutableStateFlow(SearchKind.ALL)

    val results: StateFlow<SearchResults> = combine(query.debounce(150), kind, session.state) { q, k, s -> Triple(q, k, s) }
        .flatMapLatest { (q, k, s) ->
            val vault = (s as? VaultState.Unlocked)?.vault
            if (vault == null || q.isBlank()) flowOf(SearchResults(q, k))
            else vault.database.files().search(likePattern(q)).map { files ->
                SearchResults(q, k, files.filter { k.kinds == null || it.kind in k.kinds }, searched = true)
            }.whileOpen(SearchResults(q, k))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    suspend fun thumbnail(file: VaultFile): Bitmap? = session.openVault?.thumbnails?.thumbnail(file)
}

/** Albums list, one album's contents, and moving files in and out of albums. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumsViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {

    val albums: StateFlow<List<AlbumSummary>?> = session.state
        .flatMapLatest { s ->
            (s as? VaultState.Unlocked)?.vault?.database?.albums()?.observeSummaries()?.whileOpen(emptyList())
                ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun album(id: String) = session.openVault?.database?.albums()?.observe(id) ?: flowOf(null)

    fun filesIn(id: String) =
        session.openVault?.database?.files()?.observeInAlbum(id)?.whileOpen(emptyList()) ?: flowOf(emptyList())

    suspend fun file(id: String): VaultFile? = session.openVault?.database?.files()?.get(id)

    suspend fun thumbnail(file: VaultFile): Bitmap? = session.openVault?.thumbnails?.thumbnail(file)

    fun create(name: String, color: Long, thenAdd: List<String> = emptyList()) {
        viewModelScope.launch {
            val db = session.openVault?.database ?: return@launch
            val album = Album(FileStore.newId(), name.trim().ifEmpty { "Untitled album" }, color, System.currentTimeMillis())
            db.albums().insert(album)
            thenAdd.forEach { db.files().setAlbum(it, album.id) }
        }
    }

    fun rename(id: String, name: String, color: Long) {
        viewModelScope.launch { session.openVault?.database?.albums()?.update(id, name.trim().ifEmpty { "Untitled album" }, color) }
    }

    fun delete(id: String) {
        viewModelScope.launch { session.openVault?.database?.albums()?.delete(id) }
    }

    fun moveTo(fileIds: List<String>, albumId: String?) {
        viewModelScope.launch {
            val dao = session.openVault?.database?.files() ?: return@launch
            fileIds.chunked(com.shahaabapps.obscura.vault.BULK_CHUNK).forEach { dao.setAlbumAll(it, albumId) }
        }
    }

    companion object {
        val COLORS = listOf(0xFF3B5BFF, 0xFFFF7AB6, 0xFF5EF0C2, 0xFFFF8A3D, 0xFFFFE14D, 0xFFF5F5F0)
    }
}
