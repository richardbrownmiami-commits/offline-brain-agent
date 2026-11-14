# Round 6 Research Log

**Scope:** Synthesis of the six supplied research results for the offline-brain-agent project. This log records platform/API guidance, source references, proposed project decisions, caveats, and tests. It does not claim project inspection, implementation, or target-device reproduction.

## Cross-cutting findings and constraints

- The stated target is Android 11 / API 30, with compileSdk 36 and minSdk/targetSdk 30 as stipulated in the workstream notes. Compile SDK level does not make newer platform APIs available at runtime; API 31+ and API 34+ behavior must not be assumed on API 30.
- The target description references Redmi M2006C3MII, MIUI V12.5.6.0, MT6765, 3–4 GB RAM, and armeabi-v7a. No authoritative, verified source in the supplied research documents a specific defect for this exact device/build/ABI combination in any of the six workstreams. This is “no matching issue documented in sources reviewed,” not proof that no issue exists. Device-side validation remains necessary.
- armeabi-v7a denotes 32-bit ARM; it is not evidence by itself of an Android framework defect. Verify actual `Build.SUPPORTED_ABIS`, device fingerprint, and packaged native libraries rather than inferring ABI from marketing/model information.
- Preserve Round 5 behavior and existing dependency/toolchain versions unless inspection identifies a concrete blocker. Current upstream release indexes are time-sensitive context, not upgrade recommendations. The Round 5 source, Gradle files/lockfiles, and device were not supplied for this research synthesis.
- Avoid logging notification content, credentials, resume contents/PII, typed macro text, settings plaintext, keys, or archive contents. Prefer redacted state, identifiers, counts, timings, and error categories.

## Workstream 1 — BUG 1: Real NotificationListenerService capture and silent filtering

### Research findings and Android/API notes

Android 11 is API 30. Notification-listener type-filter metadata described as API 31+ and the API 34 default-autobind behavior are not API-30 behavior. On API 30, declare `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and the `NotificationListenerService` intent action, and require the user to grant notification access in system settings. Wait for `onListenerConnected()` before calling APIs such as `getActiveNotifications()`.

`onNotificationPosted(StatusBarNotification)` reports newly posted notifications; updates can result in further post callbacks. Treat callbacks as at-least-once/upsertable events, not guaranteed one-time inserts. Starting with API N callbacks run on the main thread: extract a small immutable DTO and dispatch persistence off-thread. Do not do Room I/O or block inside callbacks. `onListenerDisconnected()` means events stop; `requestRebind(ComponentName)` is permitted there. Track disconnect/rebind state, reconnect, and reconcile with an active-notification snapshot. `getActiveNotifications()` is a best-effort current active set after connection, not notification history and cannot recover already-removed items.

Group summaries, ongoing/foreground-service items, media notifications, silent/low-importance notifications, and notifications with null text are not inherently guaranteed absent from the API-30 listener contract. Do not blanket-filter them absent explicit product policy. Extract nullable fields defensively. A stable key should use `StatusBarNotification` key (or a package/id/tag fallback); callback/snapshot overlap should use a shared idempotent conversion/filter/upsert path. Do not dedupe solely by text. Keep posted/updated timestamp semantics explicit. Never call `cancelNotification()` as part of capture.

The Android NLS reference warns that notification access/binding may be unavailable on low-RAM devices running Android Q and below. The supplied 3–4 GB RAM figure does not establish Android’s low-RAM classification; inspect `ActivityManager.isLowRamDevice()` and product configuration on-device. Room 2.8.5 is listed stable in the supplied release-note research, and Room 2.8.0 raised minSdk to 23, technically below this project’s API 30; that does not justify an upgrade.

### Project decision

Keep this change scoped to capture observability and persistence; preserve Round 5 retention behavior and do not invent a cutoff. Separate and report access granted, listener connected, callback receipt, filtering, enqueue, persistence result, and dedupe/upsert as distinct stages. Use the same converter/filter/upsert path for posted callbacks and active snapshots. Preserve notification classes by default; document and test any deliberate exclusions. Perform Room persistence asynchronously and transactionally where required. Synthetic-save success is only a persistence-path control, not proof of end-to-end real callback capture.

### Sources

- [NotificationListenerService API reference](https://developer.android.com/reference/android/service/notification/NotificationListenerService) — Android Developers; lifecycle/callback contract, filter metadata API 31+, default-autobind API 34+.
- [Notification API reference](https://developer.android.com/reference/android/app/Notification) — Android Developers; notification flags and categories.
- [Room release notes](https://developer.android.com/jetpack/androidx/releases/room) — AndroidX; supplied research reports Room 2.8.5 (2026-09-09) and minSdk 23 from 2.8.0.
- [Cannot receive notifications on the REDMI Note 12](https://www.mi.com/uk/support/faq/details/KA-93394/) — Xiaomi generic notification troubleshooting only; not the target model/build and not evidence of an NLS/ARMv7 bug.

### Recommended tests and diagnostics

- Unit-test extraction/filter policy for normal, silent/low-importance, ongoing/foreground-service, media, group summary/child, null title/text/extras, repeated updates, and same-text notifications with distinct stable keys.
- Room in-memory tests: callback then snapshot and reverse order yield one row; changed payload updates it; distinct keys remain distinct; retention boundary matches the existing Round 5 rule.
- Verify callback persistence is dispatched off-thread and failures are recorded without crashing service.
- API-30 emulator and physical target-device test: grant access, post real alerts from a separate test app (including grouped/silent/ongoing/media-like), verify callback metadata and eventual row, test removal policy, toggle access or restart to observe disconnect/rebind/reconciliation.
- Redacted diagnostics: connection/access state, callback/removal counts and times, filter reason, enqueue/persistence success/failure, dedupe count, snapshot count, disconnect duration/rebind attempts, and `isLowRamDevice()`.

## Workstream 2 — BUG 2: Process foreground/background app-lock grace period

### Research findings and Android/API notes

Do not treat `onPause()` as app exit: focus changes and multi-window can pause an activity while it remains visible; Android 10/API 29+ supports multi-resume. `onStop()` indicates an activity is no longer visible. `ProcessLifecycleOwner` aggregates activity states but deliberately delays process `ON_PAUSE`/`ON_STOP` and is not a millisecond-accurate timer. If exact grace timing matters, record the last relevant true foreground/visibility exit with a monotonic clock such as `SystemClock.elapsedRealtime()`, and ignore/cancel provisional transitions during lifecycle churn. On return, compare elapsed monotonic time to the selected duration; do not use wall clock. A cold process must initialize locked regardless of saved prior session state.

Android 11/API 30 is pre-Android 12; do not assume API-31 `HIDE_OVERLAY_WINDOWS`. `FLAG_SECURE` is a separate window control for screenshot/non-secure display protection, with official guidance noting API-30-and-lower protection is not fully reliable across devices. Keep this distinct from grace-period locking.

The supplied current Lifecycle release-note research lists 2.11.0 stable and 2.12 alpha but does not establish 2.11.0 compatibility with the unspecified project’s compileSdk 36/Compose/Kotlin combination. Prefer existing lifecycle dependencies or platform callbacks unless a verified need exists.

### Project decision

Keep the change narrowly scoped to authentication state/timing. Expose explicit choices: immediate (0), 30 seconds, 1 minute, 2 minutes (default), 5 minutes, and 15 minutes. Recents invocation alone is not an exit while the app remains displayed. Record actual exit and defer the decision until return. Immediate locks after a genuine exit; positive grace locks at/after threshold. Always start locked after process death. Keep Recents/screenshot `FLAG_SECURE` as a separate, off-by-default preference.

### Sources

- [The activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle) — Android Developers; lifecycle model.
- [ProcessLifecycleOwner API reference](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner) — Android Developers/AndroidX; delayed process events.
- [Support multi-window mode](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode) — Android Developers; API 29+ multi-resume and later differences.
- [Secure sensitive activities](https://developer.android.com/security/fraud-prevention/activities) — Android Developers; `FLAG_SECURE`, API-31 overlay API distinction.
- [Lifecycle release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle) — AndroidX; supplied research lists stable 2.11.0 and 2.12 preview.
- [Android ABIs](https://developer.android.com/ndk/guides/abis) — Android Developers/NDK; armeabi-v7a is 32-bit ARM.
- [Redmi 9 official product information](https://www.mi.com/in/redmi-9/) — Xiaomi; general family information, not the exact M2006C3MII firmware or lifecycle behavior.

### Recommended tests and diagnostics

Inject a monotonic clock; test all grace choices and just-below/equal/above thresholds without sleeping. Cover Home/Recents rapid return, switching apps on both sides of thresholds, notification shade/focus loss without visibility exit, configuration recreation, multiple activities, screen off/on per product policy, and process kill/relaunch (must start locked). Verify timestamp is set only on true exit and not overwritten by internal transitions. On API 30 target device, test Recents preview with `FLAG_SECURE` on/off independently. Redacted diagnostics may include lifecycle event, elapsed time/delta, configured duration, decision, cold-start lock reason, and secure-window setting; never log credentials or protected content.

## Workstream 3 — BUG 3: Safe small-talk routing before accessibility actions

### Research findings and Android/API notes

This is a text intent-routing issue, not an Android 30 or Compose defect. A deterministic classifier should give conversational intents (greetings, thanks, identity, capabilities, jokes, time/date) precedence where appropriate, then explicit device actions, then ordinary fallback. Accessibility setup should require explicit enable/configure/open intent; broad tokens such as “you,” “how,” “help,” “phone,” or “accessibility” must not by themselves trigger setup. Normalize case/whitespace/punctuation conservatively and avoid substring matching. Classify first, then render replies. `ACTION_ACCESSIBILITY_SETTINGS` opens system settings; it is not a permission grant or automatic service-enablement API, and a matching activity may not exist, so resolve/catch failures if launching it.

Android 11 behavior relevant to accessibility services includes the API-30 service metadata rule for accessibility-button association; it does not explain “how are you” being misrouted. API 34 `ACTION_ADVANCED_MEMORY_PROTECTION_SETTINGS` is later and unrelated. TTS interaction/package querying for a target-30 app needs a manifest `<queries>` declaration only if the app queries/interacts with TTS. No new dependency or LLM is required. The supplied current Compose release information is informational only and is not a reason to upgrade.

### Project decision

Make the minimal routing-only change: place/insert small-talk classification before accessibility setup; limit setup prompts to explicit positive action requests; preserve existing replies/actions for other cases and retain all Round 5 behavior. Do not change Kotlin, Compose, compileSdk, minSdk/targetSdk, or ABI for this correction.

### Sources

- [Settings API reference: ACTION_ACCESSIBILITY_SETTINGS](https://developer.android.com/reference/android/provider/Settings#ACTION_ACCESSIBILITY_SETTINGS) — Android Developers; action available well before API 30.
- [Behavior changes: Apps targeting Android 11](https://developer.android.com/about/versions/11/behavior-changes-11) — Android Developers; API 30 target behavior.
- [Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service) — Android Developers; accessibility framework guidance.
- [Conditions and loops (Kotlin control flow)](https://kotlinlang.org/docs/control-flow.html) — Kotlin; ordered `when` branch behavior.
- [Compose release notes](https://developer.android.com/jetpack/androidx/releases/compose) and [Compose BOM mapping](https://developer.android.com/jetpack/compose/bom/bom-mapping) — AndroidX; supplied research reports Compose stable modules 1.12.1 and Material3 1.4.0 as of 2026-11-11, not an upgrade recommendation.
- [How to update the Phone](https://www.mi.com/in/service/help/smartAnswer/1672832466469/) — Xiaomi generic update guidance; no matching bug documentation.

### Recommended tests and diagnostics

Use a pure classifier and assert route plus no settings launch/setup prompt for the supplied 40 unique non-action corpus. Include explicit positive action phrases (e.g. “open accessibility settings,” “enable the accessibility service”) and negative contrasts (“how do you feel about accessibility?”, “tell me what accessibility means”), plus precedence, case, punctuation, surrounding whitespace, and unknown fallback. The supplied research corpus includes an instruction to replace its repeated “how are you” with “how do you feel” to retain 40 unique phrases. On device verify small-talk reply and no settings intent/service-state change. Test settings activity resolution/fallback separately. Validate API 30 and a current emulator; log category/route only, not sensitive raw utterances.

## Workstream 4 — BUG 4: Deterministic resume extraction and focused document Q&A

### Research findings and Android/API notes

Use offline deterministic Kotlin extraction/Q&A; the referenced platform documentation does not require an LLM or new dependency. Detect document category from normalized headings/labels and structure (Resume/CV, Article, Report, Form), with an explicit unknown outcome. For a resume, map evidence to name, contact, summary, skills, jobs/employer/role/dates/bullets, education, and languages, retaining source text/provenance. Parse pipe-delimited role/company/place/date records whether separate-line or multiple entries on one line; preserve all jobs and never synthesize an anonymous title. Query against section/paragraph/job-block candidates and return a short evidence window (3–5 sentences or one complete job block), not a whole-document summary. Return not-found when unsupported by evidence. Normalize and deduplicate summary/key-point snippets; avoid summary source spans in key-point generation. Scanned/image-only PDF OCR is unsupported unless explicitly implemented.

For file import use `ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, appropriate MIME types (or `*/*` if broad formats are intentional), and `ContentResolver`/`InputStream` from the returned URI—not guessed paths or broad storage permission. Persist URI permission only when later access is needed. Android 11 SAF restrictions include Android/data and Android/obb selection limitations and tree restrictions for roots/Download as documented; no all-files permission is justified. Keep compileSdk 36 and minSdk/targetSdk 30 as stipulated. Verify any native dependency actually packages armeabi-v7a. Current Compose/Core/DocumentFile/Kotlin version indexes in the supplied research are not recommendations; preserve the existing compatible build baseline.

### Project decision

Preserve Round 5 behavior, dependencies, and build versions absent a concrete blocker. No project corpus, parser, lockfile, or approved answer fixture was provided, so exact compatibility/expected answer wording remains for maintainer confirmation. Use structured field provenance and explicit fallback; do not infer content not present in source.

### Sources

- [Storage updates in Android 11](https://developer.android.com/about/versions/11/privacy/storage) — Android Developers; API 30 storage behavior and target-SDK conditions.
- [Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files) — Android Developers; SAF.
- [Behavior changes: Apps targeting Android 11](https://developer.android.com/about/versions/11/behavior-changes-11) — Android Developers; API 30 target behavior.
- [Android ABIs](https://developer.android.com/ndk/guides/abis) — Android Developers/NDK; ABI definition.
- [Compose release notes](https://developer.android.com/jetpack/androidx/releases/compose), [AndroidX releases](https://developer.android.com/jetpack/androidx/versions), and [Kotlin release process](https://kotlinlang.org/docs/releases.html) — supplied research reports current indexes as of 2026-11-11 (Compose 1.12.1 modules/Material3 1.4.0, Core 1.19.1, DocumentFile 1.1.0, Kotlin 2.4.21); none is a project upgrade recommendation.

### Recommended tests and diagnostics

Build fixtures for a labeled resume with all fields/two jobs; multiple `Role | Company | Place | Dates` records on one line; missing/ambiguous role; article/report/form/unknown classification; duplicate summary suppression; evidence-window limits; not-found; and highlighting only matched evidence. Use the supplied ten fixed Q&A query shapes (name, email, phone, summary, skills, Acme role, Acme responsibilities, previous employer/role, education, languages) with exact expected evidence authored from an approved fixture. Test repeated-query stability and absence of unrelated sections. On API 30, test local/cloud/provider URIs, cancellation, malformed/empty input, large input, URI persistence if used, low-memory stress, parse latency, and ABI packaging. Log non-sensitive errors/counts only; never document content or PII.

## Workstream 5 — FEATURE 5: Typed macro model/editor/execution and cancellation

### Research findings and Android/API notes

Represent macros as a persisted, versioned, sealed/typed list of step variants rather than free-form action strings: `OpenApp`, `TapText`, `TypeText`, `Wait`, `Scroll`, `GlobalAction` (Back/Home/Recents), `Speak`, `RunChatCommand`, `DeviceStatus`, `RunMacro`. Validate nonblank fields, bounded waits, valid targets, and macro references/cycles. Use a sequential state machine with `Success`/`Failed`/`Cancelled`, run/step IDs, per-step timeout, visible current status, and first-failure stop for learned-phrase playback. Stop cancels the run Job and pending operation where supported and prevents future scheduling; it cannot roll back device actions already performed. Use cancellable waits and cancellation-safe callback bridges with late-callback/run-ID guards. Immutable editor list transformations should use stable step IDs; provide accessible drag handles and move-up/down alternatives. Duration is an estimate, not a guarantee.

On API 30, `AccessibilityService` supports documented global Back/Home/Recents and gesture dispatch; gestures require `android:canPerformGestures=true`. Package visibility filters discovery for targets of API 30+, so avoid enumerating all packages; use narrow `<queries>` for supported discoverable launch targets or handle unavailable apps. Prefer accessibility-node click/scroll where appropriate; use gestures for coordinates/drags and report failure when inaccessible/rejected. `ACTION_SET_TEXT` support varies; do not silently claim success when unsupported. TTS requires initialization/error handling. Accessibility trees/actions vary across apps, secure windows, custom views, and OEM behavior; obtain explicit user enablement and explain scope. Do not rely on post-API-30 global actions. No LLM is required.

### Project decision

Add typed model/editor/executor as an additive layer that preserves chat, learned-phrase behavior, and command dispatch. Persist schema version and stable IDs; migrate legacy data safely and fall back for unknown steps. Serialize to one run at a time, use run-scoped cancellation, deterministic first-failure halt, surfaced status/error, and prominent Stop. Seed tutorial as Open Chrome → Wait 2 seconds → Search cats, but resolve “Search cats” semantics before implementation (chat command versus explicit browser-search action). Preserve existing Compose/Kotlin/AGP versions; supplied source does not establish the full version matrix. Retain compileSdk 36 and minSdk/targetSdk 30.

### Sources

- [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) — Android Developers; global actions.
- [Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service) — Android Developers; gestures and service configuration.
- [Package visibility filtering on Android](https://developer.android.com/training/package-visibility) — Android Developers; API 30 target behavior.
- [Kotlin coroutines on Android](https://developer.android.com/kotlin/coroutines) — Android Developers; structured concurrency/cancellation. Supplied page example dependency 1.3.9 is not a current-version recommendation.
- [Compose release notes](https://developer.android.com/jetpack/androidx/releases/compose) — AndroidX; supplied research notes Compose 1.9 stable in Aug 2025 and newer entries; choose based on existing baseline.
- [Kotlin release process](https://kotlinlang.org/docs/releases.html) — JetBrains; supplied research lists 2.4.21 as of 2026-11-11, not an upgrade recommendation.
- [TextToSpeech API reference](https://developer.android.com/reference/android/speech/tts/TextToSpeech) — Android Developers.
- [Accessibility testing](https://developer.android.com/training/testing/espresso/accessibility-checking) — Android Developers.
- [Update the Phone](https://www.mi.com/in/service/help/smartAnswer/1672832466469/) — Xiaomi generic update guidance only; no accessibility compatibility data.

### Recommended tests and diagnostics

Unit-test serialization/migrations, stable IDs, editor operations, validation, duration estimates, nested macro cycle/depth protection, execution order, first-failure halt, cancellation during wait/callback/launch/nested run, repeated Stop, and suppression of late callbacks. Instrumented API-30 tests: installed/missing Chrome, narrow package visibility, accessible/inaccessible text, rejected `ACTION_SET_TEXT`, gesture config/results, global action results, scrolling, missing TTS engine, progress and Stop. Device test on emulator and target Redmi with accessibility enabled, lock/unlock, gesture/button navigation, memory pressure, rotation/background/resume. Check ABI in APK and install armeabi-v7a build on target. Log run/step IDs/type, elapsed time, result/failure category and API result, not typed content, commands, or screen content.

## Workstream 6 — FEATURE 6: All-or-nothing Room plus DataStore encrypted restore

### Research findings and Android/API notes

A SQLite transaction and a DataStore `updateData` transaction are each scoped to their own store; neither atomically spans both. Sequential replacement of independent files is not inherently crash-atomic. Validate/decrypt the full archive in private staging before touching live state: format/schema versions, required tables/integrity, settings schema, lengths, and authenticated encryption tag. Quiesce reads/writes through a restore-wide gate; close Room so DB/WAL/SHM handles are not live, and prevent concurrent DataStore updates/collectors from racing. Snapshot old database state (including necessary WAL/checkpoint handling) and settings; apply both replacements; reopen/verify both; on exception restore snapshots before lifting gate. Persist a restore journal and recover it at startup if interrupted. Rollback can cover injected exceptions only if rollback succeeds; process death, power loss, or rollback I/O failure can interrupt sequential replacements. Do not claim stronger crash durability without a tested generation/pointer protocol.

Use SAF for shared-storage archive selection then copy to private staging; no broad storage permission is justified. API 30 supports ordinary SQLite transactions and `beginTransactionNonExclusive`; `beginTransactionReadOnly` is API 35 and unavailable. Current supplied AndroidX notes list Room 2.8.5 (minSdk 23 from 2.8.0) and DataStore 1.3.0, but project AGP/Kotlin/KSP/toolchain compatibility is unknown. DataStore Tink is not necessary if Round 5 already defines archive encryption/key handling; do not change encryption format without review.

### Project decision

Preserve Round 5 behavior and dependencies. Inspect actual Gradle/lockfile versions before implementation. Use staged validation, old-state snapshots, exclusive restore gate, durable journal, rollback/recovery, and success only after both stores reopen and validate. Surface recovery failure explicitly. Keep aligned Room artifacts at one version if a future independent upgrade is justified.

### Sources

- [DataStore — Android Developers](https://developer.android.com/topic/libraries/architecture/datastore) — supplied current guide; `updateData` transaction is per stored object/file.
- [Room release notes](https://developer.android.com/jetpack/androidx/releases/room) — AndroidX; supplied research lists stable 2.8.5 (2026-09-09), minSdk 23 from 2.8.0.
- [DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore) — AndroidX; supplied research lists stable 1.3.0 (2026-10-21), including datastore-tink.
- [SQLiteDatabase API reference](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase) — Android Developers; transaction API levels, including read-only transaction API 35.
- [Android 11](https://developer.android.com/about/versions/11) — Android Developers; API 30 platform.
- [Coroutines basics](https://kotlinlang.org/docs/coroutines-basics.html) — Kotlin; structured coroutine concepts.
- [Redmi 9 specifications](https://www.mi.com/in/redmi-9/specs/) — Xiaomi; lists Helio G35 and 4 GB configurations, but supplied research notes model identity is not established and these specs conflict with assuming the target’s MT6765 context. Not evidence of a restore defect.

### Recommended tests and diagnostics

Inject failures after staging, DB snapshot, settings snapshot, DB replacement, settings replacement, and reopen/verification. Assert originals are logically restored, DB integrity succeeds, Room reopens, settings read, and restore reports failure. Also test invalid auth/tag, malformed settings, unsupported versions, truncation, insufficient storage/copy failure, cancellation, concurrent access blocked, and startup recovery at each journal phase. Test forced process death/reboot between replacements as a separate durability property. On API 30 emulator and actual ARMv7/MIUI device when available, record only phase, error class, elapsed time, recovery outcome, SDK, supported ABIs, fingerprint, and library/tool versions—not keys, plaintext settings, or archive contents.

## Unavailable or failed research and validation

- The supplied result set reports `failures: []`; no workstream is marked as a failed search. However, several requested device-specific claims were unavailable: no authoritative source documenting a matching defect for Redmi M2006C3MII / MIUI V12.5.6.0 / armeabi-v7a was located for NLS capture, lifecycle grace, small-talk routing, SAF/resume parsing, accessibility macros, or Room/DataStore restore. Xiaomi pages found were generic or for other Redmi-family models and do not substantiate those defects.
- No target handset or project source/build files were available to this research synthesis. Therefore exact on-device behavior, current Round 5 implementation/dependency compatibility, actual ABI packaging, parser fixture correctness, and release-build behavior remain unverified.
- Product decisions still requiring confirmation include notification retention/removal policy and desired empty-content treatment; lifecycle definition of foreground exit and screen-off policy; exact action phrase boundaries; approved resume fixture/expected answers and scanned-PDF support; executable semantics of tutorial “Search cats”; and the crash-durability guarantee required for cross-store restore.
- Version listings in the supplied results are dated research snapshots (mostly checked 2026-11-11). Recheck official release notes and the actual project toolchain before any version change.


## Additional permission-lint decisions

- [Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission) — Android Developers: `POST_NOTIFICATIONS` applies on Android 13/API 33+, and for apps targeting API 32 or lower the system controls when the prompt appears after a notification channel is created. Decision: retain targetSdk 30; catch a `SecurityException` from the benign test-notification post and return a clear permission/channel message rather than assuming posting is allowed. Android 11/API 30 has no `POST_NOTIFICATIONS` runtime permission.
- [AudioRecord API reference](https://developer.android.com/reference/android/media/AudioRecord) — Android Developers: `AudioRecord` construction requires `RECORD_AUDIO`; a missing permission can yield an uninitialized recorder (and Builder can throw). Decision: check `RECORD_AUDIO` immediately before construction and fail with a permission error before opening audio hardware.
- [Request runtime permissions](https://developer.android.com/training/permissions/requesting) — Android Developers: check permission for each protected operation and degrade gracefully after denial/revocation. Decision: keep permission prompts tied to user-started microphone capture; never start it from a background callback.
