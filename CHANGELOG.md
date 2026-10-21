# Changelog

All notable changes to Obscura are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/). Every release has a section whose heading
matches `obscura.versionName` in `gradle.properties`; the release workflow publishes that
section as the GitHub release notes.

## [Unreleased]

## [0.1.0] - 2026-10-21

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

[Unreleased]: https://github.com/shahabcodes/Obscura/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/shahabcodes/Obscura/releases/tag/v0.1.0
