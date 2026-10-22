package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
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
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Search, albums and the Security screen, driven through the real UI. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OrganizeFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val passphrase = "lantern orbit velvet canyon mosaic"

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }.compress(Bitmap.CompressFormat.JPEG, 80, it)
    }.toByteArray()

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        entry.autoLock().delayMs = AutoLock.DEFAULT_DELAY_MS
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before
    fun setUp() {
        wipe()
        runBlocking {
            entry.session().create(passphrase.toCharArray())
            val files = entry.session().openVault!!.files
            files.put(jpeg().inputStream(), IncomingFile("passport-photo.jpg", "image/jpeg", 1_700_000_000_000))
            files.put("tax id 1234".toByteArray().inputStream(), IncomingFile("Tax return 2025.txt", "text/plain", 1_700_000_100_000))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun searchFindsAndOpensADocument() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Search the vault"), 5_000)
        compose.onNodeWithContentDescription("Search the vault").performTextInput("TAX")
        compose.waitUntilAtLeastOneExists(hasText("1 RESULT"), 5_000)
        compose.onNodeWithText("Tax return 2025.txt").performClick()
        compose.waitUntilAtLeastOneExists(hasText("tax id 1234"), 10_000)
    }

    @Test
    fun createAnAlbumAndAddAPhotoFromTheViewer() {
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.waitUntilAtLeastOneExists(hasText("No albums yet", substring = true), 5_000)
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("Album name").performTextInput("Documents & IDs")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Documents & IDs"), 5_000)

        compose.onNodeWithContentDescription("Home").performClick()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 5_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("JPG, passport-photo.jpg"), 10_000)
        compose.onNodeWithContentDescription("JPG, passport-photo.jpg").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Album"), 10_000)
        compose.onNodeWithText("Album").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Add to album"), 5_000)
        compose.onNodeWithText("Documents & IDs").performClick()

        compose.waitUntil(5_000) {
            runBlocking { entry.session().openVault!!.database.albums().observeSummaries().first().singleOrNull()?.count == 1 }
        }
    }

    @Test
    fun securitySettingsApply() {
        compose.onNodeWithContentDescription("Security").performClick()
        compose.waitUntilAtLeastOneExists(hasText("VAULT HEALTH"), 5_000)

        compose.onNodeWithText("1 min").performScrollTo().performClick()
        compose.waitUntil(3_000) { entry.autoLock().delayMs == 60_000L }

        // Open the sheet with the section's button, then submit with the sheet's button.
        val changeButton = hasText("Change passphrase") and hasClickAction()
        compose.onNode(changeButton).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Current passphrase"), 5_000)
        compose.onNodeWithContentDescription("Current passphrase").performTextInput(passphrase)
        compose.onNodeWithContentDescription("New passphrase").performTextInput("quartz tundra violet piano saffron")
        compose.onNodeWithContentDescription("Repeat new passphrase").performTextInput("quartz tundra violet piano saffron")
        compose.onAllNodes(changeButton).let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1].performScrollTo().performClick() }
        compose.waitUntilAtLeastOneExists(hasText("Passphrase changed", substring = true), 30_000)

        instrumentation.runOnMainSync { entry.session().lock() }
        val result = runBlocking { entry.session().unlockWithPassphrase("quartz tundra violet piano saffron".toCharArray()) }
        assertEquals(UnlockResult.Success, result)
    }
}
