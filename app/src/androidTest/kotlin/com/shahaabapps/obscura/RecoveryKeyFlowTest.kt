package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Security → Recovery key: a new key after the passphrase, and the PDF password rules. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RecoveryKeyFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
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

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        wipe()
        runBlocking { entry.session().create(passphrase.toCharArray()) }
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun createANewKeyAndProtectThePdf() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("SEALED ON THIS PHONE")
        compose.onNodeWithContentDescription("Security").performClick()
        waitForText("VAULT HEALTH")
        compose.onNodeWithText("Create a new recovery key").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Obscura never stores your recovery key")

        // A wrong passphrase is refused and counted.
        compose.onNode(hasSetTextAction()).performTextInput("wrong passphrase")
        compose.onAllNodesWithText("Create a new recovery key")[0].performClick()
        waitForText("That passphrase is wrong.", 30_000)

        compose.onNode(hasSetTextAction()).performTextInput(passphrase)
        compose.onAllNodesWithText("Create a new recovery key")[0].performClick()
        waitForText("Your new recovery key.", 30_000)

        // Read the key off the screen and prove it opens the vault.
        val groups = (1..8).map { n ->
            compose.onAllNodes(hasText("%02d".format(n))).fetchSemanticsNodes().size
        }
        assertEquals(List(8) { 1 }, groups)

        compose.onNodeWithText("Save as protected PDF").performScrollTo().performClick()
        waitForText("Protect the PDF")
        val fields = compose.onAllNodes(hasSetTextAction())
        // The vault passphrase may not protect its own recovery page.
        fields[0].performTextInput(passphrase)
        fields[1].performTextInput(passphrase)
        compose.onNodeWithText("Choose where to save").performClick()
        waitForText("Use a different password from your vault passphrase", 30_000)
    }

    @Test
    fun theOldKeyStopsWorking() {
        val original = runBlocking {
            wipe()
            entry.session().create(passphrase.toCharArray()).format()
        }
        val (result, fresh) = runBlocking { entry.session().newRecoveryKey(passphrase.toCharArray()) }
        assertEquals(UnlockResult.Success, result)
        entry.session().lock()
        assertEquals(true, runBlocking { entry.session().unlockWithRecoveryKey(original) } is UnlockResult.Wrong)
        assertEquals(UnlockResult.Success, runBlocking { entry.session().unlockWithRecoveryKey(fresh!!.format()) })
    }
}
