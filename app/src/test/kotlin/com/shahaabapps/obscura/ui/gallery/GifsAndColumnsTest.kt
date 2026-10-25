package com.shahaabapps.obscura.ui.gallery

import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.FileStore
import com.shahaabapps.obscura.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GIFs are their own category, and the grid's size can be pinched between its limits. */
class GifsAndColumnsTest {

    private fun file(id: String, kind: FileKind, mime: String, name: String = "$id.x") =
        VaultFile(id, name, mime, kind, 1_000, 1_700_000_000_000, 1_700_000_000_000, ByteArray(72), id.repeat(8))

    private val all = listOf(
        file("a", FileKind.PHOTO, "image/jpeg", "beach.jpg"),
        file("b", FileKind.GIF, "image/gif", "dance.gif"),
        file("c", FileKind.VIDEO, "video/mp4", "clip.mp4"),
        file("d", FileKind.AUDIO, "audio/mpeg", "song.mp3"),
        file("e", FileKind.GIF, "image/gif", "cat.gif"),
    )

    @Test
    fun aGifIsItsOwnKind() {
        assertEquals(FileKind.GIF, FileStore.kindOf("image/gif"))
        assertEquals(FileKind.PHOTO, FileStore.kindOf("image/jpeg"))
        assertEquals(FileKind.PHOTO, FileStore.kindOf("image/png"))
    }

    @Test
    fun theTypeIsReadLooselyEnoughForRealFiles() {
        // What a picker hands over varies in case, and may carry a parameter.
        assertEquals(FileKind.GIF, FileStore.kindOf("image/GIF"))
        assertEquals(FileKind.GIF, FileStore.kindOf("image/gif; charset=binary"))
        assertEquals(FileKind.GIF, FileStore.kindOf(" image/gif "))
        assertEquals(FileKind.VIDEO, FileStore.kindOf("VIDEO/MP4"))
    }

    @Test
    fun theGalleryShowsGifsAndCanShowOnlyGifs() {
        val shown = GalleryLogic.media(all, GalleryFilter.ALL).map { it.id }
        assertEquals(listOf("a", "b", "c", "e"), shown)
        assertEquals(listOf("b", "e"), GalleryLogic.media(all, GalleryFilter.GIFS).map { it.id })
        // A GIF is not a photo and not a video, so those filters leave it out.
        assertEquals(listOf("a"), GalleryLogic.media(all, GalleryFilter.PHOTOS).map { it.id })
        assertEquals(listOf("c"), GalleryLogic.media(all, GalleryFilter.VIDEOS).map { it.id })
    }

    @Test
    fun aGifIsLabelledAsOne() {
        assertEquals("GIF", GalleryLogic.typeLabel(all[1]))
        assertEquals("Video", GalleryLogic.typeLabel(all[2]))
        assertEquals("JPG", GalleryLogic.typeLabel(all[0]))
    }

    @Test
    fun pinchingStepsBetweenTheLimits() {
        assertEquals(2, ColumnRange.stepped(3, -1))
        assertEquals(4, ColumnRange.stepped(3, 1))
        // It stops at the ends rather than wrapping or going silly.
        assertEquals(ColumnRange.MIN, ColumnRange.stepped(ColumnRange.MIN, -1))
        assertEquals(ColumnRange.MAX, ColumnRange.stepped(ColumnRange.MAX, 1))
        assertTrue(ColumnRange.choices.first() == ColumnRange.MIN && ColumnRange.choices.last() == ColumnRange.MAX)
    }
}
