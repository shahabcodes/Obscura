package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decoy's whole point is that there is nothing to see. An export set up in the real vault
 * must not show through: neither the entry that offers it, nor the folder it was going to.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DecoyHidesTheExportTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val real = "lantern orbit velvet canyon mosaic"
    private val decoy = "harbour lantern thicket pebble"
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("obscura.export", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        wipe()
        TestScreen.wake()
        runBlocking {
            entry.session().create(real.toCharArray())
            // Clearing a decoy needs the real vault open, so it cannot happen inside wipe().
            entry.session().removeDecoy()
            assertEquals(UnlockResult.Success, entry.session().createDecoy(decoy.toCharArray(), null))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    private fun openSecurity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithContentDescription("Security").performClick()
        compose.waitUntilAtLeastOneExists(hasText("VAULT HEALTH"), 20_000)
    }

    /**
     * The real vault remembers a folder; the decoy must neither offer the export nor know
     * anything about where the real one was going.
     */
    @Test
    fun theDecoySessionShowsNoExportEntryAndNoRealFolder() {
        // The real vault picks a folder. Any tree uri will do: what matters is which key it is
        // filed under and who can read it back.
        val folder = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        runBlocking { entry.session().unlockWithPassphrase(real.toCharArray()) }
        entry.exportManager().rememberDestination(folder)
        val prefs = context.getSharedPreferences("obscura.export", Context.MODE_PRIVATE)
        assertEquals("the real vault files it under its own key", folder.toString(), prefs.getString("destination", null))

        // Now the decoy.
        instrumentation.runOnMainSync { entry.session().lock() }
        assertEquals(UnlockResult.Success, runBlocking { entry.session().unlockWithPassphrase(decoy.toCharArray()) })
        assertTrue("should be in the decoy", entry.session().openVault!!.isDecoy)

        assertNull("the decoy can see the real vault's folder", entry.exportManager().destination())
        assertNull("the decoy has a folder of its own already", prefs.getString("decoy.destination", null))
        assertTrue("the decoy could carry on a real export", !entry.exportManager().continueEverything(startService = false))

        openSecurity()
        // The tour is there for everyone; the export is not offered at all.
        compose.onNodeWithText("How Obscura works — a tour").performScrollTo()
        compose.onAllNodesWithText("Export everything to a folder").assertCountEquals(0)
    }

    /** And the real vault still has its folder afterwards: hiding it is not forgetting it. */
    @Test
    fun theRealVaultKeepsItsFolderAfterADecoySession() {
        val folder = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        runBlocking { entry.session().unlockWithPassphrase(real.toCharArray()) }
        entry.exportManager().rememberDestination(folder)

        instrumentation.runOnMainSync { entry.session().lock() }
        runBlocking { entry.session().unlockWithPassphrase(decoy.toCharArray()) }
        assertNull(entry.exportManager().destination())

        instrumentation.runOnMainSync { entry.session().lock() }
        runBlocking { entry.session().unlockWithPassphrase(real.toCharArray()) }
        val prefs = context.getSharedPreferences("obscura.export", Context.MODE_PRIVATE)
        assertEquals(folder.toString(), prefs.getString("destination", null))
    }
}
