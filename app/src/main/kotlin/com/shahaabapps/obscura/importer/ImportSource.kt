package com.shahaabapps.obscura.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream

/** One file to bring into the vault. */
interface ImportSource {
    val displayName: String
    val mimeType: String
    val sizeBytes: Long?
    val lastModified: Long?

    /** Content URI when the file came from the system picker; used for video metadata. */
    val uri: Uri? get() = null

    fun open(): InputStream

    /** Deletes the original file. Returns false when its location doesn't allow deleting. */
    fun deleteOriginal(): Boolean
}

/** A file chosen through the system picker (Storage Access Framework). */
class DocumentSource(
    private val resolver: ContentResolver,
    override val uri: Uri,
    override val displayName: String,
    override val mimeType: String,
    override val sizeBytes: Long?,
    override val lastModified: Long?,
    private val canDelete: Boolean,
) : ImportSource {

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Could not open $displayName")

    override fun deleteOriginal(): Boolean = canDelete && DocumentsContract.deleteDocument(resolver, uri)
}

/** Turns picker results into [ImportSource]s, walking folders recursively. */
object SourceResolver {

    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )

    fun fromDocuments(context: Context, uris: List<Uri>): List<ImportSource> =
        uris.mapNotNull { uri -> describe(context.contentResolver, uri) }

    /** Every file under a picked folder or USB drive, including subfolders. */
    fun fromTree(context: Context, treeUri: Uri): List<ImportSource> {
        val resolver = context.contentResolver
        val result = mutableListOf<ImportSource>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(children, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(2) ?: "application/octet-stream"
                    if (mime == Document.MIME_TYPE_DIR) {
                        pending.add(id)
                        continue
                    }
                    val name = c.getString(1) ?: id
                    // Skip hidden system files such as .thumbnails and .nomedia.
                    if (name.startsWith(".")) continue
                    result += DocumentSource(
                        resolver = resolver,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = if (c.isNull(3)) null else c.getLong(3),
                        lastModified = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 },
                        canDelete = c.getInt(5) and Document.FLAG_SUPPORTS_DELETE != 0,
                    )
                }
            }
        }
        return result
    }

    private fun describe(resolver: ContentResolver, uri: Uri): ImportSource? =
        resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            DocumentSource(
                resolver = resolver,
                uri = uri,
                displayName = c.getString(1) ?: uri.lastPathSegment ?: "file",
                mimeType = c.getString(2) ?: resolver.getType(uri) ?: "application/octet-stream",
                sizeBytes = if (c.isNull(3)) null else c.getLong(3),
                lastModified = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 },
                canDelete = c.getInt(5) and Document.FLAG_SUPPORTS_DELETE != 0,
            )
        }
}
