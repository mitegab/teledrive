# TeleDrive bug-fix plan

Date: 2026-10-11
Branch: `arena/dcfda4d4-teledrive`

## Baseline

The repository's initial commit contained only a placeholder README. The existing implementation was built in the working tree and has now been captured without resetting or replacing it. No credentials, TDLib session data, native libraries, or personal Telegram files are present.

The baseline Gradle command was attempted:

```bash
JAVA_HOME=/tmp/teledrive-jdk-py/jdk4py/java-runtime \
PATH="$JAVA_HOME/bin:$PATH" ./gradlew testDebugUnitTest
```

It cannot reach the configured Gradle distribution at `services.gradle.org` in this sandbox, so there is no baseline test/build result yet. Android SDK/NDK availability also needs to be checked on a toolchain-enabled machine.

## Iteration status

The implementation and its deterministic regression coverage are captured in these commits:

- `b5e38f5` (`docs: document storage recovery and validation limits`) records the implementation plan, privacy boundary, TDLib integration, and unavailable-prerequisite status.
- `c6b53e1` (`feat(storage): implement Telegram-backed file workflows`) adds the Android/core foundation, asynchronous upload tracker/gateway, media mapper, Room index/migrations, managed storage, transfer recovery, secure viewer routing, metadata rules, and tests.
- `11be71b` (`fix(model): classify PDFs as documents`) keeps PDF filtering distinct from text-file filtering.
- `df0d447` (`fix(transfer): settle cancellation against latest state`) prevents a stale transfer-row snapshot from canceling a transfer that already completed.
- `b2b7cc8` (`fix(sync): reconcile permanent remote deletions`) handles TDLib deletion updates without removing local managed bytes.
- `127a82a` (`fix(sync): reconcile stale remote index entries`) reconciles absence only after a completed full history scan.
- `ac4b127` (`test(transfer): cover cancellation failure policy`) adds JVM coverage for cancellation and retry policy.

The targeted review and deterministic checks continue on the same branch.

With a temporary JDK from `jdk4py` and the Kotlin compiler package available through the allowed npm registry, selected pure-Kotlin model/domain tests, synchronization tests, upload-tracker tests, mapper tests with a JSON stub, fake-gateway tests, transfer-failure tests, and TDLib/app source smoke compilations pass. The Gradle wrapper still cannot download Gradle because the configured distribution endpoint fails TLS here; Android SDK/NDK, generated native TDLib libraries, and live Telegram credentials remain unavailable. No live Android, TDLib, or APK result is claimed.

## Diagnosis from source inspection

The following defects were identified in the original implementation plan and are now addressed by the committed implementation.

### Upload

The original upload path treated `sendMessage`'s immediate response as mandatory final media metadata. TDLib can instead return a sending message whose final/usable content arrives through later updates. The gateway now retains a pending waiter, handles `updateNewMessage`, `updateMessageContent`, `updateMessageSendSucceeded`, and `updateMessageSendFailed`, reconciles provisional/final IDs scoped to the Saved Messages chat, and completes only once usable final media is mapped. Duplicate terminal updates become no-ops; cancellation and retryable failures clean up or preserve the persisted staging row at the appropriate boundary.

### Synchronization

The mapper now covers document/PDF/ZIP content, photos, videos, audio/music, voice notes, animations, video notes, and intentionally supported stickers using TDLib's type-specific nested file fields. Regression fixtures cover the supported shapes, MIME-derived names, largest-photo selection, permanent-vs-cache-only deletion updates, unknown messages, remote-only state, cursor checkpoints, repeated pages, and deduplication through Room identity preservation. Synchronization advances only after page persistence, reconciles absence only after a completed full scan, and bounds pathological cursor loops.

### Local storage

`LocalFileStorage` now keeps verified copies under app-private `files/downloads/<stable-hash>/`, publishes through temporary `.part` files, rejects size mismatches, reconciles missing paths, cleans stale temporary files, and exposes only managed paths for deletion/export/viewing. TDLib cache paths, app-managed copies, and SAF export destinations remain separate. Room transfer rows plus a unique connected-network WorkManager drain recover queued, failed, and process-interrupted work; duplicate downloads coalesce in process.

### Viewers

`FileOpenRouter` now validates that the file is inside managed storage, resolves an explicit/non-generic MIME type with filename fallbacks, checks for a compatible handler, and shares/open it through a private `FileProvider` content URI. Images and bounded text have in-app preview paths; PDF, audio, and video use documented installed-viewer fallbacks when no bundled renderer is present. Remote-only actions download into managed storage first, and SAF export is explicit.

## Implementation sequence

1. **Complete:** Add deterministic upload lifecycle/update fixtures and fix TDLib update correlation.
2. **Complete:** Add comprehensive message fixtures and mapper/pagination/deduplication tests; extend supported media mapping.
3. **Complete for the available code/toolchain:** Add managed local storage, verified copies, persistent transfer recovery, cancellation, and instrumented storage tests.
4. **Complete for the available code/toolchain:** Add viewer routing, Android URI/MIME checks, private content URIs, sharing/export, and documented PDF/audio/video fallbacks.
5. **Remaining validation:** Run the full Gradle unit/lint/build/instrumented suite and generate an APK when Gradle, Android SDK/NDK, generated TDLib libraries, and configured credentials are available. Inspect every resulting diff and continue with live-independent tests where prerequisites remain unavailable.

Live Telegram behavior will not be reported as verified without an authenticated account and generated TDLib native libraries.
