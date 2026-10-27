package com.shahaabapps.obscura.ui.gallery

import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class SortBy(val label: String) { DATE("Date"), SIZE("Size"), TYPE("Type") }

enum class GalleryFilter(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), GIFS("GIFs"), SCREENSHOTS("Screens"), FAVORITES("Favourites")
}

/**
 * A length to hold videos against: [seconds], and which side of it to keep.
 *
 * Both halves are the user's choice. Fifty seconds with [longer] off shows everything shorter
 * than fifty seconds; with it on, everything longer.
 */
data class DurationLimit(val seconds: Int, val longer: Boolean) {
    val label: String get() = (if (longer) "Over " else "Under ") + spell(seconds)

    /** True when a video of this length belongs; a length that isn't known never hides a file. */
    fun keeps(durationMs: Long?): Boolean {
        val ms = durationMs ?: return true
        return if (longer) ms > seconds * 1000L else ms < seconds * 1000L
    }

    companion object {
        /** A day's worth of video: past this, a limit is the same as no limit. */
        const val MAX_SECONDS = 86_400

        /** Seconds as a person would say them. */
        fun spell(seconds: Int): String = when {
            seconds < 60 -> "${seconds}s"
            seconds % 60 == 0 -> "${seconds / 60}m"
            else -> "${seconds / 60}m ${seconds % 60}s"
        }
    }
}

data class GallerySettings(
    val sortBy: SortBy = SortBy.DATE,
    val descending: Boolean = true,
    val groupByMonth: Boolean = true,
    val columns: Int = 3,
    val filter: GalleryFilter = GalleryFilter.ALL,
    /** Only has a say while the videos filter is on; null means any length. */
    val duration: DurationLimit? = null,
)

sealed interface GalleryEntry {
    val key: String

    data class Header(val month: String, val year: String, val count: Int, val bytes: Long) : GalleryEntry {
        override val key get() = "h:$year-$month"
    }

    /** [position] is the item's index in the flat, sorted list the viewer pages through. */
    data class Item(val file: VaultFile, val position: Int) : GalleryEntry {
        override val key get() = file.id
    }
}

/**
 * A month the gallery is showing and where it starts, for jumping straight to it.
 *
 * [entryIndex] is the month heading's place in the grid, so a jump lands exactly where the
 * heading is. Taking these from the grid itself means the list of months always matches what is
 * on screen: the chosen filter, the chosen sort, and nothing that was filtered out.
 */
data class MonthMark(val month: String, val year: String, val count: Int, val entryIndex: Int) {
    val label: String get() = "$month $year"
}

/** Pure grouping and sorting, kept free of Android so it can be unit-tested. */
object GalleryLogic {

    fun isScreenshot(file: VaultFile) = file.kind == FileKind.PHOTO && file.name.contains("screenshot", ignoreCase = true)

    /** Everything the gallery shows: photos, GIFs and videos, matching [filter]. */
    fun media(all: List<VaultFile>, filter: GalleryFilter): List<VaultFile> = all.filter { f ->
        val isMedia = f.kind == FileKind.PHOTO || f.kind == FileKind.GIF || f.kind == FileKind.VIDEO
        isMedia && when (filter) {
            GalleryFilter.ALL -> true
            GalleryFilter.PHOTOS -> f.kind == FileKind.PHOTO && !isScreenshot(f)
            GalleryFilter.VIDEOS -> f.kind == FileKind.VIDEO
            GalleryFilter.GIFS -> f.kind == FileKind.GIF
            GalleryFilter.SCREENSHOTS -> isScreenshot(f)
            GalleryFilter.FAVORITES -> f.favorite
        }
    }

    /**
     * What the gallery shows under [settings]: the chosen filter, then the length limit, which
     * only applies where it means anything -- the videos filter.
     */
    fun media(all: List<VaultFile>, settings: GallerySettings): List<VaultFile> {
        val shown = media(all, settings.filter)
        val limit = settings.duration.takeIf { settings.filter == GalleryFilter.VIDEOS } ?: return shown
        return shown.filter { limit.keeps(it.durationMs) }
    }

    private fun comparator(settings: GallerySettings): Comparator<VaultFile> {
        val base: Comparator<VaultFile> = when (settings.sortBy) {
            SortBy.DATE -> compareBy { it.takenAt }
            SortBy.SIZE -> compareBy { it.sizeBytes }
            SortBy.TYPE -> compareBy<VaultFile> { typeLabel(it) }.thenByDescending { it.takenAt }
        }
        // Type sorting reads naturally A→Z as "descending"; other sorts put the biggest/newest first.
        val ordered = if (settings.sortBy == SortBy.TYPE) {
            if (settings.descending) base else base.reversed()
        } else {
            if (settings.descending) base.reversed() else base
        }
        return ordered.thenBy { it.id }
    }

    fun typeLabel(file: VaultFile): String = when {
        isScreenshot(file) -> "Screenshot"
        file.kind == FileKind.GIF -> "GIF"
        file.kind == FileKind.VIDEO -> "Video"
        else -> file.mimeType.substringAfter('/').uppercase().let { if (it == "JPEG") "JPG" else it }
    }

    /**
     * Builds the grid. With month grouping, months appear newest first (oldest first when
     * sorting by date ascending) and the chosen sort applies inside each month.
     */
    fun build(all: List<VaultFile>, settings: GallerySettings, zone: ZoneId = ZoneId.systemDefault()): List<GalleryEntry> {
        val files = media(all, settings)
        val order = comparator(settings)
        if (!settings.groupByMonth) {
            return files.sortedWith(order).mapIndexed { i, f -> GalleryEntry.Item(f, i) }
        }
        val byMonth = files.groupBy { YearMonth.from(Instant.ofEpochMilli(it.takenAt).atZone(zone)) }
        val monthOrder = if (settings.sortBy == SortBy.DATE && !settings.descending) byMonth.keys.sorted() else byMonth.keys.sortedDescending()
        val entries = mutableListOf<GalleryEntry>()
        var position = 0
        for (month in monthOrder) {
            val inMonth = byMonth.getValue(month).sortedWith(order)
            entries += GalleryEntry.Header(
                month = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                year = month.year.toString(),
                count = inMonth.size,
                bytes = inMonth.sumOf { it.sizeBytes },
            )
            inMonth.forEach { entries += GalleryEntry.Item(it, position++) }
        }
        return entries
    }

    /** The flat order the viewer and reel page through. */
    fun flat(entries: List<GalleryEntry>): List<VaultFile> = entries.filterIsInstance<GalleryEntry.Item>().map { it.file }

    /**
     * The months [entries] holds, in the order they appear, each with where its heading sits.
     * Empty when the gallery is not grouped by month, since then there is nothing to jump to.
     */
    fun months(entries: List<GalleryEntry>): List<MonthMark> =
        entries.mapIndexedNotNull { index, entry ->
            (entry as? GalleryEntry.Header)?.let { MonthMark(it.month, it.year, it.count, index) }
        }
}
