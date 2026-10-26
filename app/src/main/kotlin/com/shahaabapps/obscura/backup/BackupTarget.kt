package com.shahaabapps.obscura.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** A file or folder at the backup destination. */
interface BackupNode {
    val name: String
    val isDirectory: Boolean
    val size: Long
    fun asDirectory(): BackupDir
    fun read(): InputStream
}

/**
 * A folder at the backup destination: a USB drive or any folder picked through the system
 * picker (SAF), or a plain directory in tests.
 */
interface BackupDir {
    val name: String
    fun list(): List<BackupNode>
    fun child(name: String): BackupNode? = list().firstOrNull { it.name == name }
    /** Returns the sub-folder, creating it if needed. */
    fun directory(name: String): BackupDir
    /** Creates or overwrites a file; [mimeType] helps other apps recognise it. */
    fun write(name: String, mimeType: String = "application/octet-stream"): OutputStream

    /**
     * Creates a file the caller already knows is not there. Looking a name up means listing the
     * whole folder, which is ruinous when thousands of files are written into one; a caller that
     * is keeping track of the names itself can say so.
     */
    fun writeNew(name: String, mimeType: String = "application/octet-stream"): OutputStream =
        write(name, mimeType)

    /**
     * Tries to give a written file its original date. Whether this is possible depends on where
     * the folder lives: a folder picked through the system picker usually will not allow it,
     * and there is no way to force it, so callers must not depend on it.
     */
    fun setModified(name: String, whenMillis: Long) = Unit
    fun delete(name: String)
    /** Free space at the destination, or null when it can't be told. */
    fun availableBytes(): Long? = null
}

/** Plain-directory destination, used by tests and as a reference implementation. */
class FileBackupDir(private val dir: File) : BackupDir {
    init {
        dir.mkdirs()
    }

    override val name: String get() = dir.name

    override fun list(): List<BackupNode> = dir.listFiles().orEmpty().map { f ->
        object : BackupNode {
            override val name = f.name
            override val isDirectory = f.isDirectory
            override val size = if (f.isFile) f.length() else 0
            override fun asDirectory(): BackupDir = FileBackupDir(f)
            override fun read(): InputStream = f.inputStream()
        }
    }

    override fun child(name: String): BackupNode? = File(dir, name).takeIf { it.exists() }?.let { f -> list().first { it.name == f.name } }

    override fun directory(name: String): BackupDir = FileBackupDir(File(dir, name))

    override fun write(name: String, mimeType: String): OutputStream = File(dir, name).outputStream()

    override fun setModified(name: String, whenMillis: Long) {
        File(dir, name).setLastModified(whenMillis)
    }

    override fun delete(name: String) {
        File(dir, name).deleteRecursively()
    }

    override fun availableBytes(): Long = dir.usableSpace
}

/** Storage Access Framework destination: a folder or USB drive the user picked. */
class SafBackupDir(
    private val context: Context,
    private val treeUri: Uri,
    private val documentId: String = DocumentsContract.getTreeDocumentId(treeUri),
    override val name: String = "",
) : BackupDir {

    private val resolver: ContentResolver get() = context.contentResolver

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)

    private inner class Node(val id: String, override val name: String, mime: String, override val size: Long) : BackupNode {
        override val isDirectory = mime == Document.MIME_TYPE_DIR
        val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        override fun asDirectory(): BackupDir = SafBackupDir(context, treeUri, id, name)
        override fun read(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException(name)
    }

    override fun list(): List<BackupNode> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val nodes = mutableListOf<BackupNode>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                nodes += Node(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3))
            }
        }
        return nodes
    }

    private val parentUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun directory(name: String): BackupDir {
        (child(name) as? Node)?.takeIf { it.isDirectory }?.let { return it.asDirectory() }
        val created = DocumentsContract.createDocument(resolver, parentUri, Document.MIME_TYPE_DIR, name)
            ?: throw FileNotFoundException("Could not create folder $name")
        return SafBackupDir(context, treeUri, DocumentsContract.getDocumentId(created), name)
    }

    override fun write(name: String, mimeType: String): OutputStream {
        val existing = child(name) as? Node
        val uri = existing?.uri
            ?: DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw FileNotFoundException("Could not create $name")
        // "wt" truncates an existing file so a shorter rewrite leaves no stale tail.
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(name)
    }

    override fun writeNew(name: String, mimeType: String): OutputStream {
        val uri = DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw FileNotFoundException("Could not create $name")
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(name)
    }

    /**
     * Most providers behind the picker, the one for phone storage and USB drives among them,
     * have no way to set a document's date and simply refuse. Worth asking in case the folder
     * is somewhere that allows it; the copy carries its own dates inside it either way.
     */
    override fun setModified(name: String, whenMillis: Long) {
        runCatching {
            val node = child(name) as? Node ?: return
            val values = android.content.ContentValues().apply {
                put(Document.COLUMN_LAST_MODIFIED, whenMillis)
            }
            resolver.update(node.uri, values, null, null)
        }
    }

    override fun delete(name: String) {
        (child(name) as? Node)?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
    }

    /**
     * Free space of the volume behind the picked folder. Phone storage and USB drives come from
     * the external storage provider, whose document ids start with the volume ("primary:…" or a
     * drive id like "1A2B-3C4D:…"); other providers don't say, and the copy simply runs.
     */
    override fun availableBytes(): Long? = runCatching {
        if (treeUri.authority != "com.android.externalstorage.documents") return@runCatching null
        val volume = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
        val storage = context.getSystemService(StorageManager::class.java)
        val match = storage.storageVolumes.firstOrNull { if (volume == "primary") it.isPrimary else it.uuid.equals(volume, ignoreCase = true) }
            ?: return@runCatching null
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) match.directory
        else if (match.isPrimary) Environment.getExternalStorageDirectory() else File("/storage/$volume")
        dir?.usableSpace?.takeIf { it > 0 }
    }.getOrNull()
}
