# Changelog

All notable changes to Obscura are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). Every release has a section whose heading
matches `obscura.versionName` in `gradle.properties`; the release workflow publishes that
section as the GitHub release notes.

## [Unreleased]

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

[Unreleased]: https://github.com/shahabcodes/Obscura/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/shahabcodes/Obscura/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/shahabcodes/Obscura/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/shahabcodes/Obscura/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/shahabcodes/Obscura/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/shahabcodes/Obscura/releases/tag/v0.1.0
