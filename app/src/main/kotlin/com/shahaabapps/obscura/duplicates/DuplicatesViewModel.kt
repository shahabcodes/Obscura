package com.shahaabapps.obscura.duplicates

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.OpenVault
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Fingerprints photos (from their encrypted thumbnails) and finds duplicate groups. */
object DuplicateScanner {

    /** dHash of a bitmap: shrink to 9×8, convert to luminance, compare neighbours. */
    fun fingerprint(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(72)
        small.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (small !== bitmap) small.recycle()
        val luma = IntArray(72) { i ->
            val p = pixels[i]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
        return DuplicateLogic.dHash(luma)
    }

    /** Fills in missing fingerprints, then groups every file in the vault. */
    suspend fun scan(vault: OpenVault, onProgress: (done: Int, total: Int) -> Unit): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val dao = vault.database.files()
        val pending = dao.all().filter { it.kind == FileKind.PHOTO && it.phash == null }
        pending.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            vault.thumbnails.thumbnail(file)?.let { dao.setPhash(file.id, fingerprint(it)) }
            onProgress(index + 1, pending.size)
        }
        DuplicateLogic.find(dao.all())
    }
}

sealed interface DuplicatesUi {
    data class Scanning(val done: Int, val total: Int) : DuplicatesUi
    data class Results(val groups: List<DuplicateGroup>, val marked: Set<String>, val filter: DuplicateKind?) : DuplicatesUi {
        val visible get() = if (filter == null) groups else groups.filter { it.kind == filter }
        val markedFiles get() = groups.flatMap { it.files }.distinctBy { it.id }.filter { it.id in marked }
        val markedBytes get() = markedFiles.sumOf { it.sizeBytes }
    }
    data class Shredding(val done: Int, val total: Int) : DuplicatesUi
    data class Done(val count: Int, val bytes: Long) : DuplicatesUi
    data class Failed(val message: String) : DuplicatesUi
}

@HiltViewModel
class DuplicatesViewModel @Inject constructor(private val session: VaultSession) : ViewModel() {

    private val _ui = MutableStateFlow<DuplicatesUi>(DuplicatesUi.Scanning(0, 0))
    val ui: StateFlow<DuplicatesUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        scan()
    }

    fun scan() {
        job?.cancel()
        _ui.value = DuplicatesUi.Scanning(0, 0)
        job = viewModelScope.launch {
            val vault = session.openVault ?: return@launch
            try {
                val groups = DuplicateScanner.scan(vault) { done, total -> _ui.value = DuplicatesUi.Scanning(done, total) }
                // Suggest shredding every copy except the best one in each group.
                _ui.value = DuplicatesUi.Results(groups, groups.flatMap { g -> g.extras.map { it.id } }.toSet(), null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = DuplicatesUi.Failed("The scan stopped: ${e.message}")
            }
        }
    }

    fun toggle(file: VaultFile) = _ui.update {
        if (it !is DuplicatesUi.Results) it
        else it.copy(marked = if (file.id in it.marked) it.marked - file.id else it.marked + file.id)
    }

    fun filter(kind: DuplicateKind?) = _ui.update { if (it is DuplicatesUi.Results) it.copy(filter = kind) else it }

    fun shredMarked() {
        val results = _ui.value as? DuplicatesUi.Results ?: return
        val targets = results.markedFiles
        if (targets.isEmpty()) return
        job = viewModelScope.launch {
            val vault = session.openVault ?: return@launch
            var bytes = 0L
            targets.forEachIndexed { i, file ->
                _ui.value = DuplicatesUi.Shredding(i, targets.size)
                vault.shred(file)
                bytes += file.sizeBytes
            }
            _ui.value = DuplicatesUi.Done(targets.size, bytes)
        }
    }

    suspend fun thumbnail(file: VaultFile): Bitmap? = session.openVault?.thumbnails?.thumbnail(file)
}
