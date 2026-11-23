package com.shahaabapps.obscura

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.ui.navigation.LastPlace
import com.shahaabapps.obscura.vault.Album
import com.shahaabapps.obscura.vault.IncomingFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: leaves a locked vault on the device (passphrase [VideoLengthFixture.PASSPHRASE])
 * for driving the video player's Share by hand into a real receiving app. Skipped unless asked:
 *
 *   adb shell am instrument -w -e seedShare 1 -e class com.shahaabapps.obscura.VideoShareManualSeed \
 *       com.shahaabapps.Obscura.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The vault: beach.jpg, then quick.mp4 (30s, a favourite), middle.mp4 (40s) and feature.mp4
 * (50s, in album "Trips"). Each clip's SHA-256 is printed as "SEEDSHA name sha".
 */
@RunWith(AndroidJUnit4::class)
class VideoShareManualSeed {

    @Test
    fun seed() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedShare") == "1")
        val entry = VideoLengthFixture.entry
        LastPlace.forget()
        VideoLengthFixture.wipe()
        val clips = listOf(30 to "quick.mp4", 40 to "middle.mp4", 50 to "feature.mp4")
            .map { (secs, name) -> name to VideoLengthFixture.encodeMp4(secs, "seed-$name") }
        clips.forEach { (name, f) ->
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            println("SEEDSHA $name $sha ${f.length()}")
        }
        runBlocking {
            entry.session().create(VideoLengthFixture.PASSPHRASE.toCharArray())
            val vault = entry.session().openVault!!
            vault.files.put(VideoLengthFixture.jpeg().inputStream(), IncomingFile("beach.jpg", "image/jpeg", 1_700_000_000_000))
            clips.forEachIndexed { i, (name, f) -> VideoLengthFixture.seal(f, name, 1_700_000_100_000 + i * 50_000L) }
            val all = vault.database.files().observeAll().first()
            vault.database.files().setFavorite(all.single { it.name == "quick.mp4" }.id, true)
            vault.database.albums().insert(Album("trips-id", "Trips", 0xFF336699, System.currentTimeMillis()))
            vault.database.files().setAlbum(all.single { it.name == "feature.mp4" }.id, "trips-id")
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { entry.session().lock() }
    }
}
