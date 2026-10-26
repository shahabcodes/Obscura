package com.shahaabapps.obscura.media

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.shahaabapps.obscura.ui.components.MAX_ANIMATED_BYTES
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import com.shahaabapps.obscura.vault.whileOpen
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

/** Where the GIF being made from a video has got to. */
data class GifMaking(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val saved: String? = null,
    val error: String? = null,
)

data class Library(
    val loaded: Boolean = false,
    val videos: List<VaultFile> = emptyList(),
    val audio: List<VaultFile> = emptyList(),
    val documents: List<VaultFile> = emptyList(),
)

/** Videos, audio and documents, plus what the players need to read sealed files. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val session: VaultSession,
    private val autoLock: com.shahaabapps.obscura.vault.AutoLock,
) : ViewModel() {

    private val _gif = kotlinx.coroutines.flow.MutableStateFlow(GifMaking())

    /** Progress of turning a stretch of video into a GIF. */
    val gif: StateFlow<GifMaking> = _gif

    /**
     * Makes a GIF from [video] starting at [startMs] and sealing it into the vault. The vault is
     * held open while it runs, because reading the frames takes a few seconds.
     */
    fun makeGif(video: VaultFile, startMs: Long, lengthMs: Long) {
        if (_gif.value.running) return
        val vault = session.openVault ?: return
        _gif.value = GifMaking(running = true)
        viewModelScope.launch {
            autoLock.hold()
            try {
                val gif = VideoToGif.create(
                    vault = vault,
                    video = video,
                    request = GifRequest(startMs, lengthMs),
                    stillOpen = { session.openVault === vault },
                ) { done -> _gif.value = GifMaking(running = true, fraction = done) }
                _gif.value = GifMaking(saved = gif.name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Leaving the screen or locking the vault: not a failure to report.
                _gif.value = GifMaking()
                throw e
            } catch (e: Exception) {
                _gif.value = GifMaking(error = "The GIF couldn't be made: ${e.message}")
            } finally {
                autoLock.release()
            }
        }
    }

    fun dismissGif() {
        if (!_gif.value.running) _gif.value = GifMaking()
    }

    private val byId = ConcurrentHashMap<String, VaultFile>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val library: StateFlow<Library> = session.state
        .flatMapLatest {
            if (it is VaultState.Unlocked) it.vault.database.files().observeAll().whileOpen(emptyList()) else flowOf(emptyList())
        }
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

    /** Everything a GIF needs to be shown animated: the sealed bytes, decrypted in memory. */
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

    fun shred(file: VaultFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            session.openVault?.shred(file)
            onDone()
        }
    }
}
