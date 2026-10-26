package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unlock animation, the licences screen, and a tour that saves pictures of key screens to
 * the app's external files folder (drawn from the view, so FLAG_SECURE doesn't blank them).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PolishFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

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

    private fun snapshot(name: String) {
        // Lottie files load in the background; give them a moment before the picture.
        Thread.sleep(800)
        compose.waitForIdle()
        scenario?.onActivity { activity ->
            val bitmap = activity.window.decorView.drawToBitmap()
            val dir = File(activity.getExternalFilesDir(null), "tour").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Before
    fun setUp() {
        entry.panicLock().acknowledge()
        wipe()
        // Where the user was is remembered for as long as the app runs, which is the point of
        // it; in a suite that means a place left by an earlier test. This one is about unlocking
        // into a fresh vault, where there is nowhere to go back to.
        com.shahaabapps.obscura.ui.navigation.LastPlace.forget()
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            entry.session().setPin("246810".toCharArray())
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun unlockPlaysTheAnimationThenOpensHome() {
        entry.session().lock()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("Locked.")
        snapshot("01-lock-screen")
        "24681".forEach { compose.onNodeWithText(it.toString()).performClick() }
        // Step the clock by hand, or the 1.2 s animation can finish between two checks.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("0").performClick()
        val end = System.currentTimeMillis() + 60_000
        while (compose.onAllNodes(hasText("Unlocked.")).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "The unlock animation never showed" }
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(20)
        }
        compose.mainClock.advanceTimeBy(500)
        snapshot("02-unlocking")
        compose.mainClock.autoAdvance = true
        waitForText("SEALED ON THIS PHONE", 20_000)
        snapshot("02-home")
    }

    @Test
    fun licencesOpenFromSecurity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("SEALED ON THIS PHONE")
        compose.onNodeWithContentDescription("Security").performClick()
        waitForText("VAULT HEALTH")
        snapshot("03-security")
        compose.onNodeWithText("Open-source licences").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Obscura is built on these projects")
        compose.onNodeWithText("Space Grotesk").performClick()
        waitForText("SIL OPEN FONT LICENSE Version 1.1")
        snapshot("04-licences")
    }

    @Test
    fun tourOfEmptyStates() {
        runBlocking {
            entry.session().openVault!!.files.put("notes".toByteArray().inputStream(), IncomingFile("notes.txt", "text/plain", 1_700_000_000_000))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("SEALED ON THIS PHONE")
        compose.onNodeWithText("Photos").performClick()
        waitForText("No photos or videos yet.")
        snapshot("05-gallery-empty")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Albums").performClick()
        waitForText("No albums yet.")
        snapshot("06-albums-empty")
        compose.onNodeWithContentDescription("Import files").performClick()
        waitForText("Bring files in")
        snapshot("07-import")
    }
}
