# TeleDrive

TeleDrive is an Android file-manager experience backed by a user's Telegram Saved Messages. It is not a Telegram bot and it does not run a custom cloud backend.

> **Status:** active implementation. Live Telegram behavior is gated by the developer's own TDLib native build and API credentials; see the documentation before running the app.

## Repository map

- `app/` — Compose UI, navigation, Hilt composition root, and Android integration.
- `core/model/` — platform-independent file, folder, transfer, and authorization models.
- `core/domain/` — gateway contracts, deterministic file/search/transfer/synchronization rules, and use cases.
- `core/database/` — Room local index and transactional metadata repository.
- `core/telegram/` — TDLib JSON-Java gateway, authorization, Saved Messages mapping, and real transfer calls.
- `docs/IMPLEMENTATION_PLAN.md` — iterative delivery plan and validation gates.
- `docs/TELEGRAM_INTEGRATION.md` — verified TDLib APIs, native build requirements, and integration limitations.
- `PRIVACY.md` — data flows, local persistence, cache and deletion behavior.

## Local setup

1. Install JDK 17, Android SDK platform 36, Android build tools, Android NDK, CMake 3.22.1, and the tools required by TDLib's official Android build (including OpenSSL build prerequisites).
2. Create a Telegram application at [my.telegram.org](https://my.telegram.org). Do not use a bot token or a sample API ID for distribution.
3. Copy `local.properties.example` to `local.properties` and add your own `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`. This file is ignored by Git.
4. Build the pinned official TDLib source and native libraries:

   ```bash
   ./tools/build-tdlib-android.sh
   ```

5. Open the project in Android Studio or run the Gradle tasks below.

The native build is deliberately not replaced with a mock. Until it has run, the app can compile/launch its onboarding UI but a Telegram connection reports a clear native-library configuration error.

## Validation

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
# with an emulator/device:
./gradlew connectedDebugAndroidTest
```

The debug APK, when the Android toolchain is available, is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Current verification status

The repository contains the real gateway boundary, TDLib authorization flow, Saved Messages indexing, SAF upload/download/export/open/share flows, Room metadata, virtual-folder metadata synchronization, persisted transfers, retry controls, WorkManager resumption, settings, and deterministic fake-gateway tests. This checkout has **not** been authenticated against a live Telegram account.

The sandbox has no Android SDK/NDK and cannot fetch the Gradle distribution from its restricted network, so no Gradle test, lint result, or APK is claimed here. Run the validation commands above on a machine with the documented Android toolchain. Do not treat fake-gateway tests as live Telegram verification.

## Important product limitations

- Saved Messages is ordinary Telegram cloud data; TeleDrive does not claim end-to-end encryption or a Telegram storage quota.
- Virtual folders and favorites are TeleDrive metadata. They use reserved, versioned append-only records and are eventual/best-effort across devices, not a server-side filesystem.
- Live authorization/upload/download testing requires a developer-owned Telegram account and credentials. Never commit credentials, session state, authentication codes, or personal files.
- Android may stop background work. Transfer behavior follows current WorkManager and foreground-service rules rather than promising indefinite execution.
