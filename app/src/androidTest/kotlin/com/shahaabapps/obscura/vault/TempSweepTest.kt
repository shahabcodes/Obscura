package com.shahaabapps.obscura.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Killing the app mid-import leaves a half-written file behind, because the cleanup never runs.
 * It is encrypted, but it would sit there for good on a phone that is already short of space.
 */
@RunWith(AndroidJUnit4::class)
class TempSweepTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        // Closing the index leaves work queued on the main thread. It has to run before the
        // files it points at are deleted, or Room reopens a vault that is no longer there and
        // brings the whole process down with it.
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() = wipe()

    @After
    fun tearDown() = wipe()

    @Test
    fun unlockingClearsWhatAKilledImportLeftBehind() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val sealed = entry.session().openVault!!.files.put(
            "a real file".toByteArray().inputStream(),
            IncomingFile("keep.txt", "text/plain", 1_700_000_000_000),
        )
        val store = entry.store()

        // What a force-stop halfway through sealing a file leaves on disk: written by a run
        // of the app that is no longer alive, so it is dated before this process started.
        store.tempDir.mkdirs()
        val orphan = File(store.tempDir, "63fd6db124d774451e78d42f01ee9bc6.part")
        orphan.writeBytes(ByteArray(6 * 1024 * 1024))
        assertTrue(orphan.setLastModified(System.currentTimeMillis() - 60 * 60_000))
        assertTrue(orphan.isFile)

        // A file this run is still writing: an import that a panic lock interrupted.
        val beingWritten = File(store.tempDir, "0f0e0d0c0b0a09080706050403020100.part")
        beingWritten.writeBytes(ByteArray(1024))

        entry.session().lock()
        assertTrue("still there while locked", orphan.isFile)

        assertEquals(UnlockResult.Success, entry.session().unlockWithPassphrase(passphrase.toCharArray()))
        assertFalse("the leftover is swept on unlock", orphan.exists())
        assertTrue("a file this run is still writing is left alone", beingWritten.isFile)
        // The sealed file itself is untouched.
        val vault = entry.session().openVault!!
        assertEquals(1, vault.database.files().all().size)
        assertEquals("a real file", vault.files.open(vault.database.files().get(sealed.id)!!).use { String(it.readBytes()) })
    }
}
