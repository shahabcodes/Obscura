package com.shahaabapps.obscura

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.ui.gallery.GalleryLogic
import com.shahaabapps.obscura.ui.gallery.GallerySettings
import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.FileStore
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultFile
import com.shahaabapps.obscura.vault.likePattern
import dagger.hilt.android.EntryPointAccessors
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A vault at real scale: 20,000 indexed files spread over seven years (about the count in a
 * 200 GB photo library). Index rows only; the bytes behind them aren't needed to test the
 * index, grouping, search, scrolling, selection and memory. Timings go to logcat as ObscuraStress.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LargeVaultTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val count = 20_000

    private fun log(what: String, ms: Long) = Log.i("ObscuraStress", "%-34s %,7d ms".format(what, ms))

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
        entry.panicLock().acknowledge()
        // Other tests change sort, filter and columns; start from the defaults.
        entry.gallerySettings().update { com.shahaabapps.obscura.ui.gallery.GallerySettings() }
        wipe()
        runBlocking {
            entry.session().create("lantern orbit velvet canyon mosaic".toCharArray())
            val db = entry.session().openVault!!.database
            val now = System.currentTimeMillis()
            val inserted = measureTimeMillis {
                db.withTransaction {
                    repeat(count) { i ->
                        val video = i % 10 == 0
                        db.files().insert(
                            VaultFile(
                                id = FileStore.newId(),
                                name = if (video) "VID_%05d.mp4".format(i) else "IMG_%05d.jpg".format(i),
                                mimeType = if (video) "video/mp4" else "image/jpeg",
                                kind = if (video) FileKind.VIDEO else FileKind.PHOTO,
                                sizeBytes = if (video) 180_000_000L + i else 4_000_000L + i,
                                takenAt = now - i * 3L * 3_600_000L, // every 3 hours, back seven years
                                sealedAt = now,
                                wrappedKey = ByteArray(72) { (it + i).toByte() },
                                sha256 = "%064x".format(i),
                                width = 4032,
                                height = 3024,
                                durationMs = if (video) 95_000L else null,
                            ),
                        )
                    }
                }
            }
            log("insert $count rows", inserted)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        wipe()
    }

    @Test
    fun indexGroupingAndSearchStayFast() = runBlocking {
        val dao = entry.session().openVault!!.database.files()
        lateinit var files: List<VaultFile>
        val load = measureTimeMillis { files = dao.observeAll().first() }
        log("load all rows", load)
        assertEquals(count, files.size)

        lateinit var entries: List<com.shahaabapps.obscura.ui.gallery.GalleryEntry>
        val group = measureTimeMillis { entries = GalleryLogic.build(files, GallerySettings()) }
        log("group by month", group)
        val months = entries.count { it is com.shahaabapps.obscura.ui.gallery.GalleryEntry.Header }
        assertTrue("about 7 years of months, got $months", months in 80..90)
        assertEquals(count, GalleryLogic.flat(entries).size)

        val bySize = measureTimeMillis { GalleryLogic.build(files, GallerySettings(sortBy = com.shahaabapps.obscura.ui.gallery.SortBy.SIZE)) }
        log("sort by size", bySize)

        lateinit var hits: List<VaultFile>
        val search = measureTimeMillis { hits = dao.search(likePattern("IMG_1999")).first() }
        log("search the index", search)
        assertTrue(hits.map { it.name }.toString(), hits.any { it.name == "IMG_19991.jpg" } && hits.size == 9)

        assertTrue("loading took $load ms", load < 5_000)
        assertTrue("grouping took $group ms", group < 3_000)
        assertTrue("search took $search ms", search < 2_000)
    }

    @Test
    fun galleryScrollsSelectsAndStaysWithinMemory() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
        val open = measureTimeMillis {
            compose.onNodeWithText("Photos").performClick()
            compose.waitUntilAtLeastOneExists(hasText("20,000 ITEMS", substring = true), 30_000)
            compose.waitUntilAtLeastOneExists(hasScrollToIndexAction(), 30_000)
        }
        log("open the gallery", open)

        val grid = compose.onNode(hasScrollToIndexAction())
        val scroll = measureTimeMillis {
            for (index in 0..(count + 90) step 1_500) grid.performScrollToIndex(index)
            grid.performScrollToIndex(0)
        }
        log("scroll end to end and back", scroll)

        val runtime = Runtime.getRuntime()
        System.gc()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        Log.i("ObscuraStress", "heap in use after scrolling: $usedMb MB (limit ${runtime.maxMemory() / (1024 * 1024)} MB)")
        assertTrue("heap $usedMb MB", usedMb < runtime.maxMemory() / (1024 * 1024) / 2)

        // Select all 20,000 and favourite them in one go.
        val bulk = measureTimeMillis {
            compose.onNodeWithContentDescription("Select").performClick()
            compose.onNodeWithText("Select all").performClick()
            compose.waitUntilAtLeastOneExists(hasText("20,000 selected", substring = true), 30_000)
            compose.onNodeWithText("Favourite").performClick()
            compose.waitUntil(60_000) {
                runBlocking { entry.session().openVault!!.database.files().all().count { it.favorite } } == count
            }
        }
        log("select all + favourite", bulk)
        assertTrue("bulk favourite took $bulk ms", bulk < 30_000)
    }
}
