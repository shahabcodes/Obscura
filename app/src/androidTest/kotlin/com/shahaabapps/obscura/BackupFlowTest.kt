package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.backup.BackupOutcome
import com.shahaabapps.obscura.backup.FileBackupDir
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Backup screen from Security, and restoring from Welcome through to an unlocked vault. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BackupFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private val drive = File(context.cacheDir, "usb-drive-ui")

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("obscura.backup", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

    @Before
    fun setUp() {
        wipe()
        drive.deleteRecursively()
        entry.backupManager().dismiss()
        runBlocking {
            entry.session().create(passphrase.toCharArray())
            entry.session().openVault!!.files.put("diary".toByteArray().inputStream(), IncomingFile("Diary.txt", "text/plain", 1_700_000_000_000))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
        drive.deleteRecursively()
        entry.backupManager().dismiss()
    }

    @Test
    fun backupScreenOpensFromSecurity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("SEALED ON THIS PHONE")
        compose.onNodeWithContentDescription("Security").performClick()
        waitForText("Last backup: never")
        compose.onNodeWithText("Back up to a USB drive or folder").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Not chosen yet")
        compose.onNodeWithText("Choose a USB drive or folder").assertExists()
        compose.onNodeWithText("WHAT'S COPIED").assertExists()
    }

    @Test
    fun restoreFromWelcomeThenUnlock() {
        val backups = entry.backupManager()
        assertTrue(backups.backup(FileBackupDir(drive), startService = false))
        while (backups.state.value.running != null || backups.state.value.outcome == null) Thread.sleep(50)
        assertTrue(backups.state.value.outcome is BackupOutcome.BackedUp)
        backups.dismiss()
        wipe()

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("Off the grid.")
        compose.onNodeWithText("Restore from a backup drive").performClick()
        waitForText("Choose the backup")

        // The system folder picker can't be driven from here; hand the same folder in directly.
        assertTrue(backups.restore(FileBackupDir(drive), startService = false))
        waitForText("VAULT RESTORED", 60_000)
        compose.onNodeWithText("Unlock").performClick()
        waitForText("Enter your passphrase.")
        compose.onNode(hasSetTextAction()).performTextInput(passphrase)
        compose.onNodeWithText("Unlock").performClick()
        waitForText("SEALED ON THIS PHONE", 60_000)
        waitForText("Search 1 sealed files")
    }
}
