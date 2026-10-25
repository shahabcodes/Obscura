package com.shahaabapps.obscura

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Run against a vault that was built by the PREVIOUS release (database v3) and then upgraded in
 * place. Nothing is created or wiped here: it only opens what is on disk and checks that the
 * 3 -> 4 migration moved the GIF rows and left every byte alone.
 *
 * Needs the vault seeded by the old build first, so it is not part of the normal suite; run it
 * by class name.
 */
@RunWith(AndroidJUnit4::class)
class LegacyUpgradeVerifyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    /** name -> sha256 of the plaintext, as the old build recorded it. */
    private val expected = mapOf(
        "anim.gif" to "e3a3ae9d290431886a7934c2601c1d96a93c5e7d4ce36f6dbd26b3821cfabe74",
        "beach.jpg" to "8ee288492468fa6b3f422d3164df529d7cc8c3039fa3404a9d465d5c2449b600",
        "big.gif" to "29fd947467c4fb0865af1c14e659448810ccb63fc1aa8a929a89e0dacb9924e3",
        "broken.gif" to "ea2a7aa239724ae7e1d41ec6d8d223d1e749779f43f08c891b7563f9b645c50e",
        "broken.mp4" to "900a9ddf51f946e35191ec19fd9eb632f619d9a6b7485914f9fe7da08b991061",
        "clip.mp4" to "067a6525e9410db416f1f32ad53ba992f18725605a66b17766b8eff273d5fefa",
        "huge.gif" to "f658814a992a07484b05046c4595ce871dce25824f2db9224d044725fb0d4fd3",
        "many.pdf" to "43808a88638063dbd5fd9533cb8643b712ddf5977feb8d8bd15885fb41f34818",
        "notes.txt" to "f581e0457f56a80a010b691cba48b01b26024c4f0dfb1455aed46180d6c491d5",
        "short.mp4" to "d358786a90df7da5d1d66830d280bbeb5b9b3e13b25b3d898c8531a3e3a03f3e",
        "simple.pdf" to "a58434bf11be9ecb7b928c9c0838f5ccde91c100fbebfab71fe4b2a5afec674d",
        "tone.wav" to "fb8e8b509a81294d920e21b0e08dc0360607ca00a1f7adb740601b334562b327",
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun anOldVaultOpensWithEverythingStillThereAndGifsReclassified() = runBlocking {
        org.junit.Assume.assumeTrue("no vault from an older build on this device; seed one to run this", entry.store().exists())
        instrumentation.runOnMainSync { entry.session().lock() }

        val result = entry.session().unlockWithPassphrase(passphrase.toCharArray())
        assertEquals("the upgraded vault refused the old passphrase", UnlockResult.Success, result)
        assertTrue(entry.session().state.value is VaultState.Unlocked)

        val vault = entry.session().openVault!!
        vault.database.openHelper.readableDatabase.query("PRAGMA user_version").use {
            it.moveToFirst()
            println("UPGRADE user_version=${it.getInt(0)}")
            assertEquals("the migration did not run", 4, it.getInt(0))
        }

        val all = vault.database.files().all()
        println("UPGRADE count=${all.size}")
        assertEquals("files lost or gained across the upgrade", expected.size, all.size)

        all.sortedBy { it.name }.forEach { f ->
            val bytes = vault.files.open(f).use { s -> s.readBytes() }
            val digest = sha(bytes)
            println("UPGRADE row name=${f.name} kind=${f.kind} size=${f.sizeBytes} sha=$digest")
            assertEquals("${f.name} no longer decrypts to the same bytes", expected[f.name], digest)
        }

        // The four image/gif rows moved; nothing else did.
        assertEquals(
            listOf("anim.gif", "big.gif", "broken.gif", "huge.gif"),
            all.filter { it.kind == FileKind.GIF }.map { it.name }.sorted(),
        )
        assertEquals(listOf("beach.jpg"), all.filter { it.kind == FileKind.PHOTO }.map { it.name })
        assertEquals(3, all.count { it.kind == FileKind.VIDEO })
        assertEquals(1, all.count { it.kind == FileKind.AUDIO })
        assertEquals(3, all.count { it.kind == FileKind.DOCUMENT })

        // The PIN set by the old build still works on the new one.
        instrumentation.runOnMainSync { entry.session().lock() }
        assertEquals(UnlockResult.Success, entry.session().unlockWithPin("135790".toCharArray()))
    }
}
