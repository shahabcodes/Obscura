package com.shahaabapps.obscura.testing

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * A real [DocumentsProvider] over a plain directory, for tests only; it is declared in the debug
 * manifest and is never part of a release build.
 *
 * Obscura reaches a folder the user picked through the Storage Access Framework, so importing a
 * folder and exporting back into one run through `DocumentsContract` and a provider owned by
 * some other app. Standing a `java.io.File` in for that in tests skips exactly the code a real
 * folder picker exercises. Pointing this provider at a temporary directory lets those paths be
 * tested as they actually run.
 */
class LocalFilesProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun fileOf(documentId: String): File {
        val root = requireNotNull(root) { "No folder has been set for the test provider" }
        if (documentId == ROOT_ID) return root
        val file = File(root, documentId.removePrefix("$ROOT_ID/"))
        // A document id is a path relative to the root and must stay inside it. The separator
        // matters: without it a sibling folder whose name merely starts the same way passes.
        if (!inside(root, file)) throw FileNotFoundException(documentId)
        return file
    }

    /** True when [file] really sits under [root], rather than merely sharing its first letters. */
    private fun inside(root: File, file: File): Boolean {
        val top = root.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar
        return file.canonicalPath == root.canonicalPath || file.canonicalPath.startsWith(top)
    }

    private fun idOf(file: File): String {
        val root = requireNotNull(root)
        if (file == root) return ROOT_ID
        return "$ROOT_ID/${file.relativeTo(root).invariantSeparatorsPath}"
    }

    /**
     * Adds [file] as a row. The values are placed by the cursor's own column names, since a
     * caller asks for the columns it wants in the order it wants them; filling them in a fixed
     * order would quietly hand back a size where a flag was asked for.
     */
    private fun MatrixCursor.add(file: File) {
        val isDirectory = file.isDirectory
        val values = mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to idOf(file),
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (file == root) "Test folder" else file.name,
            DocumentsContract.Document.COLUMN_SIZE to file.length(),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            DocumentsContract.Document.COLUMN_MIME_TYPE to
                if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(file.name),
            DocumentsContract.Document.COLUMN_FLAGS to
                if (isDirectory) {
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                } else {
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                        DocumentsContract.Document.FLAG_SUPPORTS_REMOVE
                },
        )
        newRow().apply { columnNames.forEach { add(it, values[it]) } }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            if (root == null) return@apply
            val values = mapOf(
                DocumentsContract.Root.COLUMN_ROOT_ID to ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE to "Obscura test files",
                DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_CREATE,
                DocumentsContract.Root.COLUMN_ICON to android.R.drawable.ic_menu_save,
            )
            newRow().apply { columnNames.forEach { add(it, values[it]) } }
        }

    /**
     * Required for a folder handed over as a tree: the system asks this before letting a caller
     * touch anything reached through it, and the default answer is no. Without it a folder can
     * be listed but nothing inside it can be written or deleted.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = fileOf(parentDocumentId).canonicalPath
        val child = fileOf(documentId).canonicalPath
        child != parent && child.startsWith(parent.trimEnd(File.separatorChar) + File.separatorChar)
    } catch (e: FileNotFoundException) {
        false
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { add(fileOf(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        fileOf(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { add(it) }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        val file = File(parent, displayName)
        if (!inside(requireNotNull(root), file)) throw FileNotFoundException(displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!file.mkdirs() && !file.isDirectory) throw FileNotFoundException("Could not make $displayName")
        } else {
            // A real provider keeps both names rather than overwriting, and tells the caller
            // which one it settled on.
            var target = file
            var n = 1
            while (target.exists()) {
                val stem = displayName.substringBeforeLast('.', displayName)
                val extension = displayName.substringAfterLast('.', "")
                target = File(parent, stem + " (" + (++n) + ")" + if (extension.isEmpty()) "" else ".$extension")
            }
            if (!target.createNewFile()) throw FileNotFoundException("Could not make $displayName")
            return idOf(target)
        }
        return idOf(file)
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun getDocumentType(documentId: String): String {
        val file = fileOf(documentId)
        return if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(file.name)
    }

    companion object {
        const val ROOT_ID = "root"

        /** The directory this provider stands over. Tests set it before using the provider. */
        @Volatile
        var root: File? = null

        fun authority(packageName: String) = "$packageName.testfiles"

        /** A tree uri for the whole folder, as the system picker would hand back. */
        fun treeUri(packageName: String): Uri =
            DocumentsContract.buildTreeDocumentUri(authority(packageName), ROOT_ID)

        private fun mimeOf(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
