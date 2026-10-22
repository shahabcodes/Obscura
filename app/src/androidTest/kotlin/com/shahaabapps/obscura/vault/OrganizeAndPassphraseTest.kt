package com.shahaabapps.obscura.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import com.shahaabapps.obscura.crypto.RecoveryKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrganizeAndPassphraseTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var clock: FakeClock

    private class MemoryStorage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private class FakeClock(var now: Long = 1_000) : DeviceClock {
        override fun elapsedRealtime() = now
        override fun bootCount() = 1
    }

    private val storage = MemoryStorage()
    private val binding = KeystoreBinding("obscura.test.org.pin")

    private fun newSession() = VaultSession(
        context, VaultStore(root), LockoutPolicy(storage, clock), binding, KeystoreGate("obscura.test.org.bio"), KdfParams(1024, 1, 1),
    )

    @Before
    fun setUp() {
        root = File(context.cacheDir, "org-test-${System.nanoTime()}")
        clock = FakeClock()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        binding.delete()
    }

    private fun put(session: VaultSession, name: String, mime: String, taken: Long, size: Int = 10): VaultFile = runBlocking {
        session.openVault!!.files.put(ByteArray(size) { (it + name.length).toByte() }.inputStream(), IncomingFile(name, mime, taken))
    }

    @Test
    fun albumsCountCoverAndDeleteKeepsFiles() = runBlocking {
        val session = newSession()
        session.create("passphrase".toCharArray())
        val db = session.openVault!!.database
        val old = put(session, "old.jpg", "image/jpeg", 1_000, 100)
        val new = put(session, "new.jpg", "image/jpeg", 9_000, 200)
        val doc = put(session, "notes.pdf", "application/pdf", 20_000, 50)
        db.albums().insert(Album("a1", "Trips", 0xFF3B5BFF, 1))
        listOf(old, new, doc).forEach { db.files().setAlbum(it.id, "a1") }

        val summary = db.albums().observeSummaries().first().single()
        assertEquals(3, summary.count)
        assertEquals(350L, summary.bytes)
        assertEquals("cover is the newest photo, not the newer document", new.id, summary.coverId)
        assertEquals(3, db.files().observeInAlbum("a1").first().size)

        db.albums().delete("a1")
        assertTrue(db.albums().observeSummaries().first().isEmpty())
        assertEquals(3, db.files().observeAll().first().size)
        assertNull(db.files().get(old.id)!!.albumId)
        session.lock()
    }

    @Test
    fun searchIsCaseInsensitiveAndTreatsWildcardsLiterally() = runBlocking {
        val session = newSession()
        session.create("passphrase".toCharArray())
        put(session, "Passport scan.pdf", "application/pdf", 1)
        put(session, "beach_2024.jpg", "image/jpeg", 2)
        put(session, "50% off.txt", "text/plain", 3)
        put(session, "beachside.jpg", "image/jpeg", 4)
        val files = session.openVault!!.database.files()
        suspend fun names(q: String) = files.search(likePattern(q)).first().map { it.name }.sorted()

        assertEquals(listOf("Passport scan.pdf"), names("passport"))
        assertEquals(listOf("50% off.txt"), names("50%"))
        assertEquals(listOf("beach_2024.jpg"), names("_2"))
        assertEquals(listOf("beach_2024.jpg", "beachside.jpg"), names("BEACH"))
        assertTrue(names("nothing-like-this").isEmpty())
        session.lock()
    }

    @Test
    fun changingThePassphraseKeepsEverythingElseWorking() = runBlocking {
        val first = newSession()
        val recovery = RecoveryKey.parse(first.create("old passphrase".toCharArray()).format())
        first.setPin("135790".toCharArray())

        assertEquals(UnlockResult.Wrong(3), first.changePassphrase("not it".toCharArray(), "new passphrase".toCharArray()))
        assertEquals(1, storage.failures)
        assertEquals(UnlockResult.Success, first.changePassphrase("old passphrase".toCharArray(), "new passphrase".toCharArray()))
        assertEquals(0, storage.failures)
        first.lock()

        val again = newSession()
        assertEquals(UnlockResult.Wrong(3), again.unlockWithPassphrase("old passphrase".toCharArray()))
        assertEquals(UnlockResult.Success, again.unlockWithPassphrase("new passphrase".toCharArray()))
        again.lock()
        assertEquals(UnlockResult.Success, again.unlockWithRecoveryKey(recovery.format()))
        again.lock()
        assertEquals(UnlockResult.Success, again.unlockWithPin("135790".toCharArray()))
        again.lock()
    }

    @Test
    fun guessingTheCurrentPassphraseHitsTheLockout() = runBlocking {
        val session = newSession()
        session.create("right".toCharArray())
        repeat(4) { session.changePassphrase("wrong".toCharArray(), "whatever words here".toCharArray()) }
        assertTrue(session.changePassphrase("wrong".toCharArray(), "whatever words here".toCharArray()) is UnlockResult.LockedOut)
        assertTrue("even the right one waits", session.changePassphrase("right".toCharArray(), "whatever words here".toCharArray()) is UnlockResult.LockedOut)
        session.lock()
    }

    /** A vault made by 0.2–0.5 (database version 1) must open in this version with nothing lost. */
    @Test
    fun versionOneDatabaseUpgradesInPlace() = runBlocking {
        val session = newSession()
        session.create("passphrase".toCharArray())
        val photo = put(session, "keep-me.jpg", "image/jpeg", 123)
        val dbKey = session.openVault!!.keys.deriveKey("index-db")
        session.lock()

        // Rebuild the index exactly as version 1 left it: version 1 schema, no albums table.
        val dbFile = VaultStore(root).databaseFile
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        System.loadLibrary("sqlcipher")
        SQLiteDatabase.openOrCreateDatabase(dbFile, dbKey, null, null, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `files` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `kind` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL, `sealedAt` INTEGER NOT NULL, `wrappedKey` BLOB NOT NULL, `sha256` TEXT NOT NULL, `width` INTEGER, `height` INTEGER, `durationMs` INTEGER, `favorite` INTEGER NOT NULL, `albumId` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_kind` ON `files` (`kind`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_takenAt` ON `files` (`takenAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_sha256` ON `files` (`sha256`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '82ad84ca68309397242b1dd9b056697e')")
            db.execSQL(
                "INSERT INTO files VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, NULL)",
                arrayOf<Any>(photo.id, photo.name, photo.mimeType, photo.kind.name, photo.sizeBytes, photo.takenAt, photo.sealedAt, photo.wrappedKey, photo.sha256),
            )
            db.version = 1
        }

        val upgraded = newSession()
        assertEquals(UnlockResult.Success, upgraded.unlockWithPassphrase("passphrase".toCharArray()))
        val db = upgraded.openVault!!.database
        assertEquals("keep-me.jpg", db.files().get(photo.id)!!.name)
        db.albums().insert(Album("new", "Works after upgrade", 0, 1))
        db.files().setAlbum(photo.id, "new")
        assertEquals(1, db.albums().observeSummaries().first().single().count)
        upgraded.lock()
    }
}
