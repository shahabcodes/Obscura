package com.shahaabapps.obscura.ui.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.export.ExportManager
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the export screen needs to know before anything starts. */
data class ExportAllState(
    val count: Int = 0,
    val bytes: Long = 0,
    /** A folder was chosen before and is still reachable, so a stopped run can be picked up. */
    val canContinue: Boolean = false,
)

@HiltViewModel
class ExportAllViewModel @Inject constructor(
    private val exports: ExportManager,
    private val session: VaultSession,
    private val autoLock: AutoLock,
) : ViewModel() {

    /** Held while the system folder picker is in front, since that counts as leaving the app. */
    private var picking = false

    private val _state = MutableStateFlow(ExportAllState())
    val state: StateFlow<ExportAllState> = _state.asStateFlow()

    val progress = exports.progress

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val vault = session.openVault ?: return@launch
            val files = vault.database.files()
            _state.value = ExportAllState(
                count = files.count(),
                bytes = files.totalBytes(),
                canContinue = exports.destination() != null,
            )
        }
    }

    /**
     * Picking a folder puts the system's own picker in front, which is leaving the app as far as
     * auto-lock is concerned. Without holding it the vault would be locked by the time the user
     * came back, and the export could not start at all.
     */
    fun beginPicking() {
        if (!picking) {
            picking = true
            autoLock.hold()
        }
    }

    fun endPicking() {
        if (picking) {
            picking = false
            autoLock.release()
        }
    }

    /** Remembers the folder first, so a run the system stops can be carried on later. */
    fun startInto(tree: Uri) {
        exports.reset()
        exports.rememberDestination(tree)
        _state.value = _state.value.copy(canContinue = true)
        exports.exportEverything(tree)
        endPicking()
    }

    fun continueExport() {
        exports.reset()
        exports.continueEverything()
    }

    fun cancel() = exports.cancel()

    fun clear() {
        exports.reset()
        refresh()
    }
}
