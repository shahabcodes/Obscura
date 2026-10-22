package com.shahaabapps.obscura.importer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class ImportOptions(
    val skipDuplicates: Boolean = true,
    val stripLocation: Boolean = true,
)

data class ImportProgress(
    val running: Boolean = false,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val sealed: Int = 0,
    val duplicates: Int = 0,
    val failed: Int = 0,
    val locationRemoved: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val current: String? = null,
    val error: String? = null,
    val originalsDeleted: Int = 0,
    val originalsKept: Int = 0,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            total > 0 -> processed.toFloat() / total
            else -> 0f
        }
}

/**
 * Seals files one at a time in the background. The vault is held open (see [AutoLock.hold])
 * and a foreground service keeps the process alive until the batch finishes or is cancelled.
 */
@Singleton
class ImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val autoLock: AutoLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metadata = MetadataReader(context)
    private var job: Job? = null

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    /** Sources that were sealed successfully in the last batch; candidates for deleting originals. */
    private val sealedSources = mutableListOf<ImportSource>()

    val deletableOriginals: Int get() = synchronized(sealedSources) { sealedSources.size }

    fun start(sources: List<ImportSource>, options: ImportOptions, startService: Boolean = true): Boolean {
        if (_progress.value.running || sources.isEmpty() || session.openVault == null) return false
        synchronized(sealedSources) { sealedSources.clear() }
        _progress.value = ImportProgress(
            running = true,
            total = sources.size,
            bytesTotal = sources.sumOf { it.sizeBytes ?: 0L },
        )
        autoLock.hold()
        if (startService) ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java))
        job = scope.launch {
            try {
                for (source in sources) {
                    coroutineContext.ensureActive()
                    importOne(source, options)
                }
                _progress.update { it.copy(running = false, finished = true, current = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _progress.update { it.copy(running = false, finished = true, cancelled = true, current = null) }
            } finally {
                autoLock.release()
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished batch so the import screen starts fresh. */
    fun reset() {
        if (!_progress.value.running) {
            _progress.value = ImportProgress()
            synchronized(sealedSources) { sealedSources.clear() }
        }
    }

    /** Deletes the originals of the files sealed in the last batch. */
    suspend fun deleteOriginals(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val targets = synchronized(sealedSources) { sealedSources.toList() }
        var deleted = 0
        var kept = 0
        for (source in targets) {
            val ok = try {
                source.deleteOriginal()
            } catch (e: Exception) {
                false
            }
            if (ok) deleted++ else kept++
        }
        synchronized(sealedSources) { sealedSources.clear() }
        _progress.update { it.copy(originalsDeleted = deleted, originalsKept = kept) }
        deleted to kept
    }

    private suspend fun importOne(source: ImportSource, options: ImportOptions) {
        _progress.update { it.copy(current = source.displayName) }
        val startBytes = _progress.value.bytesDone
        try {
            val vault = session.openVault ?: throw IllegalStateException("The vault was locked")
            val info = metadata.read(source)
            var removedLocation = false
            val sealed = source.open().use { raw ->
                val counting = CountingStream(raw) { read -> _progress.update { it.copy(bytesDone = startBytes + read) } }
                // The scrubber rewrites the metadata header up front, so it already knows
                // whether location data was present before any pixels are sealed.
                val stream: InputStream = if (options.stripLocation && source.mimeType == "image/jpeg") {
                    val result = JpegLocationScrubber.wrap(counting)
                    removedLocation = result.removedLocation()
                    result.stream
                } else counting
                vault.files.put(
                    stream,
                    IncomingFile(source.displayName, source.mimeType, info.takenAt, info.width, info.height, info.durationMs),
                    skipIfDuplicate = options.skipDuplicates,
                )
            }
            _progress.update {
                it.copy(
                    processed = it.processed + 1,
                    sealed = it.sealed + if (sealed != null) 1 else 0,
                    duplicates = it.duplicates + if (sealed == null) 1 else 0,
                    locationRemoved = it.locationRemoved + if (sealed != null && removedLocation) 1 else 0,
                    bytesDone = startBytes + (source.sizeBytes ?: 0L),
                )
            }
            // A duplicate's original is safe to delete too: its contents are already in the vault.
            synchronized(sealedSources) { sealedSources += source }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _progress.update {
                it.copy(
                    processed = it.processed + 1,
                    failed = it.failed + 1,
                    bytesDone = startBytes + (source.sizeBytes ?: 0L),
                    error = "${source.displayName}: ${e.message}",
                )
            }
            if (session.openVault == null) throw kotlinx.coroutines.CancellationException("Vault locked")
        }
    }

    /** Reports bytes read, at most every 256 KB, for the progress bar. */
    private class CountingStream(input: InputStream, private val onProgress: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            count += n
            if (count - lastReported >= 256 * 1024) {
                lastReported = count
                onProgress(count)
            }
        }
    }
}
