package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
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

/** Gallery → Duplicates → review → shred → space freed, on the real app. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DuplicatesFlowTest {

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
            context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
            entry.session().reload()
        }
    }

    @Before
    fun setUp() {
        wipe()
        val photo = ByteArrayOutputStream().also {
            Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }.toByteArray()
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            files.put(photo.inputStream(), IncomingFile("original.jpg", "image/jpeg", 1_700_000_000_000, 300, 200))
            files.put(photo.inputStream(), IncomingFile("copy.jpg", "image/jpeg", 1_700_000_000_000, 300, 200))
            files.put(photo.inputStream(), IncomingFile("copy 2.jpg", "image/jpeg", 1_700_000_000_000, 300, 200))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        scenario?.close()
        wipe()
    }

    /** The scan runs on a real background thread while the radar spins forever: step the clock. */
    private fun stepUntil(text: String, timeoutMs: Long = 20_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "never showed '$text'" }
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(20)
        }
    }

    @Test
    fun findReviewAndShredDuplicates() {
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Duplicates"), 10_000)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Duplicates").performClick()

        stepUntil("YOU CAN FREE")
        compose.onNodeWithText("Exact copies · 1").assertExists()
        compose.onNodeWithText("2 marked across 1 sets", substring = true).assertExists()

        compose.onNode(hasText("Shred 2", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        stepUntil("Shred 2 copies?")
        compose.onAllNodesWithText("Shred")[0].performSemanticsAction(SemanticsActions.OnClick)
        stepUntil("freed")
        compose.mainClock.autoAdvance = true

        val left = runBlocking { entry.session().openVault!!.database.files().observeAll().first() }
        assertEquals(listOf("original.jpg"), left.map { it.name })
    }
}
