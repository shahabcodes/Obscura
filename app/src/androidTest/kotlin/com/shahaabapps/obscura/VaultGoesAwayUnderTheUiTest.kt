package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault disappearing while a screen is still reading it.
 *
 * The gallery, home and search all watch the encrypted index for changes. Locking the vault
 * closes that index, and deleting it takes the file away entirely — while a query may already
 * be on its way. If that lands on the screen's own collector it takes the whole app down, which
 * is a poor way to answer "delete this vault".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VaultGoesAwayUnderTheUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(color) }
            .compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun wipe() {
        instrumentation.runOnMainSync { entry.session().lock() }
        Thread.sleep(150)
        instrumentation.runOnMainSync {
            entry.store().destroy()
            KeystoreBinding().delete()
            context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        wipe()
        TestScreen.wake()
        runBlocking {
            entry.session().create(passphrase.toCharArray())
            val files = entry.session().openVault!!.files
            repeat(12) { i ->
                files.put(
                    jpeg(Color.rgb(20 + i * 15, 90, 180)).inputStream(),
                    IncomingFile("photo$i.jpg", "image/jpeg", 1_760_000_000_000 + i * 1_000L),
                )
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    /** Deleting the vault from under the gallery must leave the app standing. */
    @Test
    fun deletingTheVaultWhileTheGalleryIsWatchingDoesNotBringTheAppDown() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)

        // The gallery is now watching the index. Take the whole vault away underneath it.
        val result = runBlocking { entry.session().deleteVault(passphrase.toCharArray()) }
        assertEquals(UnlockResult.Success, result)

        // The app is still alive and back at the start, with nothing left to show.
        compose.waitUntilAtLeastOneExists(hasText("Off the grid", substring = true), 20_000)
        assertEquals(VaultState.NoVault, entry.session().state.value)
        assertTrue("the activity should still be running", scenario!!.state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED))
    }

    /** The same for an ordinary lock, which closes the index while the grid is on screen. */
    @Test
    fun lockingWhileTheGalleryIsWatchingDoesNotBringTheAppDown() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)

        instrumentation.runOnMainSync { entry.session().lock() }

        compose.waitUntilAtLeastOneExists(hasText("passphrase", substring = true), 20_000)
        assertTrue("the vault should be locked", entry.session().openVault == null)
        // And it can be opened again, which it could not if the app had fallen over.
        val reopened = runBlocking { entry.session().unlockWithPassphrase(passphrase.toCharArray()) }
        assertEquals(UnlockResult.Success, reopened)
        assertEquals(12, runBlocking { entry.session().openVault!!.database.files().all().size })
    }
}
