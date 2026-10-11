# TeleDrive implementation plan

**Repository:** `mitegab/teledrive`  
**Working branch:** `arena/dcfda4d4-teledrive`  
**Plan date:** 2026-10-11 (UTC)

## Starting point

The checkout contained only the placeholder `README.md`; there was no Android or Gradle project to preserve. The project will therefore be built from a clean foundation, with each meaningful feature/subfeature committed separately on the Arena-provided branch.

At plan creation the execution environment had no JDK, Gradle installation, Android SDK, or Android NDK. A temporary JDK was later obtained for diagnostics, but the restricted sandbox cannot fetch the Gradle distribution or Android dependencies, and still has no Android SDK/NDK. The project includes a reproducible Gradle wrapper configuration and build scripts. The final report must distinguish code review/static validation from an actual APK build.

## Architecture decisions

- **Application:** one Android `app` module for the Compose shell and feature navigation.
- **Core modules:**
  - `core:model`: Kotlin-only stable domain models and enums.
  - `core:domain`: Kotlin-only gateway contracts, mapping, search/filter/sort rules, transfer policy, and metadata synchronization rules.
  - `core:database`: Room entities, DAOs, migrations, and the local metadata repository.
  - `core:telegram`: Android TDLib JSON-Java adapter. It owns the asynchronous TDLib client, authorization mapping, Saved Messages history mapping, file transfer requests, and safe error mapping.
- **Dependency direction:** presentation -> domain/database/telegram; database and telegram -> domain/model; domain/model do not depend on Android or TDLib.
- **Dependency injection:** Hilt in the Android app. ViewModels depend on use cases/repositories, never on TDLib or `Context` directly.
- **Concurrency:** coroutines and `Flow`; TDLib receives are consumed by one dedicated IO receiver loop and dispatched to request waiters/update flows. No TDLib call blocks the main thread.
- **Persistence:** TDLib owns its encrypted/optionally encrypted Telegram database and file cache; Room owns TeleDrive's local index; DataStore owns non-sensitive preferences; Android Keystore protects the TDLib database encryption key.
- **Files:** Storage Access Framework URIs are staged into app-private temporary files for TDLib uploads. Downloads remain TDLib-managed until exported through a user-selected SAF destination. No broad storage permission is planned.

## Iterative delivery sequence

Each item is intended to be a separate commit with a focused message. A later item is not considered complete merely because its UI exists; it must be wired to the preceding layer and covered by tests where practical.

1. **Research and project foundation**
   - This plan and `docs/TELEGRAM_INTEGRATION.md`.
   - Gradle version catalog, wrapper configuration, module graph, lint/test conventions, secret-safe configuration template.
2. **Domain model and pure rules**
   - File/folder/transfer/auth models.
   - Media detection, filename/MIME handling, stable remote identity, sorting/filtering/search.
   - Transfer state machine, retry policy, history pagination, metadata codec and merge rules.
   - JVM unit tests for all deterministic rules.
3. **Room local index**
   - `StoredFile`, `Folder`, `Transfer`, and `SyncState` entities.
   - DAOs, migrations, transactional repository, cache/export state distinction.
   - Room tests and repository tests with a fake gateway.
4. **TDLib adapter**
   - Official TDLib JSON-Java binding source and documented native-library build/import path.
   - Single ordered receiver loop, request correlation, authorization states, credential validation, Keystore-backed database key.
   - Saved Messages discovery through `getMe` then `getChat`, history pagination, normalized message mapping, file upload/download/delete/cancel calls, real `updateFile` progress, and safe TDLib error mapping.
5. **Transfer orchestration and synchronization**
   - Queue persistence, bounded concurrency, lifecycle-safe upload/download workers, retry/cancel behavior.
   - Append-only reserved metadata records in Saved Messages; parser ignores them as files, conflict handling is deterministic, malformed records are ignored safely.
   - Explicit limitation copy in UI: folder/favorite metadata is only cross-device after metadata records have synchronized; no hidden claim of instant sync.
6. **Compose design system and navigation shell**
   - Light/dark TeleDrive visual language, typography, spacing, accessibility semantics, responsive scaffold, navigation routes.
7. **Onboarding/authentication UI**
   - Explain Telegram-backed storage and privacy tradeoffs.
   - Phone, code, password, email/other-device/error states; cancel/logout and restart restoration.
8. **Home/file browser/search UI**
   - Overview, recent and category shortcuts, paginated list/grid, breadcrumbs, sort/filter, multi-select, local-index search disclaimer, empty/loading/error states.
9. **File actions and previews**
   - Details, organize/favorite, SAF upload/export/share/open, local-cache removal, confirmed remote delete, safe image/text/PDF/audio/video/external preview routing.
10. **Settings/reliability hardening**
    - Theme/view/sort preferences, cache cleanup, sync controls, connection state, about/licenses, background transfer constraints, process-death recovery.
11. **Release validation/documentation**
    - README, `PRIVACY.md`, troubleshooting, native ABI/APK checks, unit/repository/UI tests, lint, debug APK if the environment supports it, and a final honest implementation report.

## External prerequisites and risks

- A developer must obtain their own `api_id` and `api_hash` from [my.telegram.org](https://my.telegram.org) and provide them locally through `local.properties` or Gradle properties. No credential will be committed or requested in chat.
- TDLib's official Android example builds native libraries from source and requires an Android SDK/NDK, CMake, OpenSSL build, and generated bindings. Native binaries are not fabricated in this repository. A setup script will make the exact source commit and ABI inputs explicit.
- Live login/upload/download testing needs a Telegram account and valid credentials. Without those, only fake-gateway/repository/unit/UI tests can be verified.
- Ordinary Saved Messages are Telegram cloud data, not an end-to-end encrypted storage vault. The product will state this clearly and will not claim a Telegram quota/capacity number.
- Saved Messages folders are an application-level abstraction. TeleDrive will use reserved, versioned text metadata records and never rewrite/delete arbitrary existing messages. Conflicting or malformed metadata will be handled conservatively.
- Android background execution and foreground-service rules change. Deferrable indexing uses WorkManager; long transfers only use a foreground service when the current Android rules and declared use case permit it. The UI will not promise indefinite background execution.

## Validation commands

Once the Android toolchain is present:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest   # only with an emulator/device
```

The final report will include the exact commands actually run and will not label live Telegram behavior verified unless it was exercised against Telegram with an authenticated account.
