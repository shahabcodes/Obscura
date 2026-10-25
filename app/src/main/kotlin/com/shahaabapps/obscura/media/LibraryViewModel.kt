package com.shahaabapps.obscura.media

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Library(
    val loaded: Boolean = false,
    val videos: List<VaultFile> = emptyList(),
    val audio: List<VaultFile> = emptyList(),
    val documents: List<VaultFile> = emptyList(),
)

/** Videos, audio and documents, plus what the players need to read sealed files. */
@HiltViewModel
class LibraryViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {

    private val byId = ConcurrentHashMap<String, VaultFile>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val library: StateFlow<Library> = session.state
        .flatMapLatest { if (it is VaultState.Unlocked) it.vault.database.files().observeAll() else flowOf(emptyList()) }
        .onEach { all ->
            byId.clear()
            all.forEach { byId[it.id] = it }
        }
        .map { all ->
            Library(
                loaded = true,
                videos = all.filter { it.kind == FileKind.VIDEO },
                audio = all.filter { it.kind == FileKind.AUDIO },
                documents = all.filter { it.kind == FileKind.DOCUMENT || it.kind == FileKind.OTHER },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Library())

    /** Data source for Media3, or null when the vault is locked. */
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(): DataSource.Factory? {
        val vault = session.openVault ?: return null
        return SealedDataSource.Factory(vault.keys.fileCipher, vault.files) { id -> byId[id] }
    }

    suspend fun thumbnail(file: VaultFile): Bitmap? = session.openVault?.thumbnails?.thumbnail(file)

    fun open(file: VaultFile) = session.openVault?.files?.open(file)

    /** Random-access PDF bytes for the viewer, or null when the vault is locked. */
    fun pdfSource(file: VaultFile): PdfSource? {
        val vault = session.openVault ?: return null
        // Tied to this vault: reading stops when it locks, and never resumes in another one.
        return vault.pdfSource(file) { session.openVault === vault }
    }

    fun shred(file: VaultFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            session.openVault?.shred(file)
            onDone()
        }
    }
}
