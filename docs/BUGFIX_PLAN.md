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
- `e7a6750` (`fix(transfer): preserve recovery on lifecycle cancellation`) keeps generic coroutine/WorkManager stops recoverable while the explicit UI cancellation path alone commits `CANCELED`.
- `a8b0146` (`fix(build): repair TDLib helper shell syntax`) makes the native-build helper parse correctly and fail truthfully at the missing-SDK prerequisite.
- `bb9c7d4` (`test(database): cover full remote reconciliation`) covers absence reconciliation while preserving local managed bytes.
- `05c1de6` (`fix(download): clear cancellation markers after early stop`) cleans explicit-cancel bookkeeping even when a download stops before creating its transfer row.
- `19a43c2` (`fix(transfer): retry transient numeric server errors`) treats TDLib-style 429 and 5xx error codes as retryable even when their messages are generic, with focused model coverage.

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

---

## Fix session: "Chat not found", broken sync, broken upload (2026-10-19)

Branch: `arena/a15aa970-teledrive`. Full diagnosis with per-suspect verdicts and
file:line evidence is in `docs/DIAGNOSIS.md`. Fixes were applied in the briefed
order, one focused commit per defect, each with tests:

| Commit | Fix |
| --- | --- |
| `8da2e54` | diagnosis note (3.1–3.7 verdicts against `td_api.tl` at `c15d3f5a`) |
| `0a6adcd` | **3.1** Saved Messages resolution via `createPrivateChat` + `loadChats` fallback, validation, caching, invalidation, one self-heal retry (`SavedMessagesChatResolver`) |
| `1cb85b3` | **3.2** `awaitReadySession` gate on every data-plane gateway call; clear `NOT_READY` errors on timeout/login-required/closing states |
| `d235710` | **3.3** upload staging as `staging/<uuid>/<displayName>` (`UploadStagingRules`), legacy `upload-*.part` recovery kept, extension-preserving truncation |
| `fa53abd` | **3.4** short/empty `getChatHistory` answers are retried a bounded number of times; deletions reconciled only after a completed full scan that saw messages |
| `b2e3ac3` | **3.5** explicit error codes (`CHAT_NOT_FOUND`, explicit auth markers instead of `AUTH`/`PHONE`/`CODE` substrings); `CHAT_NOT_FOUND` self-heals once in the gateway |
| `d69ac44` | **3.6** TDLib 1.8.68 request shapes: `inputMessageDocument` wraps `inputDocument`; email code wraps `emailAddressAuthenticationCode`; all builders in `TdLibRequests` with key-level tests |
| `1b93753` | **3.7** `setTdlibParameters` gate resets on logout/close/turnover/failure; Saved Messages cache invalidated on every session-ending transition |
| `497b7e4` | diagnostics: redacted `TeleDrive` structured logging + step-aware UI errors ("Syncing: could not open Saved Messages") |

### What the tests cover now

37 new JVM tests run against the real mapping/request/resolution/staging/sync
code (JSON fixtures, no live Telegram): chat resolution paths and self-heal,
readiness wait/timeout/fail-fast, upload staging layout and traversal rejection,
sync empty/short-page retries and reconcile guards, error classification and
retryability, and every TDLib request shape listed in the brief.

### What remains unverified (needs a real device + account)

- Live authorization, Saved Messages discovery, upload/download/delete flows.
- That TDLib accepts the corrected `inputMessageDocument` in a live send and
  names the document `report.pdf` end to end (shape is verified against
  `td_api.tl` only).
- `updateMessageSendSucceeded`/`Failed` ordering and 30-minute upload waiter
  behavior under real network conditions.
- The `app/` module (coordinators, WorkManager drain, Compose UI) compiles and
  runs only under the Android toolchain; the offline harness covers `core/*`.

### How this session was verified

`./gradlew testDebugUnitTest` cannot run in this sandbox (the Gradle
distribution and Maven repositories are unreachable). An offline harness —
Kotlin 2.4.21 compiler + JDK 25, JUnit-compatible assertions, org.json and
kotlinx-serialization compiled from pinned sources — compiled the
`core/model`, `core/domain`, `core/telegram`, `core/testing` modules and ran
their real test sources: **80/80 passing** (34 pre-existing, 46 new/updated).
No live Telegram/Android result is claimed.
