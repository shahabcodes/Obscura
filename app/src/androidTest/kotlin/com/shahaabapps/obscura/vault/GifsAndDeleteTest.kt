package com.shahaabapps.obscura.vault

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.ui.gallery.GalleryLogic
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** GIFs arriving as their own kind (including ones sealed before), and deleting the whole vault. */
@RunWith(AndroidJUnit4::class)
class GifsAndDeleteTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    /** A real, if tiny, GIF. */
    private val gifBytes: ByteArray = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)

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
    fun aGifIsSealedAsAGifAndFiltersAsOne() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val vault = entry.session().openVault!!
        val gif = vault.files.put(gifBytes.inputStream(), IncomingFile("dance.gif", "image/gif", 1_700_000_000_000))
        val photo = vault.files.put(gifBytes.inputStream(), IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000))

        assertEquals(FileKind.GIF, gif.kind)
        assertEquals(FileKind.PHOTO, photo.kind)
        val all = vault.database.files().all()
        assertEquals(listOf("dance.gif"), GalleryLogic.media(all, GalleryFilter.GIFS).map { it.name })
        assertEquals(2, GalleryLogic.media(all, GalleryFilter.ALL).size)
        // A GIF still gets a still preview for the grid.
        assertNotNull("thumbnail for the GIF", vault.thumbnails.thumbnail(gif))
    }

    /**
     * A vault sealed before GIFs had their own kind: the rows are rewritten the next time it
     * opens, so old GIFs appear under the new category without being re-imported.
     */
    @Test
    fun gifsSealedBeforeMoveToTheNewKindOnUnlock() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val vault = entry.session().openVault!!
        val gif = vault.files.put(gifBytes.inputStream(), IncomingFile("old.gif", "image/gif", 1_700_000_000_000))
        vault.files.put(gifBytes.inputStream(), IncomingFile("photo.jpg", "image/jpeg", 1_700_000_000_000))

        // Put the database back how the previous version left it: GIFs counted as photos.
        vault.database.openHelper.writableDatabase.apply {
            execSQL("UPDATE files SET kind = 'PHOTO' WHERE mimeType = 'image/gif'")
            execSQL("PRAGMA user_version = 3")
        }
        entry.session().lock()

        assertEquals(UnlockResult.Success, entry.session().unlockWithPassphrase(passphrase.toCharArray()))
        val reopened = entry.session().openVault!!.database.files()
        assertEquals(FileKind.GIF, reopened.get(gif.id)!!.kind)
        assertEquals(1, reopened.all().count { it.kind == FileKind.GIF })
        assertEquals(1, reopened.all().count { it.kind == FileKind.PHOTO })
    }

    @Test
    fun deletingTheVaultNeedsThePassphraseAndLeavesNothing() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val vault = entry.session().openVault!!
        vault.files.put(gifBytes.inputStream(), IncomingFile("secret.gif", "image/gif", 1_700_000_000_000))
        val store = entry.store()
        assertTrue(store.exists())

        // A wrong passphrase deletes nothing and counts as a wrong attempt.
        assertEquals(UnlockResult.Wrong(3), entry.session().deleteVault("not it".toCharArray()))
        assertTrue("still there after a wrong passphrase", store.exists())
        assertTrue(entry.session().state.value is VaultState.Unlocked)

        assertEquals(UnlockResult.Success, entry.session().deleteVault(passphrase.toCharArray()))
        assertFalse("the vault is gone", store.exists())
        assertFalse("and its files with it", store.blobDir.exists())
        assertEquals(VaultState.NoVault, entry.session().state.value)
    }

    /**
     * Starting over in the same sitting. The new vault has new keys, so anything left behind by
     * the old one — above all its encrypted index — must be gone rather than reopened with a key
     * that cannot read it.
     */
    @Test
    fun aNewVaultCanBeMadeStraightAfterDeletingTheOldOne() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        entry.session().openVault!!.files.put(
            gifBytes.inputStream(),
            IncomingFile("first.gif", "image/gif", 1_700_000_000_000),
        )
        assertEquals(UnlockResult.Success, entry.session().deleteVault(passphrase.toCharArray()))

        // A different passphrase, so a surviving index would be unreadable rather than merely stale.
        val second = "harbour lantern cobalt meadow pigment"
        entry.session().create(second.toCharArray())
        val vault = entry.session().openVault!!
        assertTrue(entry.session().state.value is VaultState.Unlocked)
        assertEquals("the new vault starts empty", 0, vault.database.files().all().size)

        val sealed = vault.files.put(
            gifBytes.inputStream(),
            IncomingFile("second.gif", "image/gif", 1_700_000_000_000),
        )
        assertEquals(listOf("second.gif"), vault.database.files().all().map { it.name })
        // And it reads back, so the new key and the new index agree.
        assertEquals(
            gifBytes.toList(),
            vault.files.open(vault.database.files().get(sealed.id)!!).use { it.readBytes() }.toList(),
        )

        // Locking and unlocking the new vault works, and the old passphrase does not open it.
        entry.session().lock()
        assertTrue(entry.session().unlockWithPassphrase(passphrase.toCharArray()) is UnlockResult.Wrong)
        assertEquals(UnlockResult.Success, entry.session().unlockWithPassphrase(second.toCharArray()))
        assertEquals(1, entry.session().openVault!!.database.files().all().size)
    }
}
