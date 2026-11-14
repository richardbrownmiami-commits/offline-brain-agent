# Changelog

## Round 8

- Fixed the Android CI workflow to use JDK 21 and the repository Gradle wrapper, and to retain APKs plus the release mapping file.
- Added explicit lifecycle and saved-state owners for the floating overlay, stable Compose state, non-modal window flags, and Diagnostics attach-error detail.
- Added a guarded five-second Android speech-start watchdog, Vosk fallback after non-recoverable failures, and a bundled small English Vosk model.
- Changed repository replies to versioned typed JSON and added status, notification-summary, notification-list, and document-summary cards in chat.
- Replaced strict notification phrases with local token scoring, added 50 natural-sentence fixtures and prior-topic follow-ups, mapped phone/Xiaomi categories, and added a 60-second listener-repair cooldown.
- Shared one application-scoped repository, split screens into screen-specific files, added feature-facing ViewModel adapters, removed unused dependencies and stale reports, and consolidated old research logs.
- The feature-specific ViewModels are currently thin adapters over the shared BrainViewModel; workflow/state extraction remains incomplete. Phone-only behavior has not been verified on-device.

## 1.0.5

- Current documentation identifies the project version and supported Android build baseline.
- Archived earlier round research under `docs/research/` and recorded the Round 8 Vosk model source and checksum.

Device-specific runtime behavior is not asserted here; consult actual device-test records before making compatibility claims.
