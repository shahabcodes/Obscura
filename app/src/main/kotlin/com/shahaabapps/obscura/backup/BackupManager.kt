package com.shahaabapps.obscura.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.shahaabapps.obscura.crypto.CorruptVaultException
import com.shahaabapps.obscura.importer.ImportManager
import com.shahaabapps.obscura.ui.screens.formatBytes
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupJob { BACKUP, RESTORE }

sealed interface BackupOutcome {
    data class BackedUp(val result: BackupResult, val verify: VerifyResult) : BackupOutcome
    data object Restored : BackupOutcome
    /** The destination holds a backup of a different vault; ask before replacing it. */
    data object OtherVaultThere : BackupOutcome
    data class Failed(val message: String) : BackupOutcome
}

data class BackupState(
    val running: BackupJob? = null,
    val progress: BackupProgress = BackupProgress(),
    val outcome: BackupOutcome? = null,
)

/** Where the backup goes, remembered between runs. */
data class BackupLocation(val uri: Uri, val name: String)

/**
 * Runs backups and restores in the background with a foreground service, like imports.
 *
 * Backups need the vault unlocked only to flush the database; everything copied is ciphertext.
 * While the decoy vault is open, the decoy is what gets backed up, with its own remembered
 * location and history, so nothing in the decoy hints at the real vault.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val store: VaultStore,
    private val autoLock: AutoLock,
    private val importManager: ImportManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("obscura.backup", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val prefix: String get() = if (session.openVault?.isDecoy == true) "decoy." else ""

    fun location(): BackupLocation? {
        val uri = prefs.getString(prefix + "location", null)?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (granted) BackupLocation(uri, prefs.getString(prefix + "locationName", null) ?: "Backup folder") else null
    }

    /** Remembers a folder picked with OpenDocumentTree, keeping access across restarts. */
    fun setLocation(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previous = prefs.getString(prefix + "location", null)?.let(Uri::parse)
        context.contentResolver.takePersistableUriPermission(tree, flags)
        if (previous != null && previous != tree && previous != otherLocation()) {
            runCatching { context.contentResolver.releasePersistableUriPermission(previous, flags) }
        }
        prefs.edit().putString(prefix + "location", tree.toString()).putString(prefix + "locationName", displayName(tree)).apply()
    }

    /** The other vault's remembered location, whose access must not be released. */
    private fun otherLocation(): Uri? =
        prefs.getString(if (prefix.isEmpty()) "decoy.location" else "location", null)?.let(Uri::parse)

    /** When the open vault was last backed up completely, or null. */
    fun lastBackupAt(): Long? = prefs.getLong(prefix + "lastAt", 0L).takeIf { it > 0 }

    fun backup(replaceOtherVault: Boolean = false, startService: Boolean = true): Boolean {
        val location = location() ?: return false
        return backup(SafBackupDir(context, location.uri), replaceOtherVault, startService)
    }

    /** Starts a backup into [destination]. Returns false if one can't start right now. */
    fun backup(destination: BackupDir, replaceOtherVault: Boolean = false, startService: Boolean = true): Boolean {
        val vault = session.openVault ?: return false
        if (_state.value.running != null) return false
        if (importManager.progress.value.running) {
            _state.value = BackupState(outcome = BackupOutcome.Failed("Wait for the import to finish, then back up."))
            return false
        }
        val keyPrefix = prefix
        _state.value = BackupState(running = BackupJob.BACKUP)
        autoLock.hold()
        if (startService) ContextCompat.startForegroundService(context, Intent(context, BackupService::class.java))
        scope.launch {
            val outcome = try {
                val engine = BackupEngine(vault.store.root)
                if (!replaceOtherVault && engine.holdsOtherVault(destination)) {
                    BackupOutcome.OtherVaultThere
                } else {
                    val result = engine.backup(destination, checkpoint = { checkpoint(vault) }) { p -> _state.update { it.copy(progress = p) } }
                    _state.update { it.copy(progress = it.progress.copy(phase = "Checking the backup")) }
                    val verify = engine.verify(destination)
                    if (verify.ok) prefs.edit().putLong(keyPrefix + "lastAt", System.currentTimeMillis()).apply()
                    BackupOutcome.BackedUp(result, verify)
                }
            } catch (e: Exception) {
                BackupOutcome.Failed(explain(e))
            } finally {
                autoLock.release()
            }
            _state.update { it.copy(running = null, outcome = outcome) }
        }
        return true
    }

    /**
     * Restores a backup picked with OpenDocumentTree. Only possible when there is no vault on
     * this phone, so a restore can never overwrite one.
     */
    fun restore(tree: Uri, startService: Boolean = true): Boolean =
        restore(SafBackupDir(context, tree), startService)

    fun restore(source: BackupDir, startService: Boolean = true): Boolean {
        if (store.exists() || _state.value.running != null) return false
        _state.value = BackupState(running = BackupJob.RESTORE)
        if (startService) ContextCompat.startForegroundService(context, Intent(context, BackupService::class.java))
        scope.launch {
            val staging = File(store.root.parentFile, store.root.name + ".restore")
            val outcome = try {
                BackupEngine(store.root).restore(source, staging) { p -> _state.update { it.copy(progress = p) } }
                // Anything left at the vault path is an unfinished vault without a header.
                store.root.deleteRecursively()
                check(staging.renameTo(store.root)) { "The restored vault could not be moved into place" }
                session.reload()
                BackupOutcome.Restored
            } catch (e: Exception) {
                staging.deleteRecursively()
                BackupOutcome.Failed(explain(e))
            }
            _state.update { it.copy(running = null, outcome = outcome) }
        }
        return true
    }

    /** Clears a finished run's result. */
    fun dismiss() {
        if (_state.value.running == null) _state.value = BackupState()
    }

    /** Folds the write-ahead log into `index.db` so the file is complete on its own. */
    private fun checkpoint(vault: com.shahaabapps.obscura.vault.OpenVault) {
        val db = vault.database.openHelper.writableDatabase
        repeat(10) {
            val busy = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c -> c.moveToFirst() && c.getInt(0) != 0 }
            if (!busy) return
            Thread.sleep(100)
        }
        throw IllegalStateException("The vault is busy. Try again in a moment.")
    }

    private fun explain(e: Exception): String = when (e) {
        is CorruptVaultException -> e.message ?: "This isn't an Obscura backup"
        is BackupSpaceException -> "The drive needs ${formatBytes(e.missingBytes)} more free space. Nothing was copied."
        is RestoreSpaceException -> "This phone needs ${formatBytes(e.missingBytes)} more free space to restore this backup."
        is FileTooLargeForDriveException ->
            "This drive can't hold files over 4 GB, so it's probably formatted as FAT32. Use a drive formatted as exFAT."
        is SecurityException -> "Obscura no longer has access to that folder. Choose it again."
        is java.io.FileNotFoundException ->
            "The backup drive isn't available. Plug it in or choose the folder again."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun displayName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Backup folder"
}
