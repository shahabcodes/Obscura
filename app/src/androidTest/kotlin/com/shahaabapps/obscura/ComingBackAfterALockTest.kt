package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coming back to where you were.
 *
 * The vault locks the moment the screen goes off, which is right, but it used to mean that
 * glancing at a notification and coming back put you at the top of the app again rather than on
 * the photo you were looking at.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComingBackAfterALockTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private val passphrase = "lantern orbit velvet canyon mosaic"
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
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
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
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
            repeat(4) { i ->
                files.put(
                    jpeg(Color.rgb(30 + i * 40, 120, 200)).inputStream(),
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

    private fun unlock() {
        compose.waitUntilAtLeastOneExists(hasText("passphrase", substring = true), 20_000)
        // The field, not the label above it: typing into the label would quietly do nothing and
        // the test would then be checking an unlock that never happened.
        compose.onNode(hasSetTextAction()).performTextInput(passphrase)
        compose.onNodeWithText("Unlock").performClick()
    }

    /** Locked while in the gallery: unlocking comes back to the gallery, not to home. */
    @Test
    fun unlockingComesBackToTheGallery() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)

        instrumentation.runOnMainSync { entry.session().lock() }
        unlock()

        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 20_000)
        compose.onNodeWithText("Reel mode").assertIsDisplayed()
    }

    /** Returning to home deliberately means the next unlock starts at home, not elsewhere. */
    @Test
    fun goingHomeMeansUnlockingStartsAtHome() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 15_000)

        instrumentation.runOnMainSync { entry.session().lock() }
        unlock()

        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onAllNodesWithText("Reel mode").assertCountEquals(0)
    }
}
