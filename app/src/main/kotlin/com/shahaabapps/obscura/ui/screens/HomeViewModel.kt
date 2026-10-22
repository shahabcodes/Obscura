package com.shahaabapps.obscura.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class KindSummary(val count: Int = 0, val bytes: Long = 0)

data class HomeUiState(
    val totalBytes: Long = 0,
    val totalFiles: Int = 0,
    val byKind: Map<FileKind, KindSummary> = emptyMap(),
) {
    fun of(kind: FileKind) = byKind[kind] ?: KindSummary()
}

@HiltViewModel
class HomeViewModel @Inject constructor(session: VaultSession) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUiState> = session.state
        .flatMapLatest { vault ->
            if (vault is VaultState.Unlocked) vault.vault.database.files().observeTotals() else flowOf(emptyList())
        }
        .map { totals ->
            HomeUiState(
                totalBytes = totals.sumOf { it.bytes },
                totalFiles = totals.sumOf { it.count },
                byKind = totals.associate { it.kind to KindSummary(it.count, it.bytes) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}

/** 1.2 GB, 640 MB, 12 KB… */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}
