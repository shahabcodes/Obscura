package com.shahaabapps.obscura.ui.gallery

import com.shahaabapps.obscura.vault.FileKind
import com.shahaabapps.obscura.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The videos filter can be held to a length the user chooses, on the side they choose.
 *
 * The point of these: "under 50 seconds" must mean exactly the videos under fifty seconds, and
 * nothing the user has must ever vanish because its length could not be read.
 */
class VideoLengthFilterTest {

    private fun video(id: String, seconds: Int?) = VaultFile(
        id, "$id.mp4", "video/mp4", FileKind.VIDEO, 1_000, 1_700_000_000_000, 1_700_000_000_000,
        ByteArray(72), id.repeat(8), durationMs = seconds?.let { it * 1000L },
    )

    private val photo = VaultFile(
        "p", "beach.jpg", "image/jpeg", FileKind.PHOTO, 1_000, 1_700_000_000_000, 1_700_000_000_000,
        ByteArray(72), "pppppppp",
    )

    private val all = listOf(
        video("short", 12),
        video("fifty", 50),
        video("long", 300),
        video("unknown", null),
        photo,
    )

    private fun shown(limit: DurationLimit?, filter: GalleryFilter = GalleryFilter.VIDEOS) =
        GalleryLogic.media(all, GallerySettings(filter = filter, duration = limit)).map { it.id }

    @Test
    fun underAThresholdKeepsOnlyTheShorterVideos() {
        // A video of exactly fifty seconds is not under fifty seconds.
        assertEquals(listOf("short", "unknown"), shown(DurationLimit(50, longer = false)))
    }

    @Test
    fun overAThresholdKeepsOnlyTheLongerVideos() {
        assertEquals(listOf("long", "unknown"), shown(DurationLimit(50, longer = true)))
    }

    @Test
    fun noLimitShowsEveryVideo() {
        assertEquals(listOf("short", "fifty", "long", "unknown"), shown(null))
    }

    @Test
    fun aLengthThatCouldNotBeReadNeverHidesAFile() {
        // Hiding a file the user owns is the one outcome a filter must not produce.
        assertTrue("unknown" in shown(DurationLimit(1, longer = false)))
        assertTrue("unknown" in shown(DurationLimit(100_000, longer = true)))
    }

    @Test
    fun theLimitOnlyAppliesWhereLengthMeansSomething() {
        // Under any other filter the photos and GIFs would have no length to judge, so the
        // limit stands aside rather than quietly emptying the grid.
        val everything = GalleryFilter.ALL
        assertEquals(
            listOf("short", "fifty", "long", "unknown", "p"),
            shown(DurationLimit(1, longer = false), everything),
        )
    }

    @Test
    fun theGridItselfHonoursTheLimit() {
        val entries = GalleryLogic.build(
            all,
            GallerySettings(filter = GalleryFilter.VIDEOS, duration = DurationLimit(50, longer = false)),
        )
        assertEquals(listOf("short", "unknown"), GalleryLogic.flat(entries).map { it.id })
    }

    @Test
    fun aLengthPastADayIsTheSameAsNoLimit() {
        // The ceiling the sheet holds typing to, and the one a stored value is read against.
        assertEquals(86_400, DurationLimit.MAX_SECONDS)
        // At the ceiling the limit still means something: a day-long video is not under a day.
        assertEquals(emptyList<String>(), shown(DurationLimit(DurationLimit.MAX_SECONDS, longer = true)) - "unknown")
    }

    @Test
    fun theLimitReadsAsAPersonWouldSayIt() {
        assertEquals("Under 50s", DurationLimit(50, longer = false).label)
        assertEquals("Over 1m", DurationLimit(60, longer = true).label)
        assertEquals("Under 1m 30s", DurationLimit(90, longer = false).label)
        assertEquals("Over 10m", DurationLimit(600, longer = true).label)
    }
}
