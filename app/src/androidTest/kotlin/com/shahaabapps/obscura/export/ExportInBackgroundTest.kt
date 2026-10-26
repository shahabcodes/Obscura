package com.shahaabapps.obscura.export

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.backup.FileBackupDir
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An export of a couple of hundred gigabytes runs for hours, and nobody watches a phone for
 * hours. It has to keep going once the app is left, and say so while it does.
 */
@RunWith(AndroidJUnit4::class)
class ExportInBackgroundTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private lateinit var drive: File

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        // Closing the index leaves work queued on the main thread. It has to run before the
        // files it points at are deleted, or Room reopens a vault that is no longer there and
        // brings the whole process down with it.
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        wipe()
        drive = File(context.cacheDir, "background-export").apply { deleteRecursively(); mkdirs() }
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
    }

    @After
    fun tearDown() {
        entry.exportManager().cancel()
        // Wait for the service to let go, then put the app back in front. This test walks away
        // from the app on purpose; leaving it there would mean every screen test after it found
        // no activity to look at.
        val until = System.currentTimeMillis() + 15_000
        while (entry.exportManager().progress.value.running && System.currentTimeMillis() < until) {
            Thread.sleep(25)
        }
        ActivityScenario.launch(com.shahaabapps.obscura.MainActivity::class.java).close()
        drive.deleteRecursively()
        wipe()
    }

    private fun seal(name: String, bytes: ByteArray) = runBlocking {
        entry.session().openVault!!.files.put(
            bytes.inputStream(),
            IncomingFile(name, "application/octet-stream", 1_760_000_000_000),
        )
    }

    @Test
    fun itKeepsGoingAfterTheAppIsLeftAndHoldsItselfUp() {
        // Enough that it is still going while we walk away from the app.
        repeat(90) { i -> seal("b$i.bin", Random(i).nextBytes(700_000)) }

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = true))

        // Watch from the moment it starts: it must hold itself up with a foreground service and
        // tell the user, or Android would be free to kill it the moment the app is left.
        val activity = context.getSystemService(android.app.ActivityManager::class.java)
        var sawService = false
        var sawNotification = false
        var wentHome = false
        var doneWhenLeft = 0
        var advancedWhileAway = false

        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val progress = entry.exportManager().progress.value
            if (!sawService) {
                sawService = activity.getRunningServices(50).any {
                    it.service.className == ExportService::class.java.name && it.foreground
                }
            }
            if (!sawNotification) {
                sawNotification = context.getSystemService(NotificationManager::class.java)
                    .activeNotifications.any { it.packageName == context.packageName }
            }
            // Once it is properly under way, leave the app and see whether it carries on.
            if (!wentHome && progress.running && progress.done >= 2) {
                wentHome = true
                doneWhenLeft = progress.done
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            if (wentHome && progress.done > doneWhenLeft) advancedWhileAway = true
            if (progress.finished && !progress.running) break
            Thread.sleep(10)
        }

        val result = entry.exportManager().progress.value
        assertTrue("it never ran as a foreground service", sawService)
        assertTrue("nothing told the user it was running", sawNotification)
        assertTrue("it stopped when the app was left", advancedWhileAway)
        assertEquals("every file was copied", 90, result.exported)
        assertEquals(90, drive.walkTopDown().count { it.isFile })
    }

    /** The vault stays open while it runs, and locks again once it is done. */
    @Test
    fun theVaultStaysOpenWhileItRunsAndLocksAfterwards() {
        repeat(8) { i -> seal("h$i.bin", Random(i).nextBytes(300_000)) }

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 60_000
        var samples = 0
        while (System.currentTimeMillis() < until) {
            val progress = entry.exportManager().progress.value
            if (progress.running) {
                // It cannot read the files without the vault, so it must still be open.
                assertTrue("the vault closed mid-export", entry.session().openVault != null)
                samples++
            }
            if (progress.finished && !progress.running) break
            Thread.sleep(20)
        }
        assertTrue("never caught it running", samples > 0)
        assertEquals(8, entry.exportManager().progress.value.exported)

        // And the hold is given back, so locking works straight afterwards.
        instrumentation.runOnMainSync { entry.session().lock() }
        assertTrue("the vault should lock once the export is done", entry.session().openVault == null)
    }
}
