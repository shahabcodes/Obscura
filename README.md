# Obscura

An offline, encrypted vault for photos, videos, audio and documents on Android.

- **No network access.** The app has no `INTERNET` permission; every build fails if one sneaks in.
- **Encrypted on the device.** Files are sealed with AES-256-GCM streaming encryption before they are stored.
- **Invisible to other apps.** Files live in app-private storage and are decrypted in memory only.
- **Encrypted backups.** The vault can be copied, still encrypted, to a USB drive or folder and restored on a new phone with the passphrase or recovery key.
- **Recovery key you can keep.** Save it as an AES-256 password-protected PDF to print, or create a new one in Security.
- **Get files back out, on your terms.** Save decrypted copies or share once through links that die when the vault locks, one file or a multi-selection at a time.

## Build

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew :crypto:testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

On-device tests (real SQLCipher, Keystore and full UI flows) need an emulator or phone:

```bash
./gradlew connectedDebugAndroidTest
```

## Releases

1. Bump `obscura.versionName` and `obscura.versionCode` in `gradle.properties`.
2. Add a section for the new version to `CHANGELOG.md`.
3. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

The **Release** workflow tests, signs and publishes `Obscura_<version>.apk` with the changelog
section as release notes. It needs four repository secrets: `OBSCURA_KEYSTORE_BASE64`,
`OBSCURA_KEYSTORE_PASSWORD`, `OBSCURA_KEY_ALIAS` and `OBSCURA_KEY_PASSWORD`.

### Signing a release build locally

No key, password, or path to one lives in this repository. The build reads them from the clone's
own git config, which is kept in `.git/config` and is never committed:

```sh
git config obscura.signing.keystore      /path/to/obscura-release.jks
git config obscura.signing.storePassword '…'
git config obscura.signing.keyAlias      '…'
git config obscura.signing.keyPassword   '…'
```

`./gradlew assembleRelease` then signs the APK. Without them the release build is left unsigned,
which is what happens on anyone else's clone. The matching `OBSCURA_*` environment variables are
read as a fallback, which is how the workflow signs with its secrets. Keep the keystore itself
outside the working tree; `*.jks` and `*.keystore` are ignored in any case.

## Project layout

| Module | Contents |
|---|---|
| `app` | Compose UI, navigation, Prism theme |
| `crypto` | Key derivation, vault header, file encryption, Android Keystore wrapper |
