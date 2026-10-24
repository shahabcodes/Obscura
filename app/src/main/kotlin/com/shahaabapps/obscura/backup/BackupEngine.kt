package com.shahaabapps.obscura.backup

import com.shahaabapps.obscura.crypto.CorruptVaultException
import com.shahaabapps.obscura.crypto.VaultHeader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BackupProgress(
    val phase: String = "",
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else if (filesTotal > 0) filesDone.toFloat() / filesTotal else 0f
}

/** [skipped] counts files shredded while the backup ran; they are simply not in it. */
data class BackupResult(val copied: Int, val unchanged: Int, val removed: Int, val bytesCopied: Long, val skipped: Int = 0)

/** The destination lacks [missingBytes] of free space. Nothing was copied. */
class BackupSpaceException(val missingBytes: Long) : IOException("Not enough free space on the drive")

/** The destination refused a file over 4 GB, which FAT32-formatted drives can't hold. */
class FileTooLargeForDriveException(cause: Throwable) : IOException("The drive can't hold files over 4 GB", cause)

/** This phone lacks [missingBytes] of free space for the restore. Nothing was changed. */
class RestoreSpaceException(val missingBytes: Long) : IOException("Not enough free space on this phone")

data class VerifyResult(val checked: Int, val missing: Int, val mismatched: Int) {
    val ok: Boolean get() = missing == 0 && mismatched == 0
}

/**
 * Copies the vault folder as it is on disk, which is already fully encrypted: the header holds
 * only wrapped keys, the index is SQLCipher, and every blob and thumbnail is AES-GCM. Nothing is
 * decrypted on the way, so a backup is useless without the passphrase or recovery key.
 *
 * Device-bound files (PIN and fingerprint data) are left out: they only work on this phone.
 * Blobs never change once written (each has a unique id), so an existing backup is updated by
 * copying only new blobs and removing ones that were shredded.
 */
class BackupEngine(private val vaultRoot: File) {

    /**
     * Writes or updates the backup inside [destination]/[FOLDER]. [checkpoint] must flush the
     * database's write-ahead log so `index.db` is complete on its own.
     *
     * @throws BackupSpaceException before anything is copied if the drive is too full.
     * @throws FileTooLargeForDriveException if the drive refuses a file over 4 GB (FAT32).
     */
    fun backup(destination: BackupDir, checkpoint: () -> Unit, onProgress: (BackupProgress) -> Unit): BackupResult {
        require(File(vaultRoot, HEADER).isFile) { "No vault to back up" }
        checkpoint()
        val root = destination.directory(FOLDER)
        // An unfinished backup is recognisable: the manifest is removed first and written last.
        root.delete(MANIFEST)

        val local = SEALED_DIRS.flatMap { dir -> listSealed(dir) }
        val remoteDirs = mutableMapOf<String, BackupDir>()
        val remoteListing = mutableMapOf<String, Map<String, BackupNode>>()
        fun remoteDir(path: String): BackupDir = remoteDirs.getOrPut(path) {
            path.split('/').fold(root) { dir, part -> dir.directory(part) }
        }
        // Planning only reads: nothing is created on the drive until the space check has passed.
        fun remoteFiles(path: String) = remoteListing.getOrPut(path) {
            path.split('/').fold<String, BackupDir?>(root) { dir, part -> dir?.child(part)?.takeIf { it.isDirectory }?.asDirectory() }
                ?.list()?.associateBy { it.name }.orEmpty()
        }

        // Plan: which sealed files are missing or half-copied, and which were shredded since.
        val toCopy = local.filter { (dir, file) ->
            val existing = remoteFiles(dir)[file.name]
            existing == null || existing.isDirectory || existing.size != file.length()
        }
        val keep = local.groupBy({ it.first }, { it.second.name }).mapValues { it.value.toSet() }
        val shredded = SEALED_DIRS.flatMap { top ->
            val topDir = root.child(top)?.takeIf { it.isDirectory }?.asDirectory() ?: return@flatMap emptyList()
            topDir.list().filter { it.isDirectory }.flatMap { bucket ->
                val wanted = keep["$top/${bucket.name}"].orEmpty()
                val dir = bucket.asDirectory()
                dir.list().filter { !it.isDirectory && it.name !in wanted }.map { dir to it }
            }
        }

        // Check space before touching anything. Removed and rewritten files give their space back.
        val index = File(vaultRoot, INDEX)
        val header = File(vaultRoot, HEADER)
        val needed = toCopy.sumOf { it.second.length() } + index.length() + header.length() + MANIFEST_ROOM
        val reusable = shredded.sumOf { it.second.size } +
            toCopy.sumOf { (dir, file) -> remoteFiles(dir)[file.name]?.size ?: 0L } +
            (root.child(INDEX)?.size ?: 0L) + (root.child(HEADER)?.size ?: 0L)
        val available = root.availableBytes()
        if (available != null && needed - reusable > available) throw BackupSpaceException(needed - reusable - available)

        val bytesTotal = local.sumOf { it.second.length() } + index.length() + header.length()
        var bytesDone = 0L
        var copied = 0
        var skipped = 0
        var done = 0
        fun report(phase: String) = onProgress(BackupProgress(phase, done, local.size + 2, bytesDone, bytesTotal))

        report("Removing shredded files")
        for ((dir, node) in shredded) dir.delete(node.name)

        // The database right after the checkpoint, so it matches the blobs about to be copied.
        report("Copying the encrypted index")
        bytesDone += copy(index, root, INDEX)
        done++

        val copying = toCopy.toSet()
        for (entry in local) {
            val (relativeDir, file) = entry
            if (entry in copying) {
                report("Copying sealed files")
                if (copy(file, remoteDir(relativeDir), file.name) < 0) skipped++ else copied++
            }
            bytesDone += file.length()
            done++
            if (done % 20 == 0) report("Copying sealed files")
        }

        bytesDone += copy(header, root, HEADER)
        done++
        root.write(MANIFEST).use { it.write(manifest().toByteArray()) }
        report("Done")
        return BackupResult(copied, local.size - toCopy.size, shredded.size, bytesDone, skipped)
    }

    /** Checks the backup holds every sealed file at the right size and the current header. */
    fun verify(destination: BackupDir): VerifyResult {
        val root = destination.child(FOLDER)?.takeIf { it.isDirectory }?.asDirectory()
            ?: return VerifyResult(0, 1, 0)
        var missing = 0
        var mismatched = 0
        if (root.child(MANIFEST) == null) missing++
        val header = root.child(HEADER)
        if (header == null) missing++ else if (!header.read().use { it.readBytes() }.contentEquals(File(vaultRoot, HEADER).readBytes())) mismatched++
        if (root.child(INDEX) == null) missing++
        val local = SEALED_DIRS.flatMap { listSealed(it) }
        val listings = mutableMapOf<String, Map<String, BackupNode>>()
        for ((relativeDir, file) in local) {
            val listing = listings.getOrPut(relativeDir) {
                relativeDir.split('/').fold<String, BackupDir?>(root) { dir, part -> dir?.child(part)?.takeIf { it.isDirectory }?.asDirectory() }
                    ?.list()?.associateBy { it.name }.orEmpty()
            }
            val node = listing[file.name]
            when {
                node == null -> missing++
                node.size != file.length() -> mismatched++
            }
        }
        return VerifyResult(local.size + 2, missing, mismatched)
    }

    /**
     * Copies a backup into [target] (a fresh, empty folder). The caller moves it into place.
     * @throws CorruptVaultException if [source] is not a complete Obscura backup.
     */
    fun restore(source: BackupDir, target: File, onProgress: (BackupProgress) -> Unit) {
        val root = findBackupRoot(source) ?: throw CorruptVaultException("No Obscura backup in this folder")
        if (root.child(MANIFEST) == null) throw CorruptVaultException("This backup is incomplete (it was interrupted). Run the backup again.")
        val headerNode = root.child(HEADER) ?: throw CorruptVaultException("The backup has no vault header")
        val headerBytes = headerNode.read().use { it.readBytes() }
        VaultHeader.fromBytes(headerBytes) // throws if it isn't a valid header
        val index = root.child(INDEX) ?: throw CorruptVaultException("The backup has no index")

        val remote = SEALED_DIRS.flatMap { top ->
            val topDir = root.child(top)?.takeIf { it.isDirectory }?.asDirectory() ?: return@flatMap emptyList()
            topDir.list().filter { it.isDirectory }.flatMap { bucket ->
                bucket.asDirectory().list().filter { !it.isDirectory }.map { "$top/${bucket.name}" to it }
            }
        }
        val bytesTotal = remote.sumOf { it.second.size } + index.size + headerBytes.size
        target.deleteRecursively()
        target.mkdirs()
        // Keep a little room so the phone isn't left completely full.
        val missing = bytesTotal + RESTORE_MARGIN - target.usableSpace
        if (missing > 0) {
            target.deleteRecursively()
            throw RestoreSpaceException(missing)
        }
        var bytesDone = 0L
        var done = 0
        fun report(phase: String) = onProgress(BackupProgress(phase, done, remote.size + 2, bytesDone, bytesTotal))

        report("Restoring the encrypted index")
        bytesDone += copyIn(index.read(), File(target, INDEX))
        done++
        for ((dir, node) in remote) {
            bytesDone += copyIn(node.read(), File(File(target, dir), node.name))
            done++
            if (done % 20 == 0) report("Restoring sealed files")
        }
        File(target, HEADER).writeBytes(headerBytes)
        done++
        report("Done")
    }

    /**
     * Whether [destination] already holds a backup of a different vault (another phone's, or one
     * that was deleted and recreated), which updating would replace.
     */
    fun holdsOtherVault(destination: BackupDir): Boolean {
        val node = destination.child(FOLDER)?.takeIf { it.isDirectory }?.asDirectory()?.child(HEADER) ?: return false
        val theirs = runCatching { VaultHeader.fromBytes(node.read().use { it.readBytes() }).vaultId }.getOrNull() ?: return false
        return !theirs.contentEquals(VaultHeader.fromBytes(File(vaultRoot, HEADER).readBytes()).vaultId)
    }

    /** The backup folder itself, or a folder that contains it. */
    fun findBackupRoot(dir: BackupDir): BackupDir? = when {
        dir.child(HEADER) != null -> dir
        else -> dir.child(FOLDER)?.takeIf { it.isDirectory }?.asDirectory()
    }

    private fun listSealed(top: String): List<Pair<String, File>> =
        File(vaultRoot, top).listFiles().orEmpty().filter { it.isDirectory }.flatMap { bucket ->
            bucket.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") }.map { "$top/${bucket.name}" to it }
        }

    /**
     * Copies one file and returns the bytes written, or -1 if the local file disappeared first
     * (it was shredded while the backup ran).
     */
    private fun copy(file: File, dir: BackupDir, name: String): Long {
        val input = try {
            file.inputStream()
        } catch (e: java.io.FileNotFoundException) {
            if (!file.exists()) return -1
            throw e
        }
        return input.use {
            try {
                dir.write(name).use { output -> pump(input, output) }
            } catch (e: IOException) {
                if (file.length() >= FAT32_LIMIT) throw FileTooLargeForDriveException(e)
                throw e
            }
        }
    }

    private fun copyIn(input: InputStream, file: File): Long {
        file.parentFile?.mkdirs()
        return input.use { i -> file.outputStream().use { o -> pump(i, o) } }
    }

    private fun pump(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        output.flush()
        return total
    }

    private fun manifest(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return """{"format":1,"app":"Obscura","created":"${iso.format(Date())}"}"""
    }

    companion object {
        const val FOLDER = "Obscura Backup"
        const val MANIFEST = "obscura-backup.json"
        const val HEADER = "vault.hdr"
        const val INDEX = "index.db"
        val SEALED_DIRS = listOf("blobs", "thumbs")

        /** FAT32 can't store a file of 4 GiB or more. */
        const val FAT32_LIMIT = 4L shl 30
        private const val RESTORE_MARGIN = 64L shl 20
        private const val MANIFEST_ROOM = 256L
    }
}
