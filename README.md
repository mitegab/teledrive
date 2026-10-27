# TeleDrive

TeleDrive is an Android file manager backed directly by your Telegram **Saved Messages**. 

It connects to Telegram as a native client using [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library). It is **not** a bot and requires **no custom server or intermediary cloud backend**—your device talks directly to Telegram’s servers.

> **Status:** Under active development. To run the app against a live Telegram account, you must build the native TDLib binaries and provide your own Telegram API credentials.

---

## Key Highlights

- **Telegram as Storage:** Uploads, downloads, and reads files directly from your Telegram Saved Messages chat.
- **Client-Side Virtual Folders:** Provides a traditional file/folder tree and favorites system by synchronizing lightweight, versioned metadata records.
- **Reliable Transfers:** Backed by Android Jetpack WorkManager and foreground services for upload/download resumption and retry controls.
- **Storage Access Framework (SAF):** Full integration with Android's native file picker, share sheet, and document providers.
- **Offline Cache:** Indexed locally with Room for instant search, offline browsing, and metadata access.

---

## Project Structure

```text
├── app/                  # UI (Jetpack Compose), Navigation, and Hilt DI root
├── core/
│   ├── model/           # Pure Kotlin data models (Files, Folders, Transfers, Auth)
│   ├── domain/          # Use cases, gateway interfaces, sync & search logic
│   ├── database/        # Room database, DAOs, and local metadata caching
│   └── telegram/        # TDLib JNI bindings, JSON-Java bridge, and Telegram gateway
├── docs/
│   ├── IMPLEMENTATION_PLAN.md    # Iterative milestones and validation criteria
│   └── TELEGRAM_INTEGRATION.md   # TDLib API details, limitations, and build requirements
└── PRIVACY.md           # Data retention, local storage policies, and credential security
```

---

## Prerequisites

Before building, ensure you have installed:

- **JDK 17**
- **Android SDK Platform 36** & Android Build Tools
- **Android NDK** & **CMake 3.22.1+**
- Build tools required by TDLib for Android (e.g., `gperf`, `openssl`, `ninja`, Python 3)

---

## Getting Started

### 1. Obtain Telegram API Credentials
1. Log in to [my.telegram.org](https://my.telegram.org) with your Telegram account.
2. Navigate to **API development tools** and create a new application.
3. Save your `api_id` and `api_hash`. *(Do not use a bot token).*

### 2. Configure Local Properties
Create a `local.properties` file in the project root (or copy `local.properties.example`):

```properties
sdk.dir=/path/to/your/android/sdk
TELEGRAM_API_ID=your_api_id_here
TELEGRAM_API_HASH=your_api_hash_here
```
> `local.properties` is already excluded in `.gitignore`. Never commit API keys or session data.

### 3. Build TDLib Native Binaries
TeleDrive requires official TDLib native libraries compiled for Android architectures:

```bash
./tools/build-tdlib-android.sh
```

*(Note: The project does not include prebuilt native binaries. Without this step, the app compiles to the onboarding screen but will fail when initializing the Telegram client.)*

### 4. Build and Run
Open the repository in Android Studio or build via the Gradle wrapper:

```bash
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Testing & Quality Assurance

Run the test suite and static analysis:

```bash
# Unit tests (Gateway mocks, Domain logic, Metadata sync)
./gradlew testDebugUnitTest

# Code style and Android Lint
./gradlew lintDebug

# Connected instrumented tests (requires connected device/emulator)
./gradlew connectedDebugAndroidTest
```

---

## Architecture & Important Limitations

- **Telegram Cloud Storage:** Saved Messages is standard Telegram cloud storage (server-side encrypted, **not** end-to-end encrypted like Secret Chats). Files are subject to Telegram's standard single-file size limits (2 GB for regular users, 4 GB for Telegram Premium).
- **Virtual Directory System:** Telegram has no native concept of nested directories. TeleDrive simulates folders using client-side metadata tags. Syncing between different devices is eventual and best-effort.
- **Background Execution:** While transfers use Android `WorkManager` and foreground notifications, the Android OS may still restrict long-running background tasks under battery-saver restrictions.
- **Authentication Safety:** Never commit database files, active session databases (`tdlib/`), or SMS/2FA codes to version control.
```
