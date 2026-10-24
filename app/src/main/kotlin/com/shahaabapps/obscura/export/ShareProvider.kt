package com.shahaabapps.obscura.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.VaultSession
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * One-time share links. Each shared file gets an unguessable link that works for
 * [LIFETIME_MS] and only while the vault is unlocked; every link dies the moment it locks.
 * The vault is held open for [HOLD_MS] so the receiving app has time to read the file.
 */
@Singleton
class ShareGrants @Inject constructor(private val session: VaultSession, private val autoLock: AutoLock) {
    private class Grant(val fileId: String, val expiresAt: Long)

    private val grants = ConcurrentHashMap<String, Grant>()
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    /** Pending releases of the short holds that keep the vault open for a share. */
    private val holds = mutableListOf<Runnable>()

    /** Descriptors other apps hold open; closed on lock so their file keys are dropped. */
    private val open = mutableSetOf<AutoCloseable>()

    fun track(reader: AutoCloseable) = synchronized(open) { open += reader }

    fun untrack(reader: AutoCloseable) = synchronized(open) { open -= reader }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            session.state.collect { if (it !is VaultState.Unlocked) revokeAll() }
        }
    }

    /** Links for [files], readable by whichever app receives them. */
    fun grant(files: List<VaultFile>, now: Long = System.currentTimeMillis()): List<Uri> {
        autoLock.hold()
        val release = object : Runnable {
            override fun run() {
                synchronized(holds) { if (!holds.remove(this)) return }
                autoLock.release()
            }
        }
        synchronized(holds) { holds += release }
        main.postDelayed(release, HOLD_MS)
        return files.map { file ->
            val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            grants[token] = Grant(file.id, now + LIFETIME_MS)
            // Token only: the name reaches the receiving app through DISPLAY_NAME, not the URI.
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build()
        }
    }

    /** The file id behind a link, or null if it is unknown, expired or the vault is locked. */
    fun lookup(token: String, now: Long = System.currentTimeMillis()): String? {
        val grant = grants[token] ?: return null
        if (now > grant.expiresAt || session.openVault == null) {
            grants.remove(token)
            return null
        }
        return grant.fileId
    }

    /** Kills every link, closes open readers and ends the holds (also runs when the vault locks). */
    fun revokeAll() {
        grants.clear()
        val readers = synchronized(open) { open.toList().also { open.clear() } }
        readers.forEach { runCatching { it.close() } }
        val pending = synchronized(holds) { holds.toList().also { holds.clear() } }
        pending.forEach {
            main.removeCallbacks(it)
            autoLock.release()
        }
    }

    companion object {
        const val AUTHORITY = "com.shahaabapps.Obscura.share"
        const val LIFETIME_MS = 15 * 60_000L
        const val HOLD_MS = 60_000L
    }
}

/**
 * Serves shared files to other apps, decrypting on the fly. Readers get a seekable descriptor
 * backed by the vault's segment-wise decryption, so nothing decrypted is written to storage.
 * Not exported: other apps can only read links Obscura granted to them.
 */
class ShareProvider : ContentProvider() {

    private val entry by lazy { EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, VaultEntryPoint::class.java) }
    private val session: VaultSession get() = entry.session()
    private val grants: ShareGrants get() = entry.shareGrants()
    private val readerThread by lazy { HandlerThread("obscura-share").apply { start() } }

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): VaultFile? {
        val token = uri.pathSegments.firstOrNull() ?: return null
        val id = grants.lookup(token) ?: return null
        val vault = session.openVault ?: return null
        return runBlocking { vault.database.files().get(id) }
    }

    override fun getType(uri: Uri): String? = resolve(uri)?.mimeType

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.sizeBytes })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Shared files are read-only")
        val file = resolve(uri) ?: throw FileNotFoundException("This link has expired")
        val token = uri.pathSegments.first()
        val vault = session.openVault ?: throw FileNotFoundException("The vault is locked")
        val raw = FileChannel.open(vault.files.blobFile(file.id).toPath(), StandardOpenOption.READ)
        val plain = vault.keys.fileCipher.seekableChannel(file.wrappedKey, file.id, raw)
        // Closing drops the decrypting channel, and with it the file key it holds.
        val reader = AutoCloseable {
            runCatching { plain.close() }
            runCatching { raw.close() }
        }
        grants.track(reader)
        val storage = requireNotNull(context).getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = file.sizeBytes

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                // Only while the very vault that granted the link is open and the link is live:
                // not after a lock, not past expiry, and never from inside another (decoy) vault.
                if (session.openVault !== vault || grants.lookup(token) == null) {
                    reader.close()
                    throw ErrnoException("read", OsConstants.EIO)
                }
                if (offset >= file.sizeBytes) return 0
                return try {
                    plain.position(offset)
                    val target = ByteBuffer.wrap(data, 0, size)
                    while (target.hasRemaining()) if (plain.read(target) <= 0) break
                    size - target.remaining()
                } catch (e: Exception) {
                    throw ErrnoException("read", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                grants.untrack(reader)
                reader.close()
            }
        }, Handler(readerThread.looper))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** A share intent for [uris], with read access granted only to the app the user picks. */
fun shareIntent(uris: List<Uri>, files: List<VaultFile>): android.content.Intent {
    val mime = files.map { it.mimeType }.distinct().singleOrNull()
        ?: files.map { it.mimeType.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" }
        ?: "*/*"
    val send = if (uris.size == 1) {
        android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
    } else {
        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = mime
    send.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
