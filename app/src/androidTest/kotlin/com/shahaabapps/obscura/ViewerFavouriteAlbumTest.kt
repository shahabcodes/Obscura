package com.shahaabapps.obscura

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.vault.VaultFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The heart and the album button, in both viewers. Each marks the file where it already is:
 * the vault holds just as many files, and just as many sealed blobs, afterwards as before.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ViewerFavouriteAlbumTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val entry get() = VideoLengthFixture.entry

    @Before
    fun setUp() {
        VideoLengthFixture.wipe()
        runBlocking { VideoLengthFixture.create() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("SEALED ON THIS PHONE", substring = true), 30_000)
    }

    @After
    fun tearDown() {
        scenario?.close()
        VideoLengthFixture.wipe()
    }

    private fun files(): List<VaultFile> = runBlocking { entry.session().openVault!!.database.files().observeAll().first() }
    private fun named(name: String) = files().single { it.name == name }
    private fun blobs() = entry.store().blobDir.walk().count { it.isFile }

    /** Hearts the file on screen, then puts it in a new album from the viewer's own sheet. */
    private fun heartAndFile(name: String, album: String) {
        val count = files().size
        val sealed = blobs()

        compose.onNodeWithContentDescription("Add to favourites").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Remove from favourites"), 5_000)

        // A labelled button in the photo viewer's bar, an icon beside the video's title.
        compose.onNode(hasText("Album") or hasContentDescription("Album")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Add to album"), 5_000)
        compose.onNodeWithText("New album").performClick()
        compose.onNodeWithContentDescription("Album name").performTextInput(album)
        compose.onNodeWithText("Create & add").performClick()

        compose.waitUntil(5_000) { named(name).albumId != null }
        val file = named(name)
        assertEquals(true, file.favorite)
        val albums = runBlocking { entry.session().openVault!!.database.albums().observeSummaries().first() }
        assertEquals(album, albums.single { it.id == file.albumId }.name)
        // The same file, marked: no second copy in the index or on disk.
        assertEquals(count, files().size)
        assertEquals(1, files().count { it.name == name })
        assertEquals(sealed, blobs())
    }

    @Test
    fun theVideoViewerHeartsAndFilesTheVideoItself() {
        entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS) }
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Video, quick.mp4"), 20_000)
        compose.onNodeWithContentDescription("Video, quick.mp4").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("Video stage"), 15_000)

        heartAndFile("quick.mp4", "Clips")

        // Unhearting takes the flag off again, still without touching anything else.
        compose.onNodeWithContentDescription("Remove from favourites").performClick()
        compose.waitUntil(5_000) { !named("quick.mp4").favorite }
        assertEquals(3, files().size)
    }

    @Test
    fun thePhotoViewerHeartsAndFilesThePhotoItself() {
        compose.onNodeWithText("Photos").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("JPG, beach.jpg"), 20_000)
        compose.onNodeWithContentDescription("JPG, beach.jpg").performClick()

        compose.waitUntilAtLeastOneExists(hasContentDescription("Add to favourites"), 10_000)
        heartAndFile("beach.jpg", "Trips")
    }
}
