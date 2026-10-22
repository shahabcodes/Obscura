# Changelog

All notable changes to Obscura are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). Every release has a section whose heading
matches `obscura.versionName` in `gradle.properties`; the release workflow publishes that
section as the GitHub release notes.

## [Unreleased]

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

[Unreleased]: https://github.com/shahabcodes/Obscura/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/shahabcodes/Obscura/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/shahabcodes/Obscura/releases/tag/v0.1.0
