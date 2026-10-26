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
    /** Files already at the destination from an earlier run, counted as done without copying. */
    val skipped: Int = 0,
    /** Set when the system stopped a long export; running it again carries on where it left off. */
    val paused: Boolean = false,
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
    private val prefs = context.getSharedPreferences("obscura.export", Context.MODE_PRIVATE)

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

    /**
     * Saves a decrypted copy of everything in the vault into [tree], a folder picked with
     * OpenDocumentTree.
     *
     * Built for a vault far larger than memory: files are walked a page at a time rather than
     * listed in one go, and each one is streamed straight from the vault into the destination.
     * Copies are put in a folder per month, so a drive does not end up with one directory
     * holding every file.
     *
     * It can be run again. A copy already there under the same name and the same size is left
     * alone and counted as done, so a run that was interrupted — by the system's limit on how
     * long a background job may go on, by a drive being unplugged, or by the user — carries on
     * where it stopped instead of starting over or making a second copy of everything.
     */
    fun exportEverything(tree: Uri, startService: Boolean = true): Boolean =
        exportEverything(SafBackupDir(context, tree), startService)

    fun exportEverything(destination: BackupDir, startService: Boolean = true): Boolean {
        if (_progress.value.running) return false
        val vault = session.openVault ?: return false
        if (startService && !startService()) return false

        _progress.value = ExportProgress(running = true)
        autoLock.hold()
        job = scope.launch {
            try {
                val files = vault.database.files()
                _progress.update { it.copy(total = files.count(), bytesTotal = files.totalBytes()) }
                // Names this run has written, across every month, so the skip check above can
                // tell "was already here" from "I put that there a moment ago".
                val writtenNow = mutableSetOf<String>()

                // A folder per month, each listed once and remembered, so the destination is not
                // re-read for every single file.
                val folders = mutableMapOf<String, BackupDir>()
                // What was already in each month folder before this run started, by name and
                // size. Only this decides whether a file is "already there".
                val before = mutableMapOf<String, Map<String, Long>>()
                // The names this run has claimed in each month folder, so two files that want
                // the same name both get written rather than one overwriting or hiding the other.
                val claimed = mutableMapOf<String, MutableSet<String>>()

                var afterId = ""
                while (true) {
                    coroutineContext.ensureActive()
                    if (session.openVault !== vault) throw kotlinx.coroutines.CancellationException("The vault locked")
                    val page = files.pageAfter(afterId, PAGE)
                    if (page.isEmpty()) break
                    afterId = page.last().id

                    for (file in page) {
                        coroutineContext.ensureActive()
                        if (session.openVault !== vault) throw kotlinx.coroutines.CancellationException("The vault locked")

                        val month = monthOf(file.takenAt)
                        val folder = folders.getOrPut(month) { destination.directory(month) }
                        val already = before.getOrPut(month) {
                            runCatching { folder.list().associate { it.name.lowercase() to it.size } }
                                .getOrDefault(emptyMap())
                        }
                        val used = claimed.getOrPut(month) { already.keys.toMutableSet() }

                        // Already there from an earlier run, and the whole file: leave it be.
                        // This is what lets a second run carry on instead of starting again.
                        // Only what was there before counts — a copy this run just wrote must
                        // never stand in for a different file of the same name and size.
                        val clean = uniqueName(file.name, emptySet())
                        if (already[clean.lowercase()] == file.sizeBytes && clean.lowercase() !in writtenNow) {
                            _progress.update {
                                it.copy(
                                    done = it.done + 1,
                                    skipped = it.skipped + 1,
                                    bytesDone = it.bytesDone + file.sizeBytes,
                                )
                            }
                            continue
                        }

                        val name = uniqueName(file.name, used)
                        used += name.lowercase()
                        writtenNow += name.lowercase()
                        val start = _progress.value.bytesDone
                        try {
                            vault.files.open(file).use { input ->
                                folder.write(name, file.mimeType).use { output ->
                                    copy(input, output, stillOpen = { session.openVault === vault }) { n ->
                                        _progress.update { it.copy(bytesDone = start + n) }
                                    }
                                }
                            }
                            _progress.update {
                                it.copy(done = it.done + 1, exported = it.exported + 1, bytesDone = start + file.sizeBytes)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            runCatching { folder.delete(name) }
                            throw e
                        } catch (e: Exception) {
                            // A half-written copy is worse than none: it would be skipped as
                            // finished by the next run.
                            runCatching { folder.delete(name) }
                            used -= name.lowercase()
                            writtenNow -= name.lowercase()
                            _progress.update {
                                it.copy(
                                    done = it.done + 1,
                                    failed = it.failed + 1,
                                    bytesDone = start + file.sizeBytes,
                                    error = "A file could not be copied: ${e.message}",
                                )
                            }
                        }
                    }
                }
                _progress.update { it.copy(running = false, finished = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _progress.update { it.copy(running = false, finished = true, cancelled = true) }
            } catch (e: Exception) {
                _progress.update { it.copy(running = false, finished = true, error = "The export stopped: ${e.message}") }
            } finally {
                autoLock.release()
            }
        }
        return true
    }

    /**
     * Remembers the folder an export is going to, with access that survives the app being
     * closed, so a run the system stopped can be carried on without picking it again.
     */
    fun rememberDestination(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previous = prefs.getString(destinationKey, null)?.let(Uri::parse)
        runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
        // Hold on to one folder at a time: these grants are capped, and an old one is standing
        // write access to a folder holding a decrypted copy of the vault.
        if (previous != null && previous != tree) {
            runCatching { context.contentResolver.releasePersistableUriPermission(previous, flags) }
        }
        prefs.edit().putString(destinationKey, tree.toString()).apply()
    }

    /** The remembered folder, if access to it still holds. */
    fun destination(): Uri? {
        val uri = prefs.getString(destinationKey, null)?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return uri.takeIf { granted }
    }

    fun forgetDestination() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        prefs.getString(destinationKey, null)?.let(Uri::parse)?.let {
            runCatching { context.contentResolver.releasePersistableUriPermission(it, flags) }
        }
        prefs.edit().remove(destinationKey).apply()
    }

    /** Forgets both vaults' destinations, for a wipe that is meant to leave no trace. */
    fun forgetEveryDestination() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        listOf("destination", "decoy.destination").forEach { key ->
            prefs.getString(key, null)?.let(Uri::parse)?.let {
                runCatching { context.contentResolver.releasePersistableUriPermission(it, flags) }
            }
        }
        prefs.edit().clear().apply()
    }

    /**
     * The decoy vault keeps its own destination, as backups do. Otherwise opening the decoy
     * would show that some other session had set up an export, and the whole point of it is that
     * there is nothing to see.
     */
    private val destinationKey: String
        get() = if (session.openVault?.isDecoy == true) "decoy.destination" else "destination"

    /** Carries on into the folder already chosen. */
    fun continueEverything(startService: Boolean = true): Boolean {
        val tree = destination() ?: return false
        return exportEverything(tree, startService)
    }

    /** Stops the run and says it can be picked up again, for the system's own time limit. */
    fun pauseForTimeLimit() {
        if (!_progress.value.running) return
        _progress.update { it.copy(paused = true) }
        job?.cancel()
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

    /** Brings the foreground service up, so the copying survives leaving the app. */
    private fun startService(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        true
    } catch (e: Exception) {
        _progress.value = ExportProgress(finished = true, error = "The export couldn't start: ${e.message}")
        false
    }

    private fun run(files: List<VaultFile>, startService: Boolean, targetFor: (VaultFile) -> Target): Boolean {
        if (_progress.value.running || files.isEmpty() || session.openVault == null) return false
        if (startService && !startService()) return false
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
            } catch (e: Exception) {
                _progress.update { it.copy(running = false, finished = true, error = "The export stopped: ${e.message}") }
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
        /** How many rows are held at a time while walking the whole vault. */
        private const val PAGE = 500

        /** The folder a file goes in: the month it was taken, as "2026-10". */
        fun monthOf(takenAt: Long): String {
            val date = java.time.Instant.ofEpochMilli(takenAt).atZone(java.time.ZoneId.systemDefault())
            return "%04d-%02d".format(date.year, date.monthValue)
        }

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
