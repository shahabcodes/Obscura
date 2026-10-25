package com.shahaabapps.obscura.vault

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The edges of the 3 -> 4 migration. A picker does not promise a canonical MIME type: it can hand
 * over "image/GIF" or "image/gif; charset=binary", and [FileStore.put] stores whatever it was
 * given. New imports are fine because [FileStore.kindOf] normalises, but the migration matches
 * the stored string exactly, so rows sealed by an older release with a non-canonical type are
 * left behind.
 */
@RunWith(AndroidJUnit4::class)
class GifMigrationEdgesTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private val gifBytes: ByteArray =
        Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before fun setUp() = wipe()
    @After fun tearDown() = wipe()

    /** A new import is classified by the normalised type, whatever the picker's spelling. */
    @Test
    fun aGifImportedWithAnAwkwardMimeTypeIsStillAGif() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val files = entry.session().openVault!!.files
        listOf("image/GIF", "image/gif; charset=binary", " image/gif ").forEach { mime ->
            val sealed = files.put(gifBytes.inputStream(), IncomingFile("odd.gif", mime, 1_700_000_000_000))
            assertEquals("kind for \"$mime\"", FileKind.GIF, sealed.kind)
        }
    }

    /**
     * The same awkward types, but sealed before the upgrade. This is what a real vault can hold,
     * and the migration has to find those rows too.
     */
    @Test
    fun oldRowsWithANonCanonicalGifTypeAreMigratedToo() = runBlocking {
        entry.session().create(passphrase.toCharArray())
        val vault = entry.session().openVault!!
        val plain = vault.files.put(gifBytes.inputStream(), IncomingFile("plain.gif", "image/gif", 1_700_000_000_000))
        val odd = vault.files.put(gifBytes.inputStream(), IncomingFile("odd.gif", "image/GIF", 1_700_000_000_000))
        val withParam = vault.files.put(
            gifBytes.inputStream(),
            IncomingFile("param.gif", "image/gif; charset=binary", 1_700_000_000_000),
        )

        // Put the database back how the previous release left it: every GIF counted as a photo,
        // mimeType stored exactly as the picker spelled it, schema version 3.
        vault.database.openHelper.writableDatabase.apply {
            execSQL("UPDATE files SET kind = 'PHOTO'")
            execSQL("PRAGMA user_version = 3")
        }
        entry.session().lock()
        assertEquals(UnlockResult.Success, entry.session().unlockWithPassphrase(passphrase.toCharArray()))

        val dao = entry.session().openVault!!.database.files()
        assertEquals("the canonical row", FileKind.GIF, dao.get(plain.id)!!.kind)
        assertEquals("\"image/GIF\" row", FileKind.GIF, dao.get(odd.id)!!.kind)
        assertEquals("\"image/gif; charset=binary\" row", FileKind.GIF, dao.get(withParam.id)!!.kind)
    }
}
