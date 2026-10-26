package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.backup.FileBackupDir
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultSettings
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real person interrupting the app halfway through something: the vault being destroyed from
 * under a screen that is watching the index, and the screen going off while an export is still
 * copying.
 *
 * The cases here are the ones the existing crash-fix tests do not reach — a wipe after ten wrong
 * attempts (which tears the vault down from a background thread, unlike the orderly delete), a
 * lock landing during an active scroll, and what the lock does once long work finishes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ExportAndWipeInterruptionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var drive: File

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun lockoutPrefs() = context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE)

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            lockoutPrefs().edit().clear().commit()
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        wipe()
        TestScreen.wake()
        drive = File(context.cacheDir, "export-interrupt").apply { deleteRecursively(); mkdirs() }
        runBlocking {
            entry.session().create(passphrase.toCharArray())
            val files = entry.session().openVault!!.files
            repeat(24) { i ->
                files.put(
                    jpeg(Color.rgb(20 + i * 9, 90, 180)).inputStream(),
                    IncomingFile("photo$i.jpg", "image/jpeg", 1_760_000_000_000 + i * 1_000L),
                )
            }
        }
    }

    @After
    fun tearDown() {
        entry.exportManager().cancel()
        entry.settings().wipeAfterTen = false
        entry.autoLock().delayMs = AutoLock.DEFAULT_DELAY_MS
        scenario?.close()
        drive.deleteRecursively()
        TestScreen.wake()
        wipe()
    }

    private fun openGallery() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)
    }

    /** Puts the counter one wrong attempt away from the wipe, with no delay standing in the way. */
    private fun armWipeAfterTen() {
        entry.settings().wipeAfterTen = true
        lockoutPrefs().edit()
            .putInt("failures", VaultSettings.WIPE_THRESHOLD - 1)
            .putLong("lockedUntil", 0)
            .putInt("bootCount", -1)
            .commit()
    }

    private fun awaitVaultGone(timeoutMs: Long = 20_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (entry.session().state.value is VaultState.NoVault) return
            Thread.sleep(25)
        }
        throw AssertionError("the vault was never wiped: ${entry.session().state.value}")
    }

    /**
     * Wipe-after-ten with the gallery on screen. Unlike the orderly delete, this tears the store
     * down from the attempt's own background thread while the grid is still watching the index.
     */
    @Test
    fun theWipeAfterTenWhileTheGalleryIsWatchingDoesNotBringTheAppDown() {
        openGallery()
        armWipeAfterTen()

        val result = runBlocking { entry.session().unlockWithPassphrase("not the passphrase".toCharArray()) }
        assertEquals("the tenth wrong attempt should have wiped", "Wiped", result::class.simpleName)
        awaitVaultGone()

        // The app is still standing, back at the beginning.
        compose.waitUntilAtLeastOneExists(hasText("Off the grid", substring = true), 20_000)
        assertTrue(
            "the activity should still be running",
            scenario!!.state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED),
        )
    }

    /** A lock landing while the grid is actually being scrolled, not merely sitting still. */
    @Test
    fun lockingDuringAGalleryScrollDoesNotBringTheAppDown() {
        openGallery()

        // Scroll the grid continuously on another thread and lock the vault while it is moving,
        // so the index closes with a query already on its way. Once the lock lands the grid is
        // gone from the tree, which is what should happen, so the scroller stops rather than
        // failing.
        val scroller = Thread {
            runCatching {
                repeat(40) { round ->
                    compose.onAllNodes(hasScrollToNodeAction()).onFirst()
                        .performScrollToIndex(if (round % 2 == 0) 20 else 2)
                }
            }
        }
        scroller.start()
        Thread.sleep(300)
        instrumentation.runOnMainSync { entry.session().lock() }
        scroller.join(20_000)

        compose.waitUntilAtLeastOneExists(hasText("passphrase", substring = true), 20_000)
        assertTrue("the vault should be locked", entry.session().openVault == null)
        assertEquals(UnlockResultName, runBlocking {
            entry.session().unlockWithPassphrase(passphrase.toCharArray())
        }::class.simpleName)
        assertEquals(24, runBlocking { entry.session().openVault!!.database.files().all().size })
    }

    /**
     * The screen going off mid-export. The export holds the vault open so the copying can
     * finish, but the lock that was asked for must land the moment it does: the vault may not be
     * left sitting unlocked behind a dark screen.
     */
    @Test
    fun theVaultLocksAsSoonAsAnExportFinishesWithTheScreenOff() {
        repeat(6) { i ->
            runBlocking {
                entry.session().openVault!!.files.put(
                    Random(i).nextBytes(3 * 1024 * 1024).inputStream(),
                    IncomingFile("big$i.bin", "application/octet-stream", 1_760_000_100_000 + i * 1_000L),
                )
            }
        }

        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 60_000
        while (entry.exportManager().progress.value.bytesDone < 1_000_000 && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        assertTrue("never caught the export running", entry.exportManager().progress.value.running)

        TestScreen.sleep()
        // The export is still allowed to run: it is holding the vault open on purpose.
        Thread.sleep(500)

        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val progress = entry.exportManager().progress.value
            if (progress.finished && !progress.running) break
            Thread.sleep(25)
        }
        val finished = entry.exportManager().progress.value
        assertTrue("the export never finished: $finished", finished.finished && !finished.running)

        // The lock it deferred has to happen now the hold is gone.
        val lockedBy = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < lockedBy && entry.session().openVault != null) {
            Thread.sleep(25)
        }
        assertTrue(
            "the vault was left unlocked behind a dark screen after the export finished " +
                "(screen is ${TestScreen.state()})",
            entry.session().openVault == null,
        )
    }

    private fun bigFiles(n: Int): Map<String, ByteArray> {
        val made = (0 until n).associate { i -> "big$i.bin" to Random(i).nextBytes(2 * 1024 * 1024) }
        made.entries.forEachIndexed { i, (name, bytes) ->
            runBlocking {
                entry.session().openVault!!.files.put(
                    bytes.inputStream(),
                    IncomingFile(name, "application/octet-stream", 1_760_000_100_000 + i * 1_000L),
                )
            }
        }
        return made
    }

    private fun awaitExportFinished(timeoutMs: Long = 180_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val p = entry.exportManager().progress.value
            if (p.finished && !p.running) return
            Thread.sleep(25)
        }
        throw AssertionError("the export never finished: ${entry.exportManager().progress.value}")
    }

    private fun runUntilSomeBytes(bytes: Long = 1_000_000) {
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        val until = System.currentTimeMillis() + 60_000
        while (entry.exportManager().progress.value.bytesDone < bytes && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        assertTrue("never caught the export running", entry.exportManager().progress.value.running)
    }

    /**
     * The panic flip while the export is copying. It must stop at once, leave nothing
     * half-written at the destination, and the job must still be finishable afterwards.
     */
    @Test
    fun aPanicLockMidExportStopsItCleanlyAndCanBeCarriedOn() {
        val originals = bigFiles(6)
        runUntilSomeBytes()

        instrumentation.runOnMainSync { entry.panicLock().trigger() }
        awaitExportFinished()

        val stopped = entry.exportManager().progress.value
        assertTrue("the panic lock should have stopped it: $stopped", stopped.cancelled)
        assertTrue("the vault should be locked", entry.session().openVault == null)

        // Nothing half-written is sitting at the destination under a real file name.
        // Only the big files are large enough to be caught mid-copy; a copy of one of them that
        // is not byte-for-byte whole is a part file.
        val leftovers = drive.walkTopDown().filter { it.isFile && it.name.startsWith("big") }
            .filter { file -> originals.values.none { it.contentEquals(file.readBytes()) } }
            .map { "${it.name}:${it.length()}" }
            .toList()
        assertEquals("a part file was left behind: $leftovers", emptyList<String>(), leftovers)

        // Unlock and carry on: everything comes out, once each, byte for byte.
        instrumentation.runOnMainSync { entry.panicLock().acknowledge() }
        runBlocking { entry.session().unlockWithPassphrase(passphrase.toCharArray()) }
        entry.exportManager().reset()
        assertTrue(entry.exportManager().exportEverything(FileBackupDir(drive), startService = false))
        awaitExportFinished()

        val copies = drive.walkTopDown().filter { it.isFile }.toList()
        // 24 photos from setUp plus the six big ones.
        assertEquals("expected one copy of each, got ${copies.size}", 30, copies.size)
        originals.forEach { (name, bytes) ->
            assertTrue("$name did not come out byte for byte", copies.any { it.readBytes().contentEquals(bytes) })
        }
    }

    /** Rotating the device while the export runs must not disturb it. */
    @Test
    fun rotatingTheDeviceMidExportDoesNotDisturbIt() {
        val originals = bigFiles(6)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)

        runUntilSomeBytes()
        try {
            TestScreen.shell("settings put system accelerometer_rotation 0")
            TestScreen.shell("settings put system user_rotation 1") // landscape
            Thread.sleep(1_200)
            TestScreen.shell("settings put system user_rotation 0") // and back
            Thread.sleep(1_200)

            awaitExportFinished()
        } finally {
            TestScreen.shell("settings put system user_rotation 0")
            TestScreen.shell("settings put system accelerometer_rotation 1")
        }

        val result = entry.exportManager().progress.value
        assertEquals("rotation cost it files: $result", 0, result.failed)
        assertTrue("it should not have been cancelled: $result", !result.cancelled)
        assertEquals("every file should be out", 30, drive.walkTopDown().count { it.isFile })
        val copies = drive.walkTopDown().filter { it.isFile }.toList()
        originals.forEach { (name, bytes) ->
            assertTrue("$name did not come out byte for byte", copies.any { it.readBytes().contentEquals(bytes) })
        }
    }

    private companion object {
        /** The name of the success result, kept in one place rather than imported for one use. */
        const val UnlockResultName = "Success"
    }
}