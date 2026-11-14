# Round 5 Android research log

Research date: 2026-11-11. The API/library recommendations below were checked against official Android/Xiaomi/Kotlin docs and opened source pages. Existing stable pins retained: Android API 36 compile / API 30 min+target; Compose BOM 2026.06.01, Material 3 1.4.0, Room 2.8.5, Lifecycle 2.8.7, Coroutines 1.9.0, PdfBox-Android 2.0.27.0; AGP 9.5.0, Gradle 9.9.1, Build Tools 36.0.0, Kotlin/Compose 2.4.10, KSP 2.3.12, JDK 21. A code-only Kotlin change has no ARMv7-specific binary requirement; every native dependency still must include `armeabi-v7a`. None of the research established a known defect on the exact Redmi M2006C3MII / MIUI V12.5.6.0 combination, so real-device checks must remain UNTESTED until performed.

## BUG 1 — notification extraction and persistence

Use `NotificationListenerService` only after `onListenerConnected()`; individual notification extras are optional and style-dependent. Prefer separate guarded reads for title, text, big text, InboxStyle lines and MessagingStyle sender/body. Use `StatusBarNotification.getKey()` as record identity and a Room unique index for race-safe dedupe; the project already pins Room 2.8.5, so no library change is justified. The planned safe path caps each string, preserves Unicode boundaries and shares the live suspend save path with Diagnostics.

Sources: [NotificationListenerService API](https://developer.android.com/reference/android/service/notification/NotificationListenerService) (callback contract applies to API 30); [Notification API](https://developer.android.com/reference/android/app/Notification) (optional standard extras); [StatusBarNotification API](https://developer.android.com/reference/android/service/notification/StatusBarNotification) (`getKey()` unique record key; `getPostTime()`); [Room guide](https://developer.android.com/training/data-storage/room) (updated 2026-10-23; newer guide examples are not reason to upgrade); [Room unique index guide](https://developer.android.com/training/data-storage/room/defining-data) (updated 2026-10-23).

## BUG 2 — FileProvider log sharing

Use a narrow app-private directory, a `content://` URI and temporary read-only grants; avoid `file://`, broad roots and storage permissions. AndroidX advises a trivial `FileProvider` subclass because base-class registration is unreliable on some devices. Decision: scope the root to app-internal files, subclass the provider, keep logs bounded/redacted, and use chooser intent with read grant.

Sources: [Set up secure file sharing](https://developer.android.com/training/secure-file-sharing/setup-sharing) (updated 2025-02-10); [FileProvider API](https://developer.android.com/reference/androidx/core/content/FileProvider) (subclass reliability note); [Share a file](https://developer.android.com/training/secure-file-sharing/share-file); [Android 11 storage changes](https://developer.android.com/about/versions/11/privacy/storage) (API 30 scoped storage context). No MIUI/ARMv7-specific FileProvider defect was documented.

## BUG 3 — compound command parsing and device execution

Use a deterministic local parser into typed actions; preserve full search payload and only split conjunctions before a recognized command. Android 11 filters package queries, so resolve only launchable apps and catch missing activities instead of requesting broad visibility. Use direct `ACTION_VIEW` for web search; AccessibilityService is user-enabled UI automation, not a background shortcut. Execution must be ordered, foreground-only, bounded, and report failures per step.

Sources: [Package visibility](https://developer.android.com/training/package-visibility) / [common use cases](https://developer.android.com/training/package-visibility/use-cases) (Android 11/API 30); [Common intents](https://developer.android.com/guide/components/intents-common) (`ACTION_WEB_SEARCH`/query); [Accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service) (node/global action contracts); [Android ABIs](https://developer.android.com/ndk/guides/abis) (native-only relevance).

## BUG 4 — clipboard cancellation

Do not swallow `CancellationException`; always remove listener in `finally`/`awaitClose` or paired Compose disposal. Android 10+ limits clipboard reads to a focused app/default IME; a callback does not guarantee content is readable. Decision: cancellation propagates, cleanup is deterministic, no clipboard read occurs just to display the screen.

Sources: [Kotlin cancellation guide](https://kotlinlang.org/docs/cancellation-and-timeouts.html) (modified 2026-07-27); [Compose side-effects](https://developer.android.com/develop/ui/compose/side-effects) (`DisposableEffect` cleanup); [ClipboardManager API](https://developer.android.com/reference/android/content/ClipboardManager) (repeated callback/null behavior); [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes) (still applies on API 30). No MIUI/ARMv7 cancellation defect was found.

## BUG 5 — PDF extraction and summaries

`PdfRenderer` renders but does not extract text; SAF returns content URIs rather than stable file paths. Keep existing PdfBox-Android 2.0.27.0, bound document/page/text processing and work off main. Use conservative NFC/text cleanup, deterministic extractive summaries and an explicit scanned/no-text state; do not claim generated/semantic summary. PdfBox is a third-party port, based on PDFBox 2.0.27 and README minimum API 19, with no exact MIUI/ARMv7 claim. No verified target-device PDF defect was found.

Sources: [PdfRenderer API](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer) (API 21+, one page open at a time); [SAF guide](https://developer.android.com/training/data-storage/shared/documents-files) (API 30 URI/location restrictions); [memory management](https://developer.android.com/topic/performance/memory-management) (updated 2026-09-21; OEM LMK variation); [ICU Normalizer2](https://developer.android.com/reference/android/icu/text/Normalizer2) (API 24+); [PdfBox-Android README](https://github.com/TomRoush/PdfBox-Android) (2.0.27.0).

## BUG 6 — PIN persistence and lifecycle locking

ProcessLifecycleOwner deliberately delays `ON_STOP` to tolerate configuration changes; Android can kill cached processes without `onDestroy()`. Persist PIN verifier and lock-enabled state separately and synchronously; never persist `unlocked`. Cold starts must gate content before rendering. Use lifecycle stop as an in-memory signal and configured re-lock delay; keep expensive KDF off main. Add Lifecycle Process only at the project's existing Lifecycle 2.8.7 version if absent.

Sources: [ProcessLifecycleOwner](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner) (delayed process event); [Android process lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle) (updated 2025-02-10); [Keystore](https://developer.android.com/privacy-and-security/keystore); [security best practices](https://developer.android.com/privacy-and-security/security-best-practices); [NDK ABI guide](https://developer.android.com/ndk/guides/abis). No MIUI-specific lifecycle-lock defect was verified.

## BUG 7 — encrypted backup / restore wizard

Use `ACTION_CREATE_DOCUMENT` and `ACTION_OPEN_DOCUMENT`, pass URIs to ContentResolver streams, preview content before destructive confirmation, authenticate before replacing data, and keep passphrases out of persistent settings. The existing format is AES-GCM; Android recommends AES-GCM and marks Security Crypto helpers deprecated in stable 1.1.0. There is an anecdotal nonofficial Xamarin issue with a chooser-wrapped picker on Redmi/MIUI 12/Android 11, not evidence for native SAF; use direct Activity Result picker and test on device. Database and DataStore are separate stores, so document any remaining cross-store rollback limitation.

Sources: [SAF document guide](https://developer.android.com/training/data-storage/shared/documents-files) (API 30); [Document Provider guide](https://developer.android.com/guide/topics/providers/document-provider) (updated 2026-10-23); [Android cryptography](https://developer.android.com/privacy-and-security/cryptography) (AES-GCM, Security Crypto deprecation); [crypto risk guidance](https://developer.android.com/privacy-and-security/risks/broken-cryptographic-algorithm); [Xamarin issue #2085](https://github.com/xamarin/Essentials/issues/2085) (opened 2023-04-12, archived repo; anecdotal only).

## BUG 8 — speech-recognizer lifecycle

SpeechRecognizer methods/listener run on main; set listener before start, destroy after session, and wait for results/error after stop before starting again. Partial results can be ignored; keep them provisional and handle empty finals. `EXTRA_PREFER_OFFLINE` may be ignored; API 30 lacks API-31 on-device-only recognizer methods. Decision: explicit state machine, bounded watchdog, callback generation guard and error-specific retry/manual-entry UX; no offline guarantee.

Sources: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) (API 30 package visibility declaration); [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent) (partial/offline extras optional); [RecognitionListener](https://developer.android.com/reference/android/speech/RecognitionListener) (zero/multiple partial callbacks); [Android 11 behavior changes](https://developer.android.com/about/versions/11/behavior-changes-11); [Android 11 permissions](https://developer.android.com/about/versions/11/privacy/permissions) (updated 2026-10-28). No device/ABI-specific recognizer issue was found.

## NEW 9 — chat copy/share

Compose text is nonselectable by default; wrap message text with `SelectionContainer`, exclude controls with `DisableSelection`, use write-only clipboard operations, and share through Android Sharesheet `ACTION_SEND`/`EXTRA_TEXT`. Keep whole-conversation order deterministic. No clipboard reads on screen open. Flutter MIUI clipboard alerts are not proof of Compose behavior.

Sources: [Compose text interaction](https://developer.android.com/develop/ui/compose/text/user-interactions); [Compose copy/paste](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste); [Android Sharesheet](https://developer.android.com/training/sharing/send) (updated 2026-10-28); [ClipboardManager](https://developer.android.com/reference/android/content/ClipboardManager); [Flutter issue #101196](https://github.com/flutter/flutter/issues/101196) (framework-specific, anecdotal).

## NEW 10 — system/IME insets and compact navigation

API 30 does not automatically force edge-to-edge for target 30; if opting in, handle typed bar and IME insets once. Navigation-bar geometry varies by gesture/3-button mode. Use Material NavigationBar only for 3–5 peer destinations; don't double-apply Scaffold insets.

Sources: [Compose insets](https://developer.android.com/develop/ui/compose/system/insets); [Views edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge); [Compose NavigationBar](https://developer.android.com/develop/ui/compose/components/navigation-bar); [Xiaomi Redmi 9A FAQ](https://www.mi.com/global/support/faq/details/KA-07667/) (general model family, not proof for exact target phone).

## NEW 11 — chat action placement

Use 48dp interactive target and accessible label; avoid system gesture insets. Keep chat actions contextual; only stable peer-level destinations belong in bottom NavigationBar. No exact MIUI touch-target issue found.

Sources: [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) (updated 2026-11-04); [edge-to-edge design](https://developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge) (updated 2026-10-26); [Material NavigationBar](https://m3.material.io/components/navigation-bar/overview) (3–5 equal peers); [NDK ABIs](https://developer.android.com/ndk/guides/abis).

## NEW 12 — device status and safe system controls

Use Android framework APIs for battery, network callback state, memory, storage, audio volume, brightness, model, uptime and settings intents. Basic network state via ConnectivityManager needs no permission; SSID, operator/phone state, app storage rankings, flashlight and DND need additional permissions/special access or limits. Settings intents open a user-controlled settings page; they do not silently change it. Do not claim unavailable information.

Sources: [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager); [ACTION_BATTERY_CHANGED](https://developer.android.com/reference/android/content/Intent#ACTION_BATTERY_CHANGED); [network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state); [Settings](https://developer.android.com/reference/android/provider/Settings); [package visibility](https://developer.android.com/training/package-visibility/declaring) (API 30); [NDK ABI guide](https://developer.android.com/ndk/guides/abis); [Redmi 9 specs](https://www.mi.com/in/redmi-9/specs/) do not establish exact target phone/firmware.

## NEW 13 — phrases and macros

Parse phrases to deterministic typed actions, preview before execution, validate allowlisted steps, keep user-started/visible foreground execution with progress, cancellation and limits. API 30 system recognition is not guaranteed offline, and background activity start is restricted. Reuse existing parser/storage and AccessibilityService; no AI/cloud macro execution or new native dependency.

Sources: [SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer) (API 30 vs API 31 on-device API distinction; updated 2026-08-28); [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent); [package visibility use cases](https://developer.android.com/training/package-visibility/use-cases); [background launches](https://developer.android.com/guide/components/activities/background-starts) (restriction since API 29); [MIUI 12 security white paper](https://trust.mi.com/pdf/MIUI_Security_White_Paper_EN_May_2021.pdf) (general MIUI 12 autostart/memory context, not a V12.5.6.0 defect); [NDK ABIs](https://developer.android.com/ndk/guides/abis).

## Verification environment

Initial `./gradlew testDebugUnitTest --stacktrace` failed before compilation: `SDK location not found`. Provisioned stable Android platform `android-36`, Build Tools `36.0.0`, Platform Tools `37.0.1`, using official [Android SDK CLI instructions](https://developer.android.com/tools/sdkmanager) (updated 2026-10-28) and command-line-tools package `15859902` from the official [Android Studio downloads](https://developer.android.com/studio). Current docs label `sdkmanager` deprecated in favor of Android CLI; it was used only for environment provisioning and is not a project pin. No Android emulator or physical device is available. All MIUI/Redmi runtime checks are UNTESTED until run on the user's device.


### BUG 3 follow-up — IME submission API

The Android `AccessibilityNodeInfo.AccessibilityAction` reference explicitly lists `ACTION_IME_ENTER` as a public action added in API 30 and says it should only be exposed for a focused, editable view. Use `AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id` with `performAction`; it is not a direct integer field on `AccessibilityNodeInfo` (the first compile surfaced that distinction). The search flow verifies a visible editable node with a search label, sets the query, then requests that action. Source: [Android AccessibilityAction API reference](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction) (API 30; opened 2026-11-11).