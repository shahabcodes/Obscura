package com.shahaabapps.obscura.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.shahaabapps.obscura.backup.BackupDir
import com.shahaabapps.obscura.backup.SafBackupDir
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.OpenVault
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
    /** Set when the destination has not got the room: what is still needed, and what is free. */
    val shortBy: Long = 0,
    /** Copies taken away because the files they were made from are no longer in the vault. */
    val removed: Int = 0,
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
        scope.launch {
            session.state.collect { state ->
                if (state !is VaultState.Unlocked) {
                    cancel()
                } else if (!_progress.value.running) {
                    // What a run did belongs to the vault it ran in. The figures stay up after
                    // a lock, so the user can see what happened, and are cleared as the next
                    // vault opens -- which may be the decoy, and it has no business seeing them.
                    _progress.value = ExportProgress()
                }
            }
        }
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
     * Copies go into one folder of its own making, with a list beside them recording what a
     * copy cannot carry itself: the date each file was taken, its album, and whether it was a
     * favourite.
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
            var index: java.io.Writer? = null
            var finishedCleanly = false
            var root: BackupDir? = null
            try {
                val files = vault.database.files()
                _progress.update { it.copy(total = files.count(), bytesTotal = files.totalBytes()) }

                // Everything goes inside a folder Obscura makes, so the folder the user picked
                // is left as they had it and anything within this one can be taken as this
                // export's own work when carrying on later.
                val folder = destination.directory(FOLDER)
                root = folder

                // Whether there is room, before anything is copied. Only what is missing counts:
                // a run being carried on may need far less than the whole vault.
                val free = destination.availableBytes()
                if (free != null) {
                    val needed = _progress.value.bytesTotal - alreadyThere(folder)
                    if (needed > free) {
                        _progress.update {
                            it.copy(
                                running = false,
                                finished = true,
                                shortBy = needed - free,
                                error = "There is not enough room in that folder.",
                            )
                        }
                        return@launch
                    }
                }

                // The folder as this run found it. A failure to read it stops the run rather
                // than passing for an empty folder, which would copy everything a second time.
                val here = folder.list().associate { it.name.lowercase() to it.size }.toMutableMap()

                // What the last run into this folder recorded: which file each copy is. Without
                // it a copy is only a name and a size, and a different file that happens to
                // share both would be taken for finished work.
                val recorded = readIndex(folder)
                // Every name this run is responsible for, whether it copied it or found it
                // already there.
                val mine = mutableSetOf<String>()

                // Which names more than one file in the vault wants. A file's name must depend
                // only on the file itself: working it out from the files walked before it would
                // mean that shredding one file changes another's name, and the copy left under
                // the old name could then be mistaken for that other file's finished work.
                val shared = sharedNames(files)

                val albums = runCatching { vault.database.albums().all().associate { it.id to it.name } }
                    .getOrDefault(emptyMap())
                // The list beside the copies is worth having, but it is not the copies. If
                // it cannot be written the files still go out, and that is said at the end
                // rather than reported as the whole export having failed.
                var indexFailed = false
                fun note(line: String) {
                    val writer = index
                    if (writer == null || indexFailed) return
                    // Flushed as it goes: a run that is killed outright cannot tidy up after
                    // itself, and an empty list beside real copies is worse than none at all.
                    runCatching {
                        writer.write(line)
                        writer.flush()
                    }.onFailure { indexFailed = true }
                }
                runCatching {
                    index = folder.write(INDEX, "text/csv").bufferedWriter()
                }.onFailure { indexFailed = true }
                note("exported name,original name,taken,kind,size in bytes,album,favourite,id\n")

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

                        val name = exportName(file, shared)
                        mine += name.lowercase()
                        val start = _progress.value.bytesDone

                        // Already written, whole, by an earlier run: leave it be. A copy of the
                        // wrong size is this export's own half-written file from a run that was
                        // killed outright, and is written over rather than left behind.
                        if (here[name.lowercase()] == file.sizeBytes &&
                            recorded[name.lowercase()] == file.id
                        ) {
                            note(indexRow(file, name, albums))
                            _progress.update {
                                it.copy(
                                    done = it.done + 1,
                                    skipped = it.skipped + 1,
                                    bytesDone = it.bytesDone + file.sizeBytes,
                                )
                            }
                            continue
                        }

                        val replacing = here.containsKey(name.lowercase())
                        here[name.lowercase()] = file.sizeBytes
                        try {
                            vault.files.open(file).use { input ->
                                // Only a rewrite needs the destination looked up; a new file is
                                // created straight out, which matters when there are thousands.
                                val out = if (replacing) folder.write(name, file.mimeType) else folder.writeNew(name, file.mimeType)
                                out.use {
                                    copy(input, it, stillOpen = { session.openVault === vault }) { n ->
                                        _progress.update { it.copy(bytesDone = start + n) }
                                    }
                                }
                            }
                            // Best effort: most folders picked through the system picker will not
                            // allow it. The date inside a photo or video is carried across either
                            // way, and the index has it for everything.
                            runCatching { folder.setModified(name, file.takenAt) }
                            note(indexRow(file, name, albums))
                            _progress.update {
                                it.copy(done = it.done + 1, exported = it.exported + 1, bytesDone = start + file.sizeBytes)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            runCatching { folder.delete(name) }
                            throw e
                        } catch (e: Exception) {
                            // A half-written copy is worse than none: the next run would take it
                            // for finished work if its size happened to match.
                            runCatching { folder.delete(name) }
                            here.remove(name.lowercase())
                            // A full drive will fail every remaining file in turn, which tells
                            // the user nothing useful. Say it once and stop.
                            val room = destination.availableBytes()
                            if (room != null && room < file.sizeBytes) {
                                _progress.update {
                                    it.copy(
                                        running = false,
                                        finished = true,
                                        done = it.done + 1,
                                        failed = it.failed + 1,
                                        shortBy = file.sizeBytes - room,
                                        error = "The folder ran out of room.",
                                    )
                                }
                                return@launch
                            }
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
                // A file shredded from the vault leaves its decrypted copy sitting here, named
                // by nothing and expected by nobody -- the opposite of what shredding is for.
                // Only copies this export is known to have made are removed, so anything of the
                // user's own that shares the folder is left alone.
                var removed = 0
                recorded.keys.filterNot { it in mine }.forEach { gone ->
                    val actual = folder.list().firstOrNull { it.name.equals(gone, ignoreCase = true) }
                    if (actual != null) {
                        runCatching { folder.delete(actual.name) }.onSuccess { removed++ }
                    }
                }
                if (removed > 0) _progress.update { it.copy(removed = removed) }

                finishedCleanly = true
                runCatching { index?.close() }.onFailure { indexFailed = true }
                index = null
                _progress.update {
                    it.copy(
                        running = false,
                        finished = true,
                        error = if (indexFailed) {
                            "Every file was copied, but the list beside them could not be written."
                        } else {
                            it.error
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _progress.update { it.copy(running = false, finished = true, cancelled = true) }
            } catch (e: Exception) {
                _progress.update { it.copy(running = false, finished = true, error = "The export stopped: ${e.message}") }
            } finally {
                runCatching { index?.close() }
                // The list is kept even when the run breaks off. A row is only written once its
                // file is actually there, and each one is flushed as it goes, so what survives
                // is a true account of how far the run got -- and it is what lets the next run
                // tell which copies are already its own work.
                autoLock.release()
            }
        }
        return true
    }

    /**
     * The copies a previous run recorded here, as name to the id of the file it copied.
     *
     * Empty when there is no list, or it cannot be read, or it is from a version that did not
     * record the id — and then nothing is skipped and everything is written again, which costs
     * time but can never pass one file's copy off as another's.
     */
    private fun readIndex(folder: BackupDir): Map<String, String> = runCatching {
        val node = folder.list().firstOrNull { it.name.equals(INDEX, ignoreCase = true) }
            ?: return emptyMap()
        val rows = mutableMapOf<String, String>()
        node.read().bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = splitRow(line)
                if (fields.size >= 8) {
                    val name = fields[0]
                    val id = fields[7]
                    if (name.isNotEmpty() && id.isNotEmpty()) rows[name.lowercase()] = id
                }
            }
        }
        rows
    }.getOrDefault(emptyMap())

    /** Splits one row of the list, honouring the quotes it was written with. */
    private fun splitRow(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                else -> field.append(c)
            }
            i++
        }
        fields += field.toString()
        return fields
    }

    /** One line of the index: what a copy cannot carry on its own. */
    private fun indexRow(file: VaultFile, name: String, albums: Map<String, String>): String {
        fun quote(value: String) = '"' + value.replace("\"", "\"\"") + '"'
        val taken = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(file.takenAt))
        return listOf(
            quote(name),
            quote(file.name),
            quote(taken),
            quote(file.kind.name.lowercase()),
            file.sizeBytes.toString(),
            quote(file.albumId?.let { albums[it] } ?: ""),
            if (file.favorite) "yes" else "no",
            // Which file in the vault this copy is. A later run reads it back to tell a finished
            // copy of this file from a copy of something else that shared its name and size.
            quote(file.id),
        ).joinToString(",") + "\n"
    }

    /**
     * The names that more than one file in the vault wants, so those files can be told apart by
     * something of their own rather than by the order they happen to be walked in.
     */
    private suspend fun sharedNames(files: com.shahaabapps.obscura.vault.FileDao): Set<String> {
        val seen = mutableSetOf<String>()
        val twice = mutableSetOf<String>()
        var afterId = ""
        while (true) {
            coroutineContext.ensureActive()
            val page = files.pageAfter(afterId, PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (file in page) {
                val clean = uniqueName(file.name, emptySet()).lowercase()
                if (!seen.add(clean)) twice += clean
            }
        }
        return twice
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

    /** How much of the vault is already sitting at the destination from an earlier run. */
    private fun alreadyThere(root: BackupDir): Long = runCatching {
        root.list().filter { !it.isDirectory }.sumOf { it.size }
    }.getOrDefault(0L)

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

        /**
         * Obscura makes this folder inside the one the user picked and treats what is in it as
         * its own, so carrying on later never has to guess whether a file is a copy it made or
         * something of the user's that happens to share a name.
         */
        const val FOLDER = "Obscura export"

        /** Everything the copies cannot carry themselves: dates, albums, favourites. */
        const val INDEX = "Obscura index.csv"

        /**
         * What a file is called at the destination. It depends on the file alone: its own name,
         * and a short piece of its id when some other file in the vault wants that name too. So
         * a file keeps its name whatever else is added or shredded, which is what lets a later
         * run tell a finished copy from one that was never made.
         */
        fun exportName(file: VaultFile, shared: Set<String>): String {
            val clean = uniqueName(file.name, emptySet())
            // A name is contested when another file wants it, when it is the index's own name,
            // or when it already looks like a name this gives out -- otherwise a file called
            // "beach [1a2b3c].jpg" could be handed the very name meant for another file's copy.
            val contested = clean.lowercase() in shared ||
                clean.equals(INDEX, ignoreCase = true) ||
                LOOKS_MARKED.containsMatchIn(clean)
            if (!contested) return clean
            // The whole id, not a piece of it: two files that both want one name must never be
            // able to arrive at the same one, however many files the vault holds.
            val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
            return clean.substring(0, dot) + " [" + file.id + "]" + clean.substring(dot)
        }

        /** The shape of a name this gives out, so a file called that is never mistaken for one. */
        private val LOOKS_MARKED = Regex("""\[[0-9a-fA-F]{6,}]""")


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
