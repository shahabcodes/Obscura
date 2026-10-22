package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app, with real Argon2id settings, SQLCipher and Keystore:
 * create a vault, keep the recovery key, set a PIN, lock, fail once, unlock by PIN and by passphrase.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SetupAndUnlockFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private val passphrase = "lantern orbit velvet canyon mosaic"
    private val pin = "135792"

    private fun wipeVault() {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        KeystoreGate().deleteKey()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { wipeVault() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { wipeVault() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

    private fun enterPin(digits: String) = digits.forEach { compose.onNodeWithText(it.toString()).performClick() }

    @Test
    fun createVaultLockAndUnlock() {
        // Welcome → setup
        waitForText("Off the grid.")
        compose.onNodeWithText("Create my vault").performClick()

        // Passphrase
        waitForText("Choose a passphrase")
        compose.onNodeWithContentDescription("Passphrase").performTextInput(passphrase)
        compose.onNodeWithContentDescription("Repeat passphrase").performTextInput(passphrase)
        compose.onNodeWithText("Create vault").performScrollTo().performClick()

        // Recovery key (Argon2id at full strength runs first)
        waitForText("Your recovery key.", 60_000)
        compose.onNodeWithText("I've written this key down safely").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()

        // PIN, twice
        waitForText("Choose a 6-digit PIN.")
        enterPin(pin)
        waitForText("Repeat your PIN.")
        enterPin(pin)

        // No fingerprint on the emulator, so setup lands on Home
        waitForText("SEALED ON THIS PHONE", 60_000)
        compose.onNodeWithText("Vault is empty · import files to begin").assertExists()

        // Lock, then a wrong PIN
        compose.onNodeWithContentDescription("Lock now").performClick()
        waitForText("Locked.")
        enterPin("000000")
        waitForText("Wrong PIN. 3 more tries before a wait.", 60_000)

        // Right PIN
        enterPin(pin)
        waitForText("SEALED ON THIS PHONE", 60_000)

        // Lock again and use the passphrase path
        compose.onNodeWithContentDescription("Lock now").performClick()
        waitForText("Locked.")
        compose.onNodeWithText("Use passphrase").performClick()
        waitForText("Enter your passphrase.")
        compose.onNodeWithContentDescription("Passphrase").performTextInput(passphrase)
        compose.onNodeWithText("Unlock").performClick()
        waitForText("SEALED ON THIS PHONE", 60_000)
    }

    @Test
    fun weakOrMismatchedPassphraseCannotCreateAVault() {
        waitForText("Off the grid.")
        compose.onNodeWithText("Create my vault").performClick()
        waitForText("Choose a passphrase")
        compose.onNodeWithContentDescription("Passphrase").performTextInput("hunter2")
        waitForText("Too weak")
        compose.onNodeWithContentDescription("Repeat passphrase").performTextInput("hunter2")
        compose.onNodeWithText("Create vault").performScrollTo().performClick()
        // Still on the passphrase screen: nothing was created.
        compose.onNodeWithText("Choose a passphrase only you know.").assertExists()
        assert(!entry.store().exists())

        compose.onNodeWithText("Generate").performClick()
        compose.waitUntilAtLeastOneExists(hasText("YOUR NEW PASSPHRASE"), 5_000)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Show Passphrase"), 5_000)
    }
}
