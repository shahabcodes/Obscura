# Changelog

All notable changes to Obscura are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). Every release has a section whose heading
matches `obscura.versionName` in `gradle.properties`; the release workflow publishes that
section as the GitHub release notes.

## [Unreleased]

### Fixed
- **Swiping sideways did nothing on a video.** Photos have always moved to the next one with a swipe, and the tour said videos did too, but the video player only knew taps and pinches. A swipe on the picture now moves to the next video or back to the one before, and the picture follows the finger as it goes. Past either end it stays put rather than wrapping round. Zoomed in, a drag still moves around the picture instead.
- **"Next" in the player was not the next video on the screen you came from.** The player went through every video newest first, whatever the gallery was showing — its sort order, its filter and the new length limit all ignored, so the next video could be one the gallery was hiding. Opened from the gallery, the player now follows the gallery exactly, and "Up next" with it. Opened from a search or an album, every video is still in reach.

### Tests
- Three on-device tests swipe through real clips in a gallery sorted oldest first, so the library's own newest-first order and the gallery's disagree: next and back, a swipe past the end, and a video the length limit hides. Two of them fail if the player goes back to the library's order.

## [0.15.0] - 2026-10-27

### Added
- **Filter videos by how long they are.** On the Videos filter there is now a length. Tap it, choose under or over, and either take a preset (10s, 30s, 1m, 2m, 5m, 10m) or type any number of seconds: fifty seconds, under, and only the clips shorter than that are shown. The choice stays until you lift it, and the pill always says what is in force. A clip whose length Obscura could not read is always shown, because a filter that hides a file you own is worse than one that shows an extra.

### Fixed
- **A selection was counted before the grid, so the app could ask about more files than it would touch.** Narrow the filter after selecting — easy to do now there is a length — and the header still said "2 selected" and the confirmation still asked "Shred 2 files?" while only the one file left on screen would go. The count now comes from what is on screen, which is what every action already worked on. Nothing was ever destroyed beyond what was showing.

### Known
- The video length is remembered across vaults, as the sort order, the filter and the column count already are. Set one in your vault and the decoy's Videos filter shows the same length.

### Tests
- Eight unit tests on the boundary and the wording, and four on-device tests that encode a real two-second clip and a real ten-second one, import them, and work the sheet the way a hand would — the preset, a typed length, the side, and the keyboard's own done key.
- The reel's shape test was reading a measurement that means nothing while Compose's clock is held: the video surface was measured correctly and simply had not been placed, so the test reported it at 0x0 and failed whenever the timing fell that way. It now reads the size the surface was measured at, with the clock running, from a clip sealed with the shape a real import records.
- Nineteen further on-device journeys: locking and panic-locking with the sheet open and the keyboard up, a real process kill and cold start with a length in force, stored lengths corrupted every way they can be, backgrounding and rotation, a length that empties the grid, selecting and then narrowing it, and the decoy in both directions.

## [0.14.1] - 2026-10-26

### Fixed
- **Exporting could leave a file behind and still say it had finished.** Three ways, all the same mistake: a name that another file could arrive at too. A file called "Obscura index.csv" was renamed out of the way before the check for a contested name; a file whose own name already looked like a copy this makes — "beach [1a2b3c].jpg", which is what an earlier export produces — could be handed the name meant for another file; and the short piece of a file's id used to tell two apart was small enough for a few thousand files to collide within. A contested name now carries the file's whole id, which nothing else has.
- **A file replaced since the last export could keep the old copy.** Deciding what was already copied from a name and a size cannot tell one file from another that took its place: shred a photo, bring in a different one of the same name and size, and the old bytes stayed while the list named the new file. The list now records which file each copy came from, and a copy is left alone only when it is that same file.
- The list is kept when a run is interrupted, and written out row by row as it goes, so a run that is killed outright leaves a true account of how far it got rather than an empty file beside real copies. It is also what lets the next run carry on.
- A copy of a file you have since shredded is taken away on the next run. Leaving a readable copy of a file you destroyed is the opposite of what shredding is for. Only copies Obscura knows it made are touched.
- Locking the vault during an export is reported as the run being stopped, not as the file failing to copy.
- Sheets and dialogs are windows of their own and did not inherit the rule that only genuine assistive tools may read the screen — and they are where file names appear, such as the clip a GIF is being made from. All of them now say so themselves.
- A list that cannot be written no longer fails the whole export: the copies still go out, and the trouble is reported at the end.
- Coming back to a file that was shredded while the vault was locked showed whichever file happened to be first. The viewer and the player now go back instead.
- A finished export's figures are cleared as the next vault opens, rather than being there for it to see.
- The tour no longer describes a folder for each month.

### Tests
- 138 unit tests and 223 on-device tests. Twelve of them attack the export: two files named like the list, a file named like another file's copy, a file replaced between runs, a hard kill partway, names differing only by case, 300-character and emoji names, and two and a half thousand files in one folder.


## [0.14.0] - 2026-10-26

### Added
- **Export everything to a folder.** In Security. Writes a readable copy of the whole vault into a folder you choose — a USB drive, an SD card, anywhere the system picker will go. Built for a vault far bigger than this phone's memory: files are walked a page at a time and streamed one by one, so a vault of a few hundred gigabytes costs no more memory than a small one.
  - It keeps going while you use other apps, holding itself up with a notification that shows how far along it is. The vault stays open until it finishes, and locks again afterwards.
  - Everything goes into one folder, called "Obscura export", inside the folder you pick. What you picked is otherwise left exactly as it was.
  - **Your dates, albums and favourites come too.** Photos and videos already carry their own dates inside them, untouched. Each copy is also given its original date where the destination allows it — a folder reached through the system picker usually will not, because Android offers no way to set a file's date there. So a list, "Obscura index.csv", is written beside the copies: every file's name, the date and time it was taken, its kind, size, album, and whether it was a favourite. It is plain text, like the copies themselves, and the screen says so before you start.
  - **It can be picked up where it left off.** Run it again into the same folder and anything already copied whole is left alone. That covers a drive being unplugged, the vault locking, you stopping it — and Android's own limit on how long a background job may run, which a multi-hour export will reach. It says so plainly when that happens, and offers to carry on. A file's name at the destination depends only on that file, so importing or shredding things in between cannot make one file's copy stand in for another's.
  - **It checks there is room first**, counting only what is still missing, and says how much more is needed rather than failing one file at a time for hours.
- **Coming back to where you were.** The vault locks the moment the screen turns off, so glancing at a notification used to put you back at the top of the app. Unlocking now returns you to the screen you were on, down to the photo you had open. It is kept in memory only and never written down, so it starts afresh after the app is closed; and a place in one vault is never restored into the other, so the decoy still shows nothing of where the real vault had been.
- **A tour of the app**, also in Security: every control and every gesture, including the ones with no button to find — pinching the gallery to change how many fit across, tapping a picture to clear the screen, swiping through reel mode.

### Fixed
- **Deleting a vault, or being wiped after ten wrong attempts, could take the app down with it.** The gallery, home and search screens watch the encrypted index for changes; when the vault was closed or destroyed underneath them, a reading already on its way failed where nothing could catch it. They now stop quietly, which is the only sensible answer once there is nothing left to read.
- Picking a folder for an export used to let the vault lock while the system picker was in front, so you came back to a locked vault and nothing happened. The vault is now held open while you choose, as it already was for backups.
- The export's own "already there" check could skip a file because of a copy it had just written itself: two different files sharing a name and a size in the same month meant only one came out, and it was reported as finished. What was already at the destination is now kept apart from what the run has made.
- The remembered export folder is kept separately for the decoy vault and is not offered there at all, so opening the decoy no longer shows that another vault had set one up.
- A wipe now forgets the export folder and gives up its write permission. A decrypted export is the one thing a wipe cannot reach; it should not be left named with access still granted.

### Tests
- 138 unit tests and 206 on-device tests. Exporting is now tested through the Storage Access Framework — the folder a real picker hands over — rather than a plain directory standing in for it: files are taken in from a folder, the originals removed, and then written back into that same folder byte for byte. Exporting everything is tested stopped partway and run again (nothing copied twice, nothing missed), across more files than fit in one page of the index, with memory measured so it cannot creep up with the number of files, with the vault locked mid-run, and left running while the app is backgrounded. The cases that would lose a file silently each have a test of their own: two files sharing a name and a size, one of them shredded between runs, a stranger's file already at the destination, and a copy left half-written by a run that was killed.

## [0.13.0] - 2026-10-25

### Added
- **Jump to a month.** A calendar button in the gallery lists every month you have files from, gathered under its year with a count, and takes you straight there. It follows whatever filter you are on, so asking for videos lists only the months that have videos. It appears once there is more than one month to choose between.

### Changed
- **GIFs look much closer to the video they came from.** The palette was chosen by counting shades rather than pixels, so a wide smooth background — a sky, a wall, a blurred backdrop — collapsed into a few flat patches, while a handful of stray colours each took a slot of their own. Colours are now chosen by how much of the picture they actually cover, and what rounding is left is dithered, which turns banding into a fine texture that reads as the shade in between. On a test clip the average pixel is a little over twice as close to the original.
- **Making a GIF is about three times faster.** Frames were fetched one at a time, and asking a video for an arbitrary moment means decoding everything from the keyframe before it, so a twenty-frame GIF decoded hundreds of frames. Obscura now plays the clip straight through once instead.
- GIFs are roughly twice the size as a result of the dithering, which is the price of the smoother picture.
- A video damaged part way through now yields a GIF of the frames that survived, rather than refusing the whole clip.

### Fixed
- A file that is not really a video used to answer with the platform's own error text, status code and all. It now says plainly that Obscura can't read it as a video.
- Turning the phone while making a GIF no longer takes the sheet, the progress and the confirmation with it.
- Locking the vault now stops the decoder immediately rather than at the next frame, and a video that stops responding is given up on instead of being read forever. Both mattered because reading a video holds the vault open while it runs.
- A damaged or non-video file no longer leaves its reader open; repeatedly trying one could previously use up the app's file handles.

### Tests
- 132 unit tests and 175 on-device tests. A new measurement compares a GIF against the video it came from, pixel by pixel, and records how long it took, so neither the colours nor the speed can quietly slip back. Others cover awkward videos — very short, started past the end, portrait with a rotation flag, odd sizes, truncated, audio-only, and files that are not videos at all — and what happens when a GIF is interrupted by a lock, a cancel or a second GIF at once, each checking that nothing readable is left behind. The jump-to-month list is checked against the headings actually on screen, under every filter and both sort directions, including when the filter changes or files vanish while it is open.

## [0.12.0] - 2026-10-25

### Added
- **Make a GIF from a video.** In the video player, pause where you want it to start, tap GIF, and pick 2, 3 or 5 seconds. The GIF is sealed into your vault like any other file and appears under GIFs. It never leaves the phone: the frames are read straight out of the encrypted video and the GIF is built in memory, with nothing written to storage along the way.
  - Obscura writes the GIF itself rather than adding a library for it, so the app gains no new dependency and still has no way to reach the network.
  - The GIF keeps the video's shape and is scaled to a sensible size, with one set of colours chosen across the whole clip so the file stays small.

### Fixed
- **Videos were stretched or squeezed.** The picture filled whatever space it was given, so a 4:3 video looked wrong on a tall phone. Videos now keep their own shape in reel mode and in the video player, including full screen.
- **A video shot holding the phone upright was recorded on its side.** A phone stores such a video as a landscape picture plus a quarter turn, and Obscura noted down the landscape figures. The details line now reads the right way round, and the player no longer squeezes the picture in the moment before it has read the file.
- A GIF made near the end of a video is now as long as the video has left, rather than repeating one frozen frame to fill the time asked for.
- The GIF now comes from the clip you were watching at the moment you tapped, even if the player would otherwise have moved on to the next video, and the video pauses while you choose.
- While a GIF is being made, its sheet can no longer be swiped away, which used to leave it working out of sight with no way back to it.
- A video with no recorded length no longer produces a GIF a fifth of a second long.

### Tests
- 128 unit tests and 150 on-device tests. The GIF writer is checked against Android's own decoder, pixel for pixel, including a large noisy frame — the case that exercises the compressor's hardest path. The conversion is checked end to end: a real video in, an animated GIF sealed in the vault out, at the video's own shape, including a clip asked for past its end and a file whose frames cannot be read at all. Separate tests measure the picture on screen against the video's real shape, check that a video shot upright is recorded upright, and make a new vault straight after deleting the old one.

## [0.11.0] - 2026-10-25

### Added
- **GIFs are their own category.** They have a GIFs filter in the gallery and a tile on Home once you have some, and they now play instead of showing a frozen first frame, both in the gallery viewer and in reel mode. GIFs already in your vault move to the new category by themselves the next time you unlock; nothing needs re-importing.
- **Pinch the gallery to change how many items fit across it.** Spread two fingers for fewer, bigger items; pinch them together for more. Anywhere from 2 to 6 across, with a small nudge of vibration at each step. Scrolling and press-and-drag selection are unaffected.
- **Tap a photo, GIF or video to clear the screen.** Everything but the picture fades away; tap again to bring it back. In the video player this gives a proper full-screen view.
- **Pinch to zoom in videos**, drag to move around once zoomed, and double-tap to go back to normal.
- **Videos play in reel mode.** Until now the reel showed a still frame with a play button that did nothing. A video now plays, the progress bar follows the video rather than the slideshow timer, and the reel moves on when the video ends instead of cutting it off. Only the video on screen is decrypted.
- **Delete, where it was missing.** Audio files could not be deleted anywhere at all; videos could not be deleted while watching them. Both now have Shred, with the same confirmation as everywhere else.
- **Delete this vault**, in Security. It asks for your passphrase, then destroys every sealed file, the decoy vault and every key. Nothing can bring them back. Asking for the passphrase means someone holding your unlocked phone cannot wipe it.

### Changed
- The encrypted index moves to version 4 (GIFs get their own kind). Existing vaults upgrade on first unlock, with nothing lost. Note that a vault opened by 0.11.0 cannot be opened by an older version of Obscura afterwards; your files are safe, but you would need to install 0.11.0 or newer again.
- A file's type is now read a little more loosely at import, so a GIF recorded as "image/GIF" is still treated as a GIF.
- Very large GIFs fall back to a still preview rather than being decrypted whole into memory.

### Tests
- 119 unit tests and on-device tests covering the new gestures, GIF categorisation and migration, deleting the vault, and a video playing through in the reel.
- The security review and the end-to-end tester both ran before release. The security review found the reel would stall on a video instead of moving on, which is fixed; five smaller findings were fixed with it.

## [0.10.1] - 2026-10-25

### Fixed
- **Opening a PDF crashed the app.** The viewer closed the document the instant it finished opening, so the first page was drawn against a closed document. Reported from a real phone, and now covered by tests that open PDFs through the actual viewer.
- Two further faults found while chasing that crash, either of which could have crashed or frozen the app on its own:
  - A PDF may declare a page metres long. Obscura asked for a picture of it at full size, which for one test page meant 700 MB of memory: enough to lock the phone up rather than merely fail. Pages are now drawn at a size that fits in memory, keeping their proportions.
  - Pages that were still being drawn when you left the document brought the app down. They are now dropped quietly.
- Large PDFs no longer have to fit in memory. A PDF is decrypted a piece at a time as you scroll, instead of being decrypted into memory in full before the first page appears, so big scans open quickly on a phone that is short of memory. Nothing is written to storage, as before.
- PDF previews now work on Android 10, which previously said they needed Android 11.
- A PDF that can't be opened (damaged, or protected by someone else's password) says so clearly, and a page that can't be drawn leaves its place blank instead of taking the viewer down.
- **The vault could stay unlocked after the screen went off.** With a system file picker or share sheet on top, Android can freeze Obscura, so neither its lock timer nor the screen-off signal reaches it; coming back then cancelled the lock that was already due. Obscura now checks the clock when it returns, and a lock that is waiting for work to finish is no longer forgotten.
- Killing the app mid-import left a half-written file behind for good. These are encrypted, but they wasted space; they are now cleared the next time you unlock.
- Home's Documents tile counted fewer files than the Documents screen listed.

### Tests
- 114 unit tests and 100 on-device tests. New: PDFs opened through the real viewer (ordinary, 300-page, page-metres-long, damaged), closing a document while forty pages render at once, the vault locking after a picker held it open, and leftover import files being swept.
- End-to-end testing of whole journeys with interruptions — backgrounding and screen-off mid-import, panic lock, killing the app, rotation, low storage — now runs before every release alongside the security review. That testing found the crash above.

## [0.10.0] - 2026-10-24

### Added
- Multi-select in the gallery, with iOS-style gestures:
  - Long-press a photo or video and keep dragging to select every tile you pass. Drag back towards where you started to let tiles go again.
  - While selecting, swipe sideways across tiles to select a run of them, or to deselect them if the first one was already selected. Up and down still scroll.
  - Drag near the top or bottom edge and the gallery scrolls along with you.
  - Tap tiles to add or remove them, or use Select and Select all in the header. Back or the close button ends selecting.
  - Selected tiles shrink behind a sun-coloured frame with a check mark, and a gentle vibration marks the start.
- Bulk actions for the selection: share, save copies, add to an album (or a new one), favourite or unfavourite, and shred.
- Save copy: decrypted copies go to a folder or USB drive you pick (from the selection), or to a file you name (from the photo viewer). Copies never overwrite: a second "beach.jpg" becomes "beach (2).jpg". Big exports run in the background with a progress card, a notification without file names, and Cancel. Obscura warns first that copies outside the vault aren't protected.
- Share: hands photos, videos or files to one app you choose, through private links that decrypt on the fly and support seeking, so nothing decrypted is written to storage. Links are read-only, can't be guessed, don't carry the file name, and work for 15 minutes, only while Obscura stays unlocked. Locking ends them for good: an app that already had a file open can't read on after the vault is unlocked again, even from the decoy vault. The vault stays open for up to a minute so the other app can read them.
- Share and Save copy in the photo viewer, whose action bar is now compact icons with labels.

### Changed
- Favouriting, moving and shredding many files at once now run as batched database updates, so thousands of files take seconds.
- The full-screen viewer shows "no preview" for a damaged file instead of closing.
- Any lock (panic lock, Lock now, screen off) stops an export straight away, even in the middle of a file, and removes the partial copy.

### Tests
- 110 unit tests and 84 on-device tests, including:
  - Real touch gestures: long-press and drag, sideways swipes and tap toggles.
  - Exports compared byte for byte, and stopped mid-file by a lock.
  - Share links streamed, seeked and refused for writing. A link stays dead after it expires, and an open reader stays dead after a lock and a fresh unlock.
- Large-vault stress test on the emulator with 20,000 files across seven years: gallery opens in 1.1 s, scrolls end to end and back in 2.5 s, all 20,000 selected and favourited in 3.1 s, 15 MB of memory in use; grouping by month 0.14 s, search 0.02 s.
- A 5 GiB file through the real file cipher on a PC: sealed at 389 MB/s, unsealed at 1,095 MB/s, identical byte for byte, and read correctly at offsets past 4 GiB.
- Before release, an automated security review of every change since 0.9.0, now part of every release. It found no critical or high issues. The three medium findings were fixed before release: share readers outliving a lock, exports continuing after a lock, and a picker hold that could leak on rotation.

## [0.9.0] - 2026-10-24

### Added
- Save your recovery key as a password-protected PDF, both at setup and later:
  - The PDF is encrypted with the PDF standard's strongest protection (AES-256, revision 6). Any PDF viewer can open it, but only with the password you choose when saving. Without it, the file is ciphertext.
  - It holds a printable A4 page: the key in large type, a short vault fingerprint so you can tell vaults apart, the date, how to store it, and how to use it.
  - Printing is allowed; editing and copying text out are not. Your vault passphrase is refused as the PDF password, because the page has to work when that passphrase is forgotten.
  - Save it anywhere through Android's picker: Downloads, a USB drive or a folder. The vault stays open while the picker is up.
- Security → Recovery key → Create a new recovery key. Recovery keys are never stored, so the one shown at setup can't be shown again; if you skipped saving it, this is how you get one. It needs your passphrase (wrong guesses count toward the lockout), the old key stops working at once, and your passphrase, PIN and fingerprint stay the same.
- The Prism motion kit: eight Lottie animations, bundled in the app (about 70 KB, nothing downloaded):
  - Unlock: the shackle swings open after a PIN, passphrase or fingerprint unlock.
  - Panic lock: a pink "Locked instantly." confirmation once the phone is face-up again.
  - Sealing: a file dropping into the vault during imports, backups and other waits.
  - Scan: a radar sweep while finding duplicates and checking a backup.
  - Success: when space is freed, a backup is done or an import finishes.
  - Empty states: floating shapes on every empty list.
  - Decrypting: shimmering tiles while photos and PDFs decrypt.
  - Heart burst: favourites in the reel and the viewer.
  - With the system's "remove animations" setting on, each shows a still frame instead.
- The real Prism fonts: Space Grotesk for text and JetBrains Mono for labels, bundled in the app.
- Security → About → Open-source licences, with the full licence texts bundled so it works offline.

### Changed
- Other apps can no longer draw over Obscura (Android 12+), so nothing can cover the PIN pad or fake a prompt. Older versions ignore touches that arrive while another window covers the app.
- On Android 14+, only genuine assistive tools such as TalkBack can read Obscura's screen; other accessibility services can't scrape file names or your recovery key.
- Uninstalling now asks whether to keep the encrypted vault, so a mistaken uninstall doesn't erase it (not kept unless you tick the box).
- Backups check free space before copying anything, and remove shredded files first to make room. Restores check the phone has room.

### Fixed
- Shredding a file while a backup ran made the backup fail with a misleading "drive isn't available" message. Such files are now simply left out.
- A drive formatted as FAT32 now explains that it can't hold files over 4 GB instead of failing with a generic error.
- If Android loses the PIN's device key (for example after reinstalling with data kept), the PIN is switched off and the lock screen asks for your passphrase, instead of showing an error. This doesn't count as a wrong attempt.

### Tests
- 101 unit tests and 72 on-device tests, including:
  - The recovery-key PDF opened with an independent PDF reader (Apache PDFBox, tests only): it opens only with the right password, uses AES-256 revision 6, carries the key and blocks editing.
  - Creating a new recovery key, and the PDF password rules in the UI.
  - Every animation parsed and drawn by Lottie, with colours checked where the design fixes them.
  - Free-space checks, files shredded during a backup, and the lost-PIN-key fallback.
- Real fingerprint unlock on the emulator, with emulated fingers: enrolment, the wrong finger refused, the right finger unlocking, and fingerprint unlock switching itself off after a new fingerprint is added to the phone.

## [0.8.0] - 2026-10-23

### Added
- Encrypted backup (Security → Backup) to a USB drive or any folder you pick with Android's own picker:
  - Copies the vault exactly as it is stored: every file, thumbnail and the index, all still encrypted. Nothing is decrypted on the way, so a backup can only be opened with your passphrase or recovery key, and no file name or content appears on the drive in readable form.
  - The first backup copies everything. Later backups copy only new files and remove shredded ones from the drive.
  - Every backup is checked when it finishes: each sealed file must be on the drive at the right size. A backup that was interrupted is marked as unfinished and can't be restored by mistake; running it again completes it.
  - The chosen drive or folder is remembered, and Security shows when you last backed up.
  - Progress card and a notification that never shows file names. The vault stays open until the copy finishes, then locks. A backup can't start while an import is running.
  - If the folder already holds a backup of a different vault, Obscura asks before replacing it.
  - Inside the decoy vault, the decoy is what gets backed up, with its own remembered location.
- Restore (Welcome → Restore from a backup drive): pick the drive or its "Obscura Backup" folder, and the encrypted vault is copied back onto the phone, then you unlock it with your passphrase or recovery key. Restoring is only offered when there is no vault on the phone, so it can never overwrite one. A failed restore leaves nothing behind.
- PIN and fingerprint unlock are not part of a backup, because they only work on the phone that set them up. Set them up again after restoring.

### Tests
- 85 unit tests and 55 on-device tests, including backing up a real vault, wiping it, restoring and unlocking it with every file and thumbnail intact, incremental updates, a check that nothing readable reaches the drive, interrupted backups, the replace-another-vault prompt, and the Backup and Restore screens.

## [0.7.0] - 2026-10-22

### Added
- Duplicate finder (Gallery → Duplicates):
  - Finds exact copies of any file (same contents), look-alike photos (re-saved, resized or lightly edited, matched by a 64-bit visual fingerprint), and bursts (similar shots taken within 5 seconds).
  - Suggests the copy to keep: the first one sealed for exact copies, otherwise the highest resolution. Every other copy is pre-marked, and you can change any mark.
  - Shows the space you can free, filters by kind, and shreds only after you confirm.
  - Fingerprints are computed from the encrypted thumbnails, stored in the encrypted index and reused on later scans.
- Panic lock: lay the phone face-down and the vault locks instantly, even in the middle of an import. It only listens to the motion sensor while Obscura is on screen. It's on by default and can be switched off in Security.
- Decoy vault: a second passphrase (and optional PIN) that opens a completely separate vault. Fill it with harmless files, and anyone made to watch you unlock sees a normal vault. Wrong guesses must miss both vaults to count. Fingerprint and recovery key always open the real vault. Inside the decoy, decoy and wipe settings are hidden. Set up and delete it from Security; its secrets must differ from the real ones.
- Wipe after 10 wrong attempts (off by default, with a strong warning before turning it on): after 10 wrong passphrases or PINs in a row, both vaults and every key that could open them are destroyed and the app returns to the welcome screen. Error messages and the lockout card show how many attempts are left.

### Changed
- The encrypted database moves to version 3 (adds visual fingerprints). Existing vaults upgrade automatically on first unlock.
- The Gallery header now has Reel mode and Duplicates side by side.

### Tests
- 77 unit tests and 49 on-device tests, including the decoy vault (separate files, PIN and passphrase routing, rules), wipe-after-10 with lockout waits, panic lock during an import, duplicate scanning of real JPEGs (exact copy, resized re-save, unrelated photo), and the duplicate finder UI flow.

## [0.6.0] - 2026-10-22

### Added
- Search: instant, case-insensitive search across every sealed file name, with filters for photos, videos, audio and documents. It runs inside the encrypted database on the phone, and characters like `%` and `_` are matched literally. Results open in the right viewer or player.
- Albums: create albums with a name and colour, then rename or delete them (deleting an album never deletes its files). Each album card shows its newest photo or video as the cover, plus the item count and total size. Add or remove a photo or video from the viewer's new Album button, or create a new album right there.
- Security screen, now fully working:
  - Turn fingerprint unlock on (with a fingerprint check) or off.
  - Set, change or remove the 6-digit PIN.
  - Choose the auto-lock delay after leaving the app: instantly, 30 s, 1 min or 5 min. The vault still locks the moment the screen turns off.
  - Change the passphrase. The current passphrase is required, and wrong guesses count toward the same lockout as unlocking. Files aren't re-encrypted, and the recovery key, PIN and fingerprint keep working.
  - Lock now, a vault health score, and an About section describing the encryption.

### Changed
- The encrypted database moves to version 2 (adds albums). Existing vaults upgrade automatically on first unlock, with nothing lost.

### Tests
- 65 unit tests and 39 on-device tests, including upgrading a real version-1 database, passphrase change rules, album counts and covers, search escaping, and UI flows for search, albums and Security.

## [0.5.0] - 2026-10-22

### Added
- Video player: plays sealed videos straight from the vault through a decrypting data source. Seeking decrypts only the 1 MiB segments needed, in memory. Includes play/pause, a seek bar, ±10 seconds, speeds from 0.5× to 2×, and an "Up next" list. The video surface is marked secure, so frames can't be captured.
- Audio player: every sealed audio file in one queue, with cover art when the file has it, a seek bar, previous/next, repeat-one and speed.
- Pressing Play after a track or video has finished starts it again from the beginning.
- Documents: list sortable by date, name or size, with coloured type badges.
- PDF viewer: the PDF is decrypted into an anonymous in-memory file (never to storage) and pages are rendered as you scroll. Needs Android 11 or newer; on Android 10 the file stays sealed with a short explanation.
- Text viewer for .txt, .md, .csv, .json, .log, .xml and .yaml files (first 512 KB, selectable).
- Shred from the document viewer.
- Tapping a video in the gallery or the photo viewer opens the player. Home's Audio and Documents tiles now open their screens.

### Changed
- Playback pauses whenever the app leaves the screen and the player is released on lock, so media never keeps a vault open in the background.

### Tests
- 61 unit tests and 31 on-device tests, including playback of a sealed H.264 video (with seeking), a sealed WAV, Play restarting a finished track, rendering every page of a sealed PDF, and ranged reads through the decrypting data source.

## [0.4.0] - 2026-10-22

### Added
- Gallery of photos and videos:
  - Grouped by month by default, with each month's item count and total size, and a floating month label while scrolling.
  - Sort by date, size or type, either direction. Size and type sorts show a badge on each tile.
  - 3, 4 or 5 columns, and filters for All, Photos, Videos, Screenshots and Favourites. Settings are remembered.
- Photo viewer: swipe between items, pinch or double-tap to zoom, info panel (date taken, size, dimensions, length, date sealed, encryption), favourite and shred.
- Reel mode: full-screen vertical slideshow that advances every 3, 5 or 10 seconds, with pause, previous/next, double-tap to favourite (with a heart burst) and a slow zoom on stills.
- Encrypted thumbnails: generated once from the sealed file (photos honour their rotation; videos use a frame decrypted on the fly), stored encrypted with the file's own key, and cached only in memory. The cache is cleared when the vault locks.
- Shredding a file removes its index row (and with it the key), its encrypted data and its thumbnail.
- Home's Videos tile opens the gallery filtered to videos.
- Every gallery tile has a spoken label for screen readers.

### Changed
- The APK only includes 64-bit native code (arm64 phones and x86_64 emulators), which roughly halves its size. 32-bit-only phones are no longer supported.

### Fixed
- **The encrypted database could fail with "file is not a database" when several reads ran at the same time** (for example the home totals during an import). The database key was wiped right after opening, while SQLCipher still needed it for extra connections. It now stays in memory exactly as long as the database is open and is wiped on lock. This affected 0.2.0 and 0.3.0; please update.

### Tests
- 61 unit tests and 25 on-device tests, including the gallery UI flow, reel timing on a controlled clock (advance, wrap-around, pause, resume), parallel database reads, encrypted thumbnails, and a video thumbnail decoded from a real sealed MP4.

## [0.3.0] - 2026-10-22

### Added
- Import screen with three sources, all through Android's own picker (no storage permission needed):
  - Photos & videos.
  - Any files (documents, audio, archives…).
  - A whole folder or USB drive, including every subfolder.
- Background import with a live progress card (percent, files, bytes) and a progress notification. The notification never shows file names and is hidden on the lock screen.
- The vault stays open while an import runs, even if you leave the app or the screen turns off, and locks as soon as the import finishes. A Cancel button is on the screen and in the notification.
- The vault also stays open while you browse the system file picker, so taking longer than the 30-second auto-lock to find a folder no longer cancels the import.
- Skip duplicates (on by default): files whose contents are already in the vault are not sealed again.
- Remove location from photos (on by default): GPS data is stripped from JPEG photos while they stream into the vault, and XMP metadata (which can repeat the location) is dropped. Date taken, camera and orientation are kept. Nothing is written to disk unencrypted.
- Date taken, dimensions and duration are read from EXIF and video metadata, so the gallery can group by month.
- After an import, "Remove originals" deletes the imported files from their original location after you confirm. Files whose location doesn't allow deleting are reported.
- Tests: 52 unit tests (including the location scrubber) and 17 on-device tests (real EXIF GPS removal, duplicates, cancelling, the vault staying open during import).

### Changed
- Auto-lock can be held open by long-running work and locks right after it ends.

## [0.2.0] - 2026-10-22

### Added
- Vault setup flow:
  - Passphrase screen with a live strength meter, estimated crack time, a 7-word generator and a repeat field. A vault can only be created with a "Good" or stronger passphrase.
  - Recovery key screen (8 groups of 4) with show/hide and a "written down" confirmation.
  - Optional 6-digit PIN for everyday unlocking.
  - Optional fingerprint unlock on phones with a strong (class 3) sensor.
- Unlock screens: PIN pad, automatic fingerprint prompt, passphrase unlock and recovery-key unlock.
- Lockout after wrong attempts: 4 free tries, then waits of 1, 5, 15 and 60 minutes. The timer uses the phone's boot clock, so changing the time doesn't skip it, and a reboot restarts the current wait.
- Auto-lock: immediately when the screen turns off, and 30 seconds after leaving the app.
- Encrypted index database (SQLCipher) holding file names, dates and per-file keys, keyed from the master key.
- Sealed file store: contents are encrypted into a temporary file and only moved into place once indexed, with a SHA-256 fingerprint recorded for duplicate detection later.
- The PIN copy of the master key is wrapped by a non-exportable Android Keystore key, so a copied PIN file can't be brute-forced on another device.
- Home screen shows real totals (size, files per type) from the vault.
- Tests: 46 unit tests and 11 on-device tests, including full setup/unlock UI flows, an on-disk check that the database is unreadable, and auto-lock on screen-off and backgrounding.

### Changed
- The Home lock button now locks the vault and returns to the lock screen.
- Removed the debug-only "preview app UI" shortcut; the app now opens on Welcome, Unlock or Home depending on the vault's state.
- A recovery key with a typo (caught by its checksum) no longer counts as a failed attempt.

### Fixed
- The welcome card showed a hard-coded "V0.1"; it now shows the installed version.

## [0.1.0] - 2026-10-22

### Added
- Project skeleton: Kotlin, Jetpack Compose, Material 3, Hilt, Navigation Compose, minSdk 29, targetSdk 35.
- Prism design system: black ground, bold block colours, display and mono type styles.
- Welcome screen with the Bauhaus artwork card, and the bento Home screen with storage card and category tiles.
- Floating bottom bar (Home, Search, Import, Albums, Security) and placeholder screens for later phases.
- `crypto` module:
  - Argon2id passphrase key derivation with tunable cost settings stored in the vault header.
  - Random 256-bit master key wrapped with AES-256-GCM, by the passphrase and by a recovery key.
  - Recovery key shown as 8 groups of 4 Crockford base32 characters with a typo-catching checksum.
  - Per-file keys and Tink AES-256-GCM-HKDF streaming encryption in 1 MiB segments, with random-access decryption for media seeking.
  - Passphrase change without re-encrypting files.
  - Android Keystore biometric key wrapper (StrongBox when available) for fingerprint unlock.
  - 26 unit tests covering round trips, seeking, tampering, truncation, wrong secrets and RFC 5869 HKDF vectors.
- Offline and privacy hardening: network permissions stripped from the manifest and checked on every build, Google backup and device transfer disabled, screenshots and the recents preview blocked.
- GitHub Actions: CI build and tests on every push, and signed releases that attach `Obscura_<version>.apk`.

[Unreleased]: https://github.com/shahabcodes/Obscura/compare/v0.12.0...HEAD
[0.12.0]: https://github.com/shahabcodes/Obscura/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/shahabcodes/Obscura/compare/v0.10.1...v0.11.0
[0.10.1]: https://github.com/shahabcodes/Obscura/compare/v0.10.0...v0.10.1
[0.10.0]: https://github.com/shahabcodes/Obscura/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/shahabcodes/Obscura/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/shahabcodes/Obscura/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/shahabcodes/Obscura/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/shahabcodes/Obscura/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/shahabcodes/Obscura/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/shahabcodes/Obscura/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/shahabcodes/Obscura/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/shahabcodes/Obscura/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/shahabcodes/Obscura/releases/tag/v0.1.0
