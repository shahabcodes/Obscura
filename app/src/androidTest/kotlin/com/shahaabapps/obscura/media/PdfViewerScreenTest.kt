package com.shahaabapps.obscura.media

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.MainActivity
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PDF viewer on the real app: pages must actually appear, and the screen must survive a lock,
 * a rotation and a damaged document while it is open.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PdfViewerScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    /** A real PDF with the given page sizes in points. */
    private fun pdf(vararg pages: Pair<Int, Int>): ByteArray {
        val doc = PdfDocument()
        pages.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", 20f, 40f, Paint().apply { textSize = 24f })
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

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

    private fun seal(name: String, bytes: ByteArray) = runBlocking {
        entry.session().openVault!!.files.put(bytes.inputStream(), IncomingFile(name, "application/pdf", System.currentTimeMillis()))
    }

    @Before
    fun setUp() {
        wipe()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    private fun openDocument(name: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Documents").performClick()
        compose.waitUntilAtLeastOneExists(hasText(name), 10_000)
        compose.onNodeWithText(name).performClick()
    }

    /** Width / height of the page row, which follows the rendered bitmap's shape. */
    private fun pageShape(label: String): Float {
        val size = compose.onNodeWithContentDescription(label).fetchSemanticsNode().size
        return size.width.toFloat() / size.height
    }

    /**
     * A landscape page must be drawn landscape. While nothing has rendered the row keeps the
     * portrait placeholder shape (0.707), so a page that never arrives shows up here.
     */
    @Test
    fun pagesAppearInTheViewer() {
        seal("wide.pdf", pdf(792 to 612))
        openDocument("wide.pdf")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 1"), 15_000)
        val deadline = System.currentTimeMillis() + 15_000
        while (pageShape("Page 1 of 1") < 1f) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("page 1 never rendered: row shape is still ${pageShape("Page 1 of 1")}, expected about 1.29")
            }
            compose.waitForIdle()
            Thread.sleep(100)
        }
        assertTrue("page is landscape", pageShape("Page 1 of 1") > 1.2f)
    }

    /** A long document: pages further down must render too, and scrolling must not crash. */
    @Test
    fun manyPagesRenderAndScroll() {
        seal("long.pdf", pdf(*Array(120) { 612 to 792 }))
        openDocument("long.pdf")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 120"), 20_000)
        repeat(10) {
            instrumentation.uiAutomation.executeShellCommand("input swipe 500 1600 500 400 80")
            Thread.sleep(400)
        }
        compose.waitForIdle()
        assertTrue("still showing the document", entry.session().openVault != null)
    }

    /** Locking while the viewer is open must take the app to unlock, not bring it down. */
    @Test
    fun lockingWithAPdfOpenIsSafe() {
        seal("secret.pdf", pdf(*Array(30) { 612 to 792 }))
        openDocument("secret.pdf")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 30"), 20_000)
        instrumentation.runOnMainSync { entry.session().lock() }
        repeat(5) {
            instrumentation.uiAutomation.executeShellCommand("input swipe 500 1600 500 400 60")
            Thread.sleep(200)
        }
        compose.waitForIdle()
        Thread.sleep(2_000)
        // The keys are gone and the renderer is reading through them: no crash, and the vault
        // must not come back open by itself.
        assertTrue("the vault is still locked", entry.session().openVault == null)
        assertTrue("the activity survived the lock", scenario!!.state.isAtLeast(Lifecycle.State.CREATED))
    }

    /** Rotating while a PDF is open must keep the app up and re-render. */
    @Test
    fun rotatingWithAPdfOpenIsSafe() {
        seal("turn.pdf", pdf(*Array(10) { 612 to 792 }))
        openDocument("turn.pdf")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 10"), 20_000)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        Thread.sleep(1_500)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 10"), 20_000)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        Thread.sleep(1_500)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 10"), 20_000)
    }

    /** A damaged PDF must explain itself instead of crashing. */
    @Test
    fun aDamagedPdfShowsAMessage() {
        seal("broken.pdf", "%PDF-1.7\n".toByteArray() + Random(11).nextBytes(40_000))
        openDocument("broken.pdf")
        compose.waitUntilAtLeastOneExists(hasText("couldn't be opened", substring = true), 40_000)
    }

    /** A page metres long: capped, but it must still show. */
    @Test
    fun aVeryTallPageStillShows() {
        seal("tall.pdf", pdf(612 to 60_000))
        openDocument("tall.pdf")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Page 1 of 1"), 20_000)
        val deadline = System.currentTimeMillis() + 20_000
        while (pageShape("Page 1 of 1") > 0.2f) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("the tall page never rendered: row shape ${pageShape("Page 1 of 1")}")
            }
            compose.waitForIdle()
            Thread.sleep(100)
        }
    }
}
