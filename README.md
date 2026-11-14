# Offline Brain Agent

**Version 1.0.5** is an Android personal assistant built with Kotlin and Jetpack Compose. Its command routing is deterministic and local; it does not use an LLM or cloud AI to answer chat. Optional network lookups use configured services only when requested, apart from separately enabled refresh jobs.

## Project notes

- Android API 30 minimum/target; compile SDK 36; native packaging targets `armeabi-v7a`.
- User-controlled features include speech input, notifications, Accessibility, clipboard history, overlay, app lock, periodic refresh and backups. Android permissions are granted by the user, not by the app.
- The optional Vosk speech model is `vosk-model-small-en-us-0.15`. Its bundled archive SHA-256 is `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
- Release APKs use a local Android debug signing certificate and are for sideload testing, not Play Store publication.

## Build

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
```

Build output paths are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`. Build success does not establish runtime compatibility on any particular device. Device-specific behavior must be tested on the target hardware; this documentation makes no new device-test claims.

## Documentation

- [Changelog](CHANGELOG.md)
- [Research archive](docs/research/)

For older release test procedures and evidence, consult archived research where applicable; prior reports are not current release validation.
