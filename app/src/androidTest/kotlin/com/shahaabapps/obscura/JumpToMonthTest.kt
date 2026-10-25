package com.shahaabapps.obscura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jumping to a month. With a year and a half of photos, reaching the oldest by scrolling is a
 * long way; the point of this is that it is two taps instead.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JumpToMonthTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(color) }
            .compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun millis(y: Int, m: Int, d: Int) =
        LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun wipe() = instrumentation.runOnMainSync {
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("obscura.gallery", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    @Before
    fun setUp() {
        wipe()
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val files = entry.session().openVault!!.files
            // A year of photos, several in each month, so the oldest sits far down the grid.
            // Exactly twelve months, so no month name appears twice and a test can name one.
            var year = 2025
            var month = 6
            repeat(12) { index ->
                repeat(4) { within ->
                    files.put(
                        jpeg(Color.rgb(40 + index * 10, 90, 200 - index * 8)).inputStream(),
                        IncomingFile("m$year-$month-$within.jpg", "image/jpeg", millis(year, month, 2 + within * 5)),
                    )
                }
                month++
                if (month > 12) { month = 1; year++ }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun pickingAMonthScrollsStraightToIt() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)

        // The newest month is at the top, and the oldest is nowhere near the screen.
        compose.waitUntilAtLeastOneExists(hasText("May"), 15_000)
        compose.onAllNodesWithText("June").assertCountEquals(0)

        compose.onNodeWithContentDescription("Jump to a month").performClick()
        // Every month in the vault is offered, including the first one sealed.
        compose.waitUntilAtLeastOneExists(hasText("2025"), 10_000)
        compose.onNodeWithText("June").performClick()

        // The sheet closes and the grid is at June 2025, a year down from where it was.
        compose.waitUntilDoesNotExist(hasText("Jump to a month"), 10_000)
        compose.waitUntilAtLeastOneExists(hasText("June"), 10_000)
        compose.onNodeWithText("June").assertIsDisplayed()
    }

    /** With one month there is nothing to jump between, so the button stays out of the way. */
    @Test
    fun theButtonIsOfferedOnlyWhenThereIsSomewhereToGo() {
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE"), 20_000)
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reel mode"), 15_000)
        compose.onNodeWithContentDescription("Jump to a month").assertIsDisplayed()

        compose.onNodeWithText("Favourites").performClick()
        // Nothing is a favourite, so there are no months at all and no way to jump.
        compose.waitUntilAtLeastOneExists(hasText("Nothing matches this filter.", substring = true), 10_000)
        compose.onAllNodesWithContentDescription("Jump to a month").assertCountEquals(0)
    }
}
