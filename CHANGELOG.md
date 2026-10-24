# Changelog

All notable changes to Obscura are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). Every release has a section whose heading
matches `obscura.versionName` in `gradle.properties`; the release workflow publishes that
section as the GitHub release notes.

## [Unreleased]

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

[Unreleased]: https://github.com/shahabcodes/Obscura/compare/v0.10.0...HEAD
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
