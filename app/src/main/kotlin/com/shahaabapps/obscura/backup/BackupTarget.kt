package com.shahaabapps.obscura.backup

import android.content.ContentResolver
import android.net.Uri
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
    /** Creates or overwrites a file. */
    fun write(name: String): OutputStream
    fun delete(name: String)
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

    override fun write(name: String): OutputStream = File(dir, name).outputStream()

    override fun delete(name: String) {
        File(dir, name).deleteRecursively()
    }
}

/** Storage Access Framework destination: a folder or USB drive the user picked. */
class SafBackupDir(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String = DocumentsContract.getTreeDocumentId(treeUri),
    override val name: String = "",
) : BackupDir {

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)

    private inner class Node(val id: String, override val name: String, mime: String, override val size: Long) : BackupNode {
        override val isDirectory = mime == Document.MIME_TYPE_DIR
        val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        override fun asDirectory(): BackupDir = SafBackupDir(resolver, treeUri, id, name)
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
        return SafBackupDir(resolver, treeUri, DocumentsContract.getDocumentId(created), name)
    }

    override fun write(name: String): OutputStream {
        val existing = child(name) as? Node
        val uri = existing?.uri
            ?: DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", name)
            ?: throw FileNotFoundException("Could not create $name")
        // "wt" truncates an existing file so a shorter rewrite leaves no stale tail.
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(name)
    }

    override fun delete(name: String) {
        (child(name) as? Node)?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
    }
}
