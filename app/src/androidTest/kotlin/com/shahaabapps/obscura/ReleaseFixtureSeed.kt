package com.shahaabapps.obscura

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: a way to put a known vault on the device so the app can then be driven by hand
 * (uiautomator, rotation, screen off, process death) against real media. @Ignore keeps it out of
 * the suite; run it by name:
 *
 *   adb shell am instrument -w -e class com.shahaabapps.obscura.ReleaseFixtureSeed \
 *       com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It needs the fixtures copied into the app's own files dir first: push them to a temp dir on
 * the device, then copy them into "files/fixtures" inside the app's data dir with run-as.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseFixtureSeed {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val dir = File(context.filesDir, "fixtures")

    private val fixtures = listOf(
        "long.mp4" to "video/mp4",
        "anim.gif" to "image/gif",
        "beach.jpg" to "image/jpeg",
        "huge.gif" to "image/gif",
        "clip.mp4" to "video/mp4",
        "big.gif" to "image/gif",
        "tiny.mp4" to "video/mp4",
        "tone.wav" to "audio/x-wav",
        "simple.pdf" to "application/pdf",
        "notes.txt" to "text/plain",
        // Optional, for the memory journeys: a GIF far larger than any phone wants in RAM.
        "giant.gif" to "image/gif",
    )

    @Test

    fun seed() = runBlocking {
        instrumentation.runOnMainSync {
            entry.session().lock()
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
        entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
        entry.session().setPin("135790".toCharArray())
        val vault = entry.session().openVault!!
        var taken = 1_700_000_000_000L
        fixtures.forEach { (name, mime) ->
            val f = File(dir, name)
            if (!f.exists()) {
                println("SEED skipping $name: not pushed")
                return@forEach
            }
            vault.files.put(f.inputStream(), IncomingFile(name, mime, taken))
            taken -= 60_000
        }
        println("SEEDED ${vault.database.files().all().size} files")
        entry.session().lock()
    }
}
