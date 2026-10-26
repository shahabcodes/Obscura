package com.shahaabapps.obscura.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check of anything: it leaves a vault behind, with known files in it, for the real app to
 * be driven by hand afterwards — force-stopped mid-export, panic-locked, and so on, which cannot
 * be done from inside the process under test because the process goes with it.
 *
 * It writes what it sealed to the device so the destination can be compared against it later.
 * Run it on its own; it deliberately does not tidy up.
 */
@RunWith(AndroidJUnit4::class)
class ExportHardKillSeed {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"

    @Test
    fun seedAVaultOfKnownFiles() {
        instrumentation.runOnMainSync { entry.session().lock() }
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            entry.session().reload()
        }
        runBlocking { entry.session().create(passphrase.toCharArray()) }

        val manifest = StringBuilder()
        runBlocking {
            val vault = entry.session().openVault!!
            repeat(COUNT) { i ->
                val bytes = Random(9_000 + i).nextBytes(SIZE)
                val name = "seed %02d.bin".format(i)
                vault.files.put(bytes.inputStream(), IncomingFile(name, "application/octet-stream", taken(i)))
                manifest.append(name).append(' ')
                    .append(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    .append('\n')
            }
        }
        File(context.filesDir.parentFile, "seed-manifest.txt").writeText(manifest.toString())
        assertEquals(COUNT, runBlocking { entry.session().openVault!!.database.files().count() })
        instrumentation.runOnMainSync { entry.session().lock() }
    }

    private fun taken(i: Int) =
        LocalDateTime.of(2026, 1 + i % 12, 1 + i % 28, 9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        const val COUNT = 24
        const val SIZE = 1_500_000
    }
}
