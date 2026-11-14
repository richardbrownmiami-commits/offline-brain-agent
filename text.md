# Offline Brain Agent 1.0.5 — Round 7 report

## Result

The **1.0.5 release (versionCode 6)** built successfully for package `com.cue.offlinebrain`, with `minSdk 30`, `targetSdk 30`, `compileSdk 36`, and **armeabi-v7a only**. The full `check assembleDebug assembleRelease assembleAndroidTest` build passed. Host tests: **154 passed, 0 failed, 0 errors, 0 skipped**. Instrumentation tests compiled and an Android test APK was assembled, but neither was run on a device or emulator.

The release APK is **31.78 MiB (33.32 MB)**. SHA-256: `deff500f3dca8118888da6342dd50ff5caad9fccd4bc71f7fcd1d7b2d04a5156`. APK Signature Scheme v2 verification passed. The signer certificate SHA-256 is `22549cb69ae26443e891cb9c85b9f7587c8de903856a3d39e14237d2d5402db9`.

**Important: one-time backup-first install change.** Round 6’s 1.0.4 APK used Android’s debug certificate (`7271d4c24d4b54ffb71731dc922108e783adf3d4d73cd45bd9b92abb6837daa9`). Round 7 has the requested dedicated persistent release key, so Android will not install it over that older copy. Before uninstalling 1.0.4, export an encrypted backup, save it outside the app (for example in Downloads or on a computer), and verify that it can be previewed. Then uninstall the old app once, install 1.0.5, restore the backup, and re-enable permissions. Future updates must use the same new key; keep a separate protected backup of it. Its files are outside the source tree under `/home/ubuntu/.cue/keys/`; passwords are not included here. See Android’s [app-signing guidance](https://developer.android.com/studio/publish/app-signing) and [`apksigner` documentation](https://developer.android.com/tools/apksigner).

## Round 6 to Round 7

| Area | Earlier issue or evidence | Round 7 change and proof |
|---|---|---|
| Microphone | The owner reported a Google-style “try again” popup, then a message that another voice service was running. The exact phone-level cause was not proven. | Chat and Diagnostics now share one speech-session owner. The app no longer launches `RecognizerIntent`; busy/client errors get one bounded cancel/destroy/recreate retry, followed by Auto-to-Vosk fallback. Audio focus is requested and released. “Say hello” reports engine, error, recognized text, and recent mic events. Code/build and host tests pass; Redmi behavior is **UNTESTED**. Android’s installed speech provider may process audio remotely; only Vosk is definitely on-device. See the [SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer), [Android 11 package-visibility guidance](https://developer.android.com/about/versions/11/behavior-changes-11), and [Vosk Android](https://alphacephei.com/vosk/android). |
| Device status | The owner previously reported status information worked but asked for a visual card. | Tools now has a refreshable card for Storage, RAM, Battery, Network, and Media volume, with icons, progress where meaningful, settings shortcuts, loading/error states, and a low-battery note below 15%. Its Compose acceptance test compiled; it was not run on-device. |
| Accessibility and MIUI | Android overlay permission does not cover all MIUI pop-up/background permissions. | Chat and Tools show an Accessibility-off banner for Cue’s exact service and refresh on Activity resume. Permissions offers a best-effort `miui.intent.action.APP_PERM_EDITOR` shortcut, App info fallback, and manual steps for MIUI “Other permissions,” pop-up, lock-screen, and background access. Xiaomi’s [MIUI compatibility notes](https://dev.mi.com/console/doc/detail?pId=941) are general, not proof for this phone. The shortcut and screen labels are **UNTESTED** on MIUI 12.5. |
| Notification cards | Notification answers were largely text-based. The owner reported capture and categories working in Round 6; that is not a Round 7 regression pass. | Saved items now use cards with app identity, relative time, unread state, and available actions: Open app, Reply only when a live RemoteInput exists, Read aloud, Copy, Mark read, and move to category. Calls and SMS keep body text hidden until “Show text.” Mark read changes Cue’s saved item; it does not dismiss the system notification. Chat names are shown when available. |
| Notification questions | Keyword heuristics were brittle across verbs and synonyms. | A local typed parser handles show/read/tell/list/what-in/any/open, category synonyms, unread/new, today, `from <app>`, and `what did <contact> send`. Ambiguous wording asks for clarification; unknown wording is rejected locally. A shared corpus of **52 distinct positive phrases** runs in host tests and the on-device diagnostic. The [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [StatusBarNotification](https://developer.android.com/reference/android/service/notification/StatusBarNotification), and [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput) docs informed the implementation boundaries. |
| Overlay and volume | Earlier device notes said the floating button could be invisible even when Android overlay access was granted. | Floating-button setup now has a user-started “Show bubble for 10 seconds” test plus normal Start/Stop. Diagnostics checks safe bubble positioning without secretly launching an overlay. Volume parser/target math is tested and is limited to the media stream. The real bubble, audible result, and MIUI behavior are **UNTESTED**. |
| Diagnostics and logs | Synthetic checks alone cannot prove every Android callback arrives on the phone. | Diagnostics now includes the shared 52-phrase query suite, call/SMS privacy checks, overlay geometry, volume math, device-status fields, speech availability/state-machine checks, and directions for the explicit bubble test. Repeated notification skip reasons are aggregated by package/minute/reason; logs rotate at about 512 KB and keep one prior file. |

Round 6’s other behavior—local notification pipeline, small-talk and document/resume handling, typed macros, encrypted restore protections, alarms, and existing offline-first command routing—was retained. Round 6 had 121 passing host tests; Round 7’s full host suite now has 154. The owner’s earlier device notes said notifications/categories, device status, alarms, and browser search worked, while the microphone popup and invisible overlay remained problems. Those are starting observations, not Round 7 phone tests.

## Build, lint, and package contents

The final command was `./gradlew check assembleDebug assembleRelease assembleAndroidTest --no-daemon --stacktrace`. It completed successfully; 143 tasks were actionable (37 executed, 106 up-to-date). Lint completed without errors and reported **87 warnings and 11 hints**, including dependency suggestions, deprecated API notes, and existing platform/library warnings. No library or SDK version was upgraded for this round; project pins were retained: Build Tools 36.0.0, AGP 9.5.0, Gradle 9.9.1, Kotlin Compose plugin 2.4.10, KSP 2.3.12, Java source/target 17, Compose BOM 2026.06.01, Material 3 1.4.0, Lifecycle 2.8.7, Room 2.8.5, DataStore 1.1.1, WorkManager 2.12.0, SQLCipher 4.5.4, coroutines 1.9.0, OkHttp 5.4.0, PdfBox-Android 2.0.27.0, ML Kit Text Recognition 16.0.1, and Vosk Android 0.3.75.

Only `armeabi-v7a` native libraries are packaged:

```text
lib/armeabi-v7a/libandroidx.graphics.path.so
lib/armeabi-v7a/libdatastore_shared_counter.so
lib/armeabi-v7a/libjnidispatch.so
lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so
lib/armeabi-v7a/libsqlcipher.so
lib/armeabi-v7a/libvosk.so
```

No arm64-v8a, x86, or x86_64 native library is included. R8 minification and resource shrinking are enabled. The mapping for this exact APK is saved locally as `dist/Offline-Brain-Agent-1.0.5-release-mapping.txt.gz`, SHA-256 `3fd7874c5db4c5cca6fae5a558282212ded1348a42686188d8922a478c33259a`; it is not a separate delivery item.

## Still UNTESTED on the Redmi

`adb devices -l` found no attached phone or emulator. No Round 7 claim is made for real speech, the popup disappearing on the Redmi, Vosk fallback/quality, real notification callback delivery or replies, Accessibility refresh after returning from Settings, MIUI permission deep link, overlay attach/drag/stop, audible volume, or exact-screen visual layout. The Android instrumentation tests were built but not run.

Measured here: APK size, signer, ABI contents, host tests, build, and lint. Not measured: startup time, memory/PSS, Vosk peak RAM, battery/thermal behavior, frame rate, command latency, or audible volume. No physical-device Macrobenchmark or Baseline Profile measurement was run; see Android’s [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview). The reported Redmi M2006C3MII / Android 11 / MIUI V12.5.6.0 / 3.7 GB RAM / 2% battery details are owner-provided, not independently measured in this build session.

## Redmi verification order

1. Export an encrypted backup from 1.0.4, save it outside the app, and verify it can be previewed **before uninstalling**.
2. Uninstall 1.0.4 once, install 1.0.5, restore the backup, and check several saved items.
3. Re-enable Android permissions. In Permissions, try the MIUI shortcut; if it opens the wrong page, use App info → Other permissions. Separately check the available pop-up, lock-screen, battery, and autostart switches.
4. In Chat and Diagnostics, tap “Say hello.” Confirm no app-launched Google popup, inspect the engine/error/text, and repeat start/stop. Test the offline fallback; do not assume the Android provider is offline.
5. In Tools, refresh the status card. Check all five values, the battery note, row shortcuts, and readability at the phone’s display size.
6. Generate a harmless test alert. Confirm it is saved in the right category; try unread, today, and `from <app>` queries. Confirm SMS/call text stays hidden until requested and test Reply only with an app exposing a reply action.
7. Tap “Show bubble for 10 seconds,” then test normal Start/Stop, dragging, and returning from another app.
8. Try `volume up`, `rise volume 30`, `set volume to 50 percent`, and `mute`. Check the media-stream level and verify ringer/DND did not change.

## Research decisions

The source review followed Android’s [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics), [AccessibilityManager API](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager), and [Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle) guidance. The Xiaomi MIUI editor action is undocumented as a universal API, so it is only a shortcut and manual steps remain available. Android’s notification docs were used to keep reply actions tied to a real `RemoteInput`, and its speech docs were used to avoid calling a system-provider result “offline” without proof. No LLM or cloud AI is used to parse or answer notification questions; any existing user-requested live lookups are separate. Research covered Android 11, current platform/AndroidX guidance, and primary Xiaomi sources where available; no source established a bug specific to this exact Redmi/MIUI build.
