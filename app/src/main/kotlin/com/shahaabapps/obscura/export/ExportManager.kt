package com.shahaabapps.obscura.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.shahaabapps.obscura.backup.BackupDir
import com.shahaabapps.obscura.backup.SafBackupDir
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExportProgress(
    val running: Boolean = false,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val exported: Int = 0,
    val failed: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val error: String? = null,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            total > 0 -> done.toFloat() / total
            else -> 0f
        }
}

/**
 * Saves decrypted copies of sealed files outside the vault: into a folder or USB drive picked
 * with the system picker, or into a single file the user named. Copies leave the vault's
 * protection, so the UI asks first. Decryption streams straight into the destination; nothing
 * decrypted is written anywhere else. Runs in the background with the vault held open.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val autoLock: AutoLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow(ExportProgress())
    val progress: StateFlow<ExportProgress> = _progress.asStateFlow()

    init {
        // A lock of any kind stops an export at once, even mid-file.
        scope.launch { session.state.collect { if (it !is VaultState.Unlocked) cancel() } }
    }

    /** Saves [files] into a folder picked with OpenDocumentTree. */
    fun exportToFolder(tree: Uri, files: List<VaultFile>, startService: Boolean = true): Boolean =
        exportToFolder(SafBackupDir(context, tree), files, startService)

    fun exportToFolder(folder: BackupDir, files: List<VaultFile>, startService: Boolean = true): Boolean {
        val taken = runCatching { folder.list().map { it.name.lowercase() }.toMutableSet() }.getOrDefault(mutableSetOf())
        return run(files, startService) { file ->
            val name = uniqueName(file.name, taken)
            taken += name.lowercase()
            Target(open = { folder.write(name, file.mimeType) }, discard = { runCatching { folder.delete(name) } })
        }
    }

    /** Saves one file to a document created with CreateDocument. */
    fun exportFile(target: Uri, file: VaultFile, startService: Boolean = true): Boolean = run(listOf(file), startService) {
        Target(
            open = { context.contentResolver.openOutputStream(target, "wt") ?: error("That location can't be written to") },
            discard = { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) } },
        )
    }

    fun cancel() {
        job?.cancel()
    }

    /** Removes a document the save picker created for an export that didn't happen. */
    fun discard(target: Uri) {
        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
    }

    /** Clears a finished run so the next export starts fresh. */
    fun reset() {
        if (!_progress.value.running) _progress.value = ExportProgress()
    }

    private class Target(val open: () -> OutputStream, val discard: () -> Unit)

    private fun run(files: List<VaultFile>, startService: Boolean, targetFor: (VaultFile) -> Target): Boolean {
        if (_progress.value.running || files.isEmpty() || session.openVault == null) return false
        if (startService) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
            } catch (e: Exception) {
                _progress.value = ExportProgress(finished = true, error = "The export couldn't start: ${e.message}")
                return false
            }
        }
        _progress.value = ExportProgress(running = true, total = files.size, bytesTotal = files.sumOf { it.sizeBytes })
        autoLock.hold()
        val vault = session.openVault
        job = scope.launch {
            try {
                for (file in files) {
                    coroutineContext.ensureActive()
                    if (vault == null || session.openVault !== vault) throw kotlinx.coroutines.CancellationException("The vault locked")
                    val start = _progress.value.bytesDone
                    val target = targetFor(file)
                    try {
                        vault.files.open(file).use { input ->
                            target.open().use { output ->
                                copy(input, output, stillOpen = { session.openVault === vault }) { n -> _progress.update { it.copy(bytesDone = start + n) } }
                            }
                        }
                        _progress.update { it.copy(done = it.done + 1, exported = it.exported + 1, bytesDone = start + file.sizeBytes) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        target.discard()
                        throw e
                    } catch (e: Exception) {
                        target.discard()
                        _progress.update { it.copy(done = it.done + 1, failed = it.failed + 1, bytesDone = start + file.sizeBytes, error = "${file.name}: ${e.message}") }
                    }
                }
                _progress.update { it.copy(running = false, finished = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _progress.update { it.copy(running = false, finished = true, cancelled = true) }
            } finally {
                autoLock.release()
            }
        }
        return true
    }

    private suspend fun copy(input: InputStream, output: OutputStream, stillOpen: () -> Boolean, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        var reported = 0L
        while (true) {
            coroutineContext.ensureActive()
            if (!stillOpen()) throw kotlinx.coroutines.CancellationException("The vault locked")
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
            if (total - reported >= 1 shl 20) {
                reported = total
                onProgress(total)
            }
        }
        output.flush()
    }

    companion object {
        /** "beach.jpg", then "beach (2).jpg", "beach (3).jpg"… so exports never overwrite. */
        fun uniqueName(name: String, taken: Set<String>): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }
            if (clean.lowercase() !in taken) return clean
            val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
            val (base, ext) = clean.substring(0, dot) to clean.substring(dot)
            var n = 2
            while ("$base ($n)$ext".lowercase() in taken) n++
            return "$base ($n)$ext"
        }
    }
}
