package com.shahaabapps.obscura.ui.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.importer.ImportManager
import com.shahaabapps.obscura.importer.ImportOptions
import com.shahaabapps.obscura.importer.ImportProgress
import com.shahaabapps.obscura.importer.ImportSource
import com.shahaabapps.obscura.importer.SourceResolver
import com.shahaabapps.obscura.vault.AutoLock
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportScreenState(
    val options: ImportOptions = ImportOptions(),
    val scanning: Boolean = false,
    val deleting: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ImportManager,
    private val autoLock: AutoLock,
) : ViewModel() {

    private var pickerOpen = false

    /**
     * The system file picker is another app, so Obscura counts as "in the background" while it
     * is showing. Hold the vault open so browsing a big folder tree can't lock it mid-pick.
     */
    fun beginPicking() {
        if (!pickerOpen) {
            pickerOpen = true
            autoLock.hold()
        }
    }

    /** Called once the picker returned; any import has already taken its own hold by now. */
    private fun endPicking() {
        if (pickerOpen) {
            pickerOpen = false
            autoLock.release()
        }
    }

    fun pickCancelled() = endPicking()

    override fun onCleared() {
        endPicking()
    }

    private val prefs = context.getSharedPreferences("obscura.settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ImportScreenState(
            options = ImportOptions(
                skipDuplicates = prefs.getBoolean("importSkipDuplicates", true),
                stripLocation = prefs.getBoolean("importStripLocation", true),
            ),
        ),
    )
    val state: StateFlow<ImportScreenState> = _state.asStateFlow()
    val progress: StateFlow<ImportProgress> = manager.progress

    val deletableOriginals: Int get() = manager.deletableOriginals

    fun setSkipDuplicates(value: Boolean) = updateOptions(_state.value.options.copy(skipDuplicates = value))

    fun setStripLocation(value: Boolean) = updateOptions(_state.value.options.copy(stripLocation = value))

    private fun updateOptions(options: ImportOptions) {
        prefs.edit()
            .putBoolean("importSkipDuplicates", options.skipDuplicates)
            .putBoolean("importStripLocation", options.stripLocation)
            .apply()
        _state.update { it.copy(options = options) }
    }

    fun importDocuments(uris: List<Uri>) = startFrom { SourceResolver.fromDocuments(context, uris) }

    fun importFolder(tree: Uri) = startFrom { SourceResolver.fromTree(context, tree) }

    private fun startFrom(resolve: () -> List<ImportSource>) {
        _state.update { it.copy(scanning = true, message = null) }
        viewModelScope.launch {
            try {
                val sources = try {
                    withContext(Dispatchers.IO) { resolve() }
                } catch (e: Exception) {
                    _state.update { it.copy(scanning = false, message = "Couldn't read that location: ${e.message}") }
                    return@launch
                }
                _state.update { it.copy(scanning = false) }
                when {
                    sources.isEmpty() -> _state.update { it.copy(message = "No files found there.") }
                    !manager.start(sources, _state.value.options) -> _state.update { it.copy(message = "An import is already running, or the vault is locked.") }
                }
            } finally {
                endPicking()
            }
        }
    }

    fun cancel() = manager.cancel()

    fun deleteOriginals() {
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val (deleted, kept) = manager.deleteOriginals()
            _state.update {
                it.copy(
                    deleting = false,
                    message = buildString {
                        append("Removed %,d original%s from your phone.".format(deleted, if (deleted == 1) "" else "s"))
                        if (kept > 0) append(" %,d couldn't be removed because their location doesn't allow deleting; remove them yourself.".format(kept))
                    },
                )
            }
        }
    }

    fun reset() {
        manager.reset()
        _state.update { it.copy(message = null) }
    }
}
