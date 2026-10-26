package com.shahaabapps.obscura

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The tour of the app: reachable, and actually describing the controls it claims to. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GuideTest {

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

    @Before
    fun setUp() {
        wipe()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun theTourIsReachableAndCoversEachPartOfTheApp() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithContentDescription("Security").performClick()
        compose.waitUntilAtLeastOneExists(hasText("VAULT HEALTH"), 15_000)

        // The action is invoked rather than tapped: scrolling can leave a row under the bar
        // along the bottom, and a tap would land on that instead.
        compose.onNodeWithText("How Obscura works — a tour")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilAtLeastOneExists(hasText("How Obscura works"), 10_000)

        // Every part of the app is covered, and the gestures that have no button to find.
        listOf(
            "Getting things in",
            "Finding things",
            "Looking at something",
            "Videos and GIFs",
            "Getting things out",
            "Keeping it shut",
            "Pinch the grid",
            "Jump to a month",
            "Make a GIF",
            "Export everything",
            "Panic lock",
            "Decoy vault",
        ).forEach { heading ->
            compose.onNodeWithText(heading).performScrollTo()
        }
    }
}
