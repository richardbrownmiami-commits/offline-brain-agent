# Build Report

## Summary: overall status in 3 lines
The Offline Brain Agent core is built in Kotlin/Compose with local rule-based routing and no LLM or cloud-AI response service.  
Signed debug and minified release APKs were clean-built for **armeabi-v7a only**, with `minSdk 30` and `targetSdk 30`.  
All 9 host unit tests passed and both APK signatures/package contents were verified; no phone or emulator was available for runtime tests.

## Stage table: Stage | Status (DONE / PARTIAL / FAILED) | Tested how | Notes

| Stage | Status (DONE / PARTIAL / FAILED) | Tested how | Notes |
|---|---|---|---|
| 1. Skeleton | DONE | Clean Gradle build; unit-test task; APK metadata/signatures checked | Compose chat/settings/navigation, MVVM/StateFlow, encrypted Room, and GitHub Actions workflow are present. |
| 2. Intent engine | PARTIAL | 4 JUnit tests; compilation | Local tokenization, fuzzy/regex/TF-IDF scoring, slots, FAQ FTS and custom phrases are present. No short-context resolution across turns. |
| 3. Alarms and reminders | PARTIAL | 3 cron-parser JUnit tests; compilation | Room, five-field cron, exact AlarmManager scheduling, reboot/package-replaced receivers, foreground announcement, sound, TTS and five-minute snooze are implemented. No real-device alarm/reboot test; alarm options are limited. |
| 4. Online skills | PARTIAL | Compilation only | Open-Meteo/geocoding, RSS, Wikipedia, currency and a user-configured API connector are implemented. No network integration tests or scheduled API/news refresh. |
| 5. Accessibility control | PARTIAL | Compilation only | Open-app, UI text/ID, coordinate gesture, scroll/navigation and text-entry paths are present. No external-app/device exercise. |
| 6. OCR screen reading | PARTIAL | Compilation only | Android screenshot API plus bundled ML Kit Latin OCR and word-position tapping are implemented. No device accuracy test; screenshot failure callbacks do not surface a specific explanation, so secure-screen reporting is unverified. |
| 7. PDF/web summaries | PARTIAL | Compilation only | Text-PDF and web text extraction with extractive summaries are present. Scanned-PDF rendering/OCR and a real-document test are not implemented. |
| 8. Notifications | PARTIAL | Compilation only | Opt-in notification listener, encrypted storage, search and 30-day cleanup are present. No device capture test, per-app filters, inline reply or read-aloud. |
| 9. Automations | PARTIAL | Compilation only | Local notes/to-dos are present. Rule automation, macro recording/replay and clipboard history are not implemented. |
| 10. Voice, polish, security | PARTIAL | Compilation and APK inspection | Dark Compose UI, persisted settings, SQLCipher storage, permission links and a manually authored startup profile are present. Vosk mic/model flow, chat TTS mode, app lock, backup/export/wipe and floating overlay are not implemented. |

## Failures: each with the exact error message or log excerpt, the cause, what you tried, and whether it is fixed

- Room KSP first reported: `The columns returned by the query does not have the fields [rowId] in com.cue.offlinebrain.data.FaqEntity ... Columns returned by the query: [question,answer,phrases]`. The FTS query omitted the row ID required to map the entity; it was changed to select `rowid`. **Fixed; later KSP and builds passed.**
- Kotlin parsing reported: `BrainViewModel.kt:52:22 Expecting an expression`. The cancellation branch used an invalid bare rethrow; it was changed to handle the cancellation exception explicitly and save the Stop status independently. **Fixed.**
- Intent-engine compilation reported `IntentEngine.kt:4:18 Unresolved reference 'Calendar'.` and `Overload resolution ambiguity between candidates` for `sumOf`; the Calendar import was corrected to `java.util.Calendar` and the count now uses `count`. The initial ambiguity then disappeared. **Fixed.**
- Timber compilation reported `OfflineBrainApplication.kt:10:12 Unresolved reference 'jakewharton'` and unresolved `Timber`. The import was corrected to the artifact's `timber.log.Timber` package. **Fixed.**
- Android Gradle Plugin first reported `jlink executable /usr/lib/jvm/java-21-openjdk-amd64/bin/jlink does not exist.` The environment had a runtime-only Java package; a full Java 21 JDK was installed. An old Gradle daemon then reported `Failed to exec spawn helper: pid: 4714, exit code: 1, error: 0 (none)` after the JDK upgrade; stopping Gradle daemons and starting a fresh one fixed it. **Fixed.**
- Release profile processing reported `Error parsing baseline-prof.txt ... Class rules don't support flags, but 'HSP' were specified`. The flags were moved to method rules and the Kotlin companion method descriptor corrected. **Fixed; profile expansion and release assembly passed.**
- Release lint reported `Google Play requires that apps target API level 33 or higher. [ExpiredTargetSdkVersion]`. This project explicitly requires API 30 for Android 11 sideloading, so that one lint check was disabled; `minSdk` and `targetSdk` remain 30. **Fixed for the requested sideload build; this is not a Play Store release.**
- Nonfatal build warnings: SDK tooling said it understands XML through version 3 but encountered version 4; native stripping said `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libmlkit_google_ocr_pipeline.so`, `libsqlcipher.so` and `libvosk.so` would be packaged as-is. The build continued successfully. **Warnings remain; the listed native libraries are included.**
- Final verification: `./gradlew clean testDebugUnitTest assembleDebug assembleRelease` completed with `BUILD SUCCESSFUL`; test reports show 9 tests, 0 failures and 0 errors.

## Background mechanism per feature: Alarm, Work or Service, and whether it survived a reboot and a force-stop test

- **Alarm:** `AlarmManager.setExactAndAllowWhileIdle` is used for exact alarms. The database stores one-shot and cron alarms; recurring alarms are re-armed after firing. Receivers are registered for boot and package replacement. A foreground `AlarmAnnouncementService` plays the alarm notification sound and speaks the label; snooze creates another alarm for five minutes later.
- **Work:** One unique periodic WorkManager job runs every 15 minutes to check and re-arm enabled future alarms and prune notifications older than 30 days. It does not poll RSS/weather/custom APIs or download Vosk. No network/battery constraints or custom retry backoff are configured for this local audit job; exceptions return WorkManager `retry()`.
- **Service:** The foreground service is used for active alarm announcements. The notification listener is an Android `NotificationListenerService`, enabled separately by the user; no macro service is present.
- **Reboot/force-stop:** **UNTESTED.** `adb devices` showed no connected phone or emulator. The boot/package-replaced receivers and periodic worker are in the build, but their behavior was not exercised on the target Redmi. Do not assume Android will run app work while the app is force-stopped; test after installing on the device.

## Untested or risky items: what needs a real device (accessibility, screenshot, notification listener, exact alarms, floating Compose overlay, Vosk speed on MT6765)

No Android 11 phone or emulator was attached, so installation, launch, alarms, accessibility actions, screenshot/OCR, notification capture, battery behavior, reboot, force-stop and performance remain untested. In particular, test the screen-coordinate alignment of OCR taps, behavior on `FLAG_SECURE` screens, Android's notification-access binding, and recurring alarm timing on the MT6765 device. The floating overlay and microphone/Vosk flow are not implemented, so Vosk speed cannot yet be measured. A TTS engine/voice must be available on the phone for spoken alarms.

## Performance: cold start time, scroll frame drops, memory use

Cold start, chat-scroll frame drops and memory use are **UNMEASURED** because no device/emulator was available. Release R8 minification and resource shrinking completed. Final artifact sizes: debug **100,547,592 bytes (100.55 MB / 95.89 MiB)**; release **31,080,725 bytes (31.08 MB / 29.64 MiB)**. Both contain five native libraries for `armeabi-v7a` only. A small manually authored startup Baseline Profile is included and processed, but it was not generated from a benchmark or measured on the target.

## Decisions and deviations: anything changed from this plan and why

- Following the latest instruction, both variants package **only `armeabi-v7a`**; `arm64-v8a` is excluded. SDK targets remain `minSdk 30` / `targetSdk 30`; compile SDK 35 is used for the build environment.
- The expired-target lint check is disabled only because the requested API 30 target is for Android 11 sideloading. The release build is not intended for Google Play.
- The release APK is signed with the local Android debug certificate for installation/testing, not production publishing. Debug uses package `com.cue.offlinebrain.debug`; release uses `com.cue.offlinebrain`.
- A small custom five-field cron parser was used instead of `cron-utils` and has three unit tests.
- Simple app-level lazy singletons wire the database/settings/repository; Koin/Hilt was not added.
- Vosk's Android native library is packaged, but no speech model download, mic capture or speech-to-text flow is wired. This was left incomplete rather than claiming voice support.
- The repository and GitHub Actions workflow are prepared locally; nothing was published to GitHub.

## Known limitations

This is a deterministic command router, not a general conversational AI. The offline FAQ and saved chat do not provide cross-turn contextual reasoning. Public skills require internet and are user-triggered; scheduled news/weather refresh is absent. The weather route reports the implemented current conditions rather than a full configurable forecast. Alarm sound, announcement text and snooze duration are not editable; snooze is fixed at five minutes. Notification retention is fixed at 30 days, with no allow/block filters, inline replies or read-aloud. Scanned PDFs are not OCRed. No Vosk microphone flow, voice-mode replies, app lock, backup/restore, export/wipe, floating overlay, macro recorder or rule-based notification automation is available. The debug/release variants are both debug-certificate signed; a production signing key and Play policy target are not configured.

## Suggestions: ranked improvements for the next round

1. Install the arm32 release on the Redmi and run a real-device checklist for launch, alarms, reboot/force-stop, notification access, accessibility and OCR taps; record cold start, memory and scroll frame timing.
2. Complete the user-started Vosk model download/microphone flow and voice-mode TTS, then measure speech latency and memory on the MT6765.
3. Add scanned-PDF page rendering plus offline OCR, then test summaries and query-focused document search on sample PDFs.
4. Add notification app filters and editable retention, plus safe inline reply/read-aloud actions.
5. Implement the floating overlay and user-confirmed macro/automation tools with clear start/stop controls and permission tests.
6. Add app lock, encrypted backup/export/wipe, and editable alarm/voice settings.
7. Add cross-turn local context and more held-out tests; consider Koin/Hilt only if dependency wiring grows.
8. Generate a Baseline Profile with a benchmark module and measure cold start and frame behavior on the actual target.

## Manual steps for the user: permissions to enable, battery settings, how to install

1. Install `app-release.apk` for package `com.cue.offlinebrain` (recommended) or `app-debug.apk` for the separate debug package. If Android blocks the installer, allow “Install unknown apps” for the file manager/browser that opened the APK, then install.
2. For Android controls, open the app's Permissions checklist and enable its service under Android **Accessibility**. For notification search, separately enable **Notification access**. Grant only the feature permissions you intend to use.
3. For reliable alarms, review the app's **Battery settings** link and allow unrestricted/background operation if Android offers that option. No exemption was automatically granted. Allow notifications/sound and make sure a TTS voice is installed for spoken alarms.
4. Internet is needed for weather, RSS, Wikipedia, currency and user-configured API calls. Local routing, notes, stored chat and alarms do not use an LLM/API. Microphone/Vosk chat is not available in this build.
