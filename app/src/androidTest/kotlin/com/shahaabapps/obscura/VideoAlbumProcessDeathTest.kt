package com.shahaabapps.obscura

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.gallery.GalleryFilter
import com.shahaabapps.obscura.vault.IncomingFile
import com.shahaabapps.obscura.vault.UnlockResult
import com.shahaabapps.obscura.vault.VaultState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two ends of a process death with the video player's album sheet open. The middle is
 * driven by hand, because a real death with saved state needs the activity to outlive the
 * process, which no instrumentation run can do:
 *
 *   adb shell am instrument -w -e albumPhase seed \
 *     -e class com.shahaabapps.obscura.VideoAlbumProcessDeathTest#seed \
 *     com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *   # launch, unlock, Photos -> middle.mp4 -> Album -> New album -> type a name; then
 *   adb shell input keyevent KEYCODE_HOME
 *   adb shell am kill com.shahaabapps.Obscura          # background death, saved state kept
 *   adb shell am start -n com.shahaabapps.Obscura/com.shahaabapps.obscura.MainActivity
 *   # must be the lock screen, nothing of the sheet; unlock; the sheet must not come back
 *   adb shell am instrument -w -e albumPhase check \
 *     -e class com.shahaabapps.obscura.VideoAlbumProcessDeathTest#check \
 *     com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Without the phase argument both halves skip themselves.
 */
@RunWith(AndroidJUnit4::class)
class VideoAlbumProcessDeathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry get() = VideoLengthFixture.entry
    private fun phase(): String? = InstrumentationRegistry.getArguments().getString("albumPhase")

    @Test
    fun seed() {
        assumeTrue("run with -e albumPhase seed", phase() == "seed")
        VideoLengthFixture.wipe()
        val clips = listOf(3 to "quick.mp4", 30 to "middle.mp4", 40 to "feature.mp4")
            .map { (secs, name) -> name to VideoLengthFixture.encodeMp4(secs, "pd-$name") }
        runBlocking {
            entry.session().create(VideoLengthFixture.PASSPHRASE.toCharArray())
            entry.session().openVault!!.files.put(
                VideoLengthFixture.jpeg().inputStream(),
                IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000),
            )
            clips.forEachIndexed { i, (name, file) -> VideoLengthFixture.seal(file, name, 1_700_000_100_000 + i * 50_000L) }
        }
        instrumentation.runOnMainSync {
            entry.gallerySettings().update { it.copy(filter = GalleryFilter.VIDEOS, descending = false) }
        }
        println("SEEDED files=${runBlocking { entry.session().openVault!!.database.files().all() }.size} blobs=${entry.store().blobDir.walk().count { it.isFile }}")
    }

    @Test
    fun check() {
        assumeTrue("run with -e albumPhase check", phase() == "check")
        // This process is the instrumentation's own: it starts locked whatever the last one did.
        assertFalse(entry.session().state.value is VaultState.Unlocked)
        runBlocking {
            assertEquals(UnlockResult.Success, entry.session().unlockWithPassphrase(VideoLengthFixture.PASSPHRASE.toCharArray()))
            val files = entry.session().openVault!!.database.files().observeAll().first()
            val blobs = entry.store().blobDir.walk().count { it.isFile }
            val albums = entry.session().openVault!!.database.albums().observeSummaries().first()
            println("CHECK files=${files.map { "${it.name}:${it.albumId}:${it.favorite}" }} blobs=$blobs albums=${albums.map { "${it.name}:${it.count}" }}")
            assertEquals(4, files.size)
            assertEquals(4, blobs)
            assertEquals(files.size, files.map { it.sha256 }.toSet().size)
            files.forEach { assertTrue(entry.session().openVault!!.files.blobFile(it.id).isFile) }
        }
    }
}
