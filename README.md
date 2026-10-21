# Obscura

An offline, encrypted vault for photos, videos, audio and documents on Android.

- **No network access.** The app has no `INTERNET` permission; every build fails if one sneaks in.
- **Encrypted on the device.** Files are sealed with AES-256-GCM streaming encryption before they are stored.
- **Invisible to other apps.** Files live in app-private storage and are decrypted in memory only.

## Build

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew :crypto:testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Releases

1. Bump `obscura.versionName` and `obscura.versionCode` in `gradle.properties`.
2. Add a section for the new version to `CHANGELOG.md`.
3. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

The **Release** workflow tests, signs and publishes `Obscura_<version>.apk` with the changelog
section as release notes. It needs four repository secrets: `OBSCURA_KEYSTORE_BASE64`,
`OBSCURA_KEYSTORE_PASSWORD`, `OBSCURA_KEY_ALIAS` and `OBSCURA_KEY_PASSWORD`.

## Project layout

| Module | Contents |
|---|---|
| `app` | Compose UI, navigation, Prism theme |
| `crypto` | Key derivation, vault header, file encryption, Android Keystore wrapper |
