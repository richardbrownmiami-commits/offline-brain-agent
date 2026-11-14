# Offline Brain Agent — Round 3

Version **1.0.2 (versionCode 3)** is a single-activity Kotlin/Jetpack Compose app. Chat routing is deterministic and on-device; the app does not use an LLM or cloud AI to answer live chats. Weather, RSS, currency, Wikipedia and user-selected API lookups use their configured endpoints only when explicitly requested, except for the separate optional periodic refresh jobs that the user can enable.

## Included in Round 3

- A chat input bar anchored above keyboard and navigation insets, with quick actions at the top.
- Android speech recognition as the default mic path; optional advanced Vosk mode.
- First-run self-check and clearer OCR/screenshot diagnostics, including local log share/copy/view paths.
- User-authored fixed phrase rules, command macro recording/playback, notification filters/replies/read-aloud, foreground-only clipboard history, app lock, encrypted backup/restore/wipe, and an opt-in floating overlay.
- Opt-in background refresh and encrypted backup work with WorkManager constraints and retry/backoff.

Notification access, Accessibility, overlay permission, clipboard history, PIN lock and background jobs remain user-controlled. The assistant never grants Android permissions for you. Read [REPORT.md](REPORT.md) before testing backup/wipe or optional permissions.

## Build and compatibility

- Verified with OpenJDK 21; source/target bytecode is Java 17.
- Android SDK platform / Build Tools: API 36 / 36.0.0; AGP 9.5.0; Gradle wrapper 9.9.1.
- `minSdk 30`, `targetSdk 30`, `compileSdk 36`; native packaging is **armeabi-v7a only** (32-bit ARM).
- Build and test with:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
```

The release APK uses the local Android debug signing certificate. It is suitable for sideload testing, not for Play Store publication. Debug has the `.debug` application ID suffix.

- Release: `app/build/outputs/apk/release/app-release.apk`
- Debug: `app/build/outputs/apk/debug/app-debug.apk`

## Privacy and storage

Chat routing is local. The Room database is encrypted with SQLCipher. New secrets use Android Keystore-backed encryption; portable backups use authenticated AES-GCM encryption. Notification capture, Accessibility, clipboard history, overlay, lock, periodic refresh and scheduled backups are opt-in. The optional Vosk model is not bundled or downloaded by the default speech path. Diagnostic logs avoid chat content and credentials.

## Test status

The Round 3 Gradle build passed **21 host unit tests** and assembled debug, release and instrumented-test APKs. The instrumented chat composer test compiled, but was not executed because this build environment has no phone, emulator or `adb`. The Redmi M2006C3MII (Android 11/MT6765) still needs the device checks in [REPORT.md](REPORT.md), especially chat/keyboard layout, alarm delivery, MIUI background settings, notification access, Accessibility, biometric prompt, overlay and backup/restore.

See [REPORT.md](REPORT.md) for exact test steps, APK hashes, limits and estimates. See [RESEARCH_LOG.md](RESEARCH_LOG.md) for stable library pins, official documentation and Android 11/MIUI/ARMv7 issue checks.
