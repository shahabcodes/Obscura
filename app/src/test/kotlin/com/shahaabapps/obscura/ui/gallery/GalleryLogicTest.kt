package com.shahaabapps.obscura.ui.gallery

import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class GalleryLogicTest {

    private val utc = ZoneId.of("UTC")

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun file(id: String, kind: FileKind, taken: Long, size: Long, name: String = "$id.jpg", mime: String = if (kind == FileKind.VIDEO) "video/mp4" else "image/jpeg", fav: Boolean = false) =
        VaultFile(id, name, mime, kind, size, taken, 0, ByteArray(0), id, favorite = fav)

    private val files = listOf(
        file("a", FileKind.PHOTO, at(2026, 10, 3), 5_000),
        file("b", FileKind.VIDEO, at(2026, 10, 20), 900_000),
        file("c", FileKind.PHOTO, at(2026, 9, 14), 2_000, name = "Screenshot_0914.png", mime = "image/png"),
        file("d", FileKind.PHOTO, at(2026, 8, 1), 8_000, fav = true),
        file("e", FileKind.DOCUMENT, at(2026, 10, 25), 100, name = "notes.pdf", mime = "application/pdf"),
    )

    private fun ids(entries: List<GalleryEntry>) = entries.joinToString(",") {
        when (it) {
            is GalleryEntry.Header -> "[${it.month.take(3)}]"
            is GalleryEntry.Item -> it.file.id
        }
    }

    @Test
    fun groupsByMonthNewestFirstByDefault() {
        val entries = GalleryLogic.build(files, GallerySettings(), utc)
        assertEquals("[Oct],b,a,[Sep],c,[Aug],d", ids(entries))
        val october = entries.first() as GalleryEntry.Header
        assertEquals(2, october.count)
        assertEquals(905_000, october.bytes)
        assertEquals("2026", october.year)
    }

    @Test
    fun documentsAreNotInTheGallery() {
        assertTrue(GalleryLogic.flat(GalleryLogic.build(files, GallerySettings(), utc)).none { it.id == "e" })
    }

    @Test
    fun oldestFirstReversesMonthsToo() {
        assertEquals("[Aug],d,[Sep],c,[Oct],a,b", ids(GalleryLogic.build(files, GallerySettings(descending = false), utc)))
    }

    @Test
    fun sizeSortWithoutGrouping() {
        val entries = GalleryLogic.build(files, GallerySettings(sortBy = SortBy.SIZE, groupByMonth = false), utc)
        assertEquals("b,d,a,c", ids(entries))
        assertEquals(listOf(0, 1, 2, 3), entries.map { (it as GalleryEntry.Item).position })
    }

    @Test
    fun sizeSortInsideMonths() {
        assertEquals("[Oct],b,a,[Sep],c,[Aug],d", ids(GalleryLogic.build(files, GallerySettings(sortBy = SortBy.SIZE), utc)))
    }

    @Test
    fun typeSortGroupsLikeTypesTogether() {
        val flat = GalleryLogic.flat(GalleryLogic.build(files, GallerySettings(sortBy = SortBy.TYPE, groupByMonth = false), utc))
        assertEquals(listOf("JPG", "JPG", "Screenshot", "Video"), flat.map(GalleryLogic::typeLabel))
    }

    @Test
    fun filters() {
        fun only(filter: GalleryFilter) = GalleryLogic.flat(GalleryLogic.build(files, GallerySettings(filter = filter), utc)).map { it.id }
        assertEquals(listOf("a", "d"), only(GalleryFilter.PHOTOS))
        assertEquals(listOf("b"), only(GalleryFilter.VIDEOS))
        assertEquals(listOf("c"), only(GalleryFilter.SCREENSHOTS))
        assertEquals(listOf("d"), only(GalleryFilter.FAVORITES))
    }

    @Test
    fun positionsFollowTheVisibleOrder() {
        val entries = GalleryLogic.build(files, GallerySettings(), utc)
        val items = entries.filterIsInstance<GalleryEntry.Item>()
        assertEquals(items.indices.toList(), items.map { it.position })
    }
}
