# Phase 0 audit — universal offline downloads, PDF reader, error handling

Date: 2026-10-21 (UTC) · Branch: `arena/5207da66-teledrive` · Base: `780ca8a`
Scope: audit only. This document records what exists before the implementation commits that follow it.

## 1. Verified baseline

| Area | State |
| --- | --- |
| Build | AGP 9.2.0, Kotlin 2.2.10, KSP 2.3.10, compileSdk 37, minSdk 26, targetSdk 36, Gradle 9.4.1, Compose BOM 2026.09.00, Room 2.8.5, WorkManager 2.12.0, Hilt 2.60.1, Media3 1.11.1, TDLib 1.8.68 |
| Modules | `app`, `core:model` (pure Kotlin), `core:domain` (pure Kotlin), `core:database` (Room), `core:telegram` (TDLib JSON/JNI), `core:testing` (fake gateway) |
| Telegram | `TdLibJsonGateway` owns one receive loop, `@extra` request correlation, readiness gating (`awaitReadySession`), Saved Messages resolution with self-heal, paged history, upload waiter + send updates, download waiter, cancel, deletion updates, redacted `TeleDriveLog` |
| Storage | `LocalFileStorage`: app-private `files/downloads/<sha256(stableId)>/<name>`, `.part` publish + fsync + size check, managed-path validation, SAF export, staging cleanup |
| Transfers | Room transfer rows, `TransferStateMachine`, retry policy (30s→900s), `TransferDrainWorker` (unique `teledrive-transfer-drain`, network constraint), in-process `ActiveTransferRegistry`, `DownloadCoordinator` dedupe map, upload staging tracker |
| Viewers | Image pager with EXIF/zoom, Media3 ExoPlayer video/audio, `FileOpenRouter` (FileProvider, MIME resolution), `ViewerIntents`, export/share |
| Playback | `PlaybackController` (ExoPlayer + MediaSession), queue, progress store, error mapper, mini player |
| Tests | JVM tests in `core/model`, `core/domain`, `core/telegram`, `core:testing`, app unit tests, Room/androidTest tests |

## 2. What is incomplete or risky

1. **Downloads have no first-class offline lifecycle.** `LocalAvailability` is the only offline record; there is no per-file observable status (queued/preparing/downloading/verifying/failed/canceled), no speed/ETA, no storage preflight, and no bounded concurrency (each call is an independent coroutine).
2. **Duplicate work is possible.** `DownloadCoordinator.download` dedupes by in-process `Deferred`, but `retry()` does not, and `TransferDrainWorker` can start a retry while the UI transfer is still running (`RUNNING` + `activeTransfers` is only a hint). Interrupted `RUNNING` rows can remain forever.
3. **No startup reconciliation.** Missing local files are only pruned when `FileLibraryViewModel` is created; no transfer recovery pass marks interrupted rows; orphaned download directories are not cleaned.
4. **No UI for offline use.** Download exists only inside the file-details dialog, without progress/cancel/retry per row, no multi-select batch download, no Offline library, no viewer-level download button (viewers implicitly download and only show a spinner).
5. **PDF is not viewable in-app.** `DefaultViewerRouter` already returns `InAppPdf`, but `FileBrowserScreen` never navigates for it and `ViewerScreen` has no PDF path; the user gets the generic external fallback. No progress persistence, search, outline, zoom, or page navigation exists.
6. **Silent failures exist.** 52 `runCatching` sites; notable silent handlers: `PlaybackController:131` (empty catch), `AudioPlayerScreen:221` (`catch (_: Exception) { null }`), `UploadCoordinator:173` (drops managed-copy failure), `MediaMetadataExtractor:60`, `TdLibJsonGateway:423` (receive loop), `MediaPlaybackRepository:43` (message only). `ViewerViewModel` surfaces raw exception messages.
7. **No typed error model.** `StorageGatewayException` carries code/message/retryable; everywhere else errors are raw strings or `IOException`. There is no category, no operation context, no user-facing vs diagnostic separation, and no single mapping from local/IO/PDF/media failures.
8. **Progress persistence is chatty.** `TransferViewModel.applyProgress` writes Room for every TDLib `updateFile`, and `FileLibraryViewModel` rebuilds its transfer list on every update; large transfers cause avoidable DB/Compose churn.
9. **Database lacks offline metadata.** `stored_files` has `localAvailability`/`localPath` only; there is no download timestamp, no offline query support (`FileQuery` has no `localOnly`), and no reconciliation API.
10. **WorkManager loop risk.** `TransferDrainWorker` returns `Result.retry()` whenever any transfer fails, without a bounded attempt budget; combined with `ExistingWorkPolicy.KEEP` this can keep a doomed transfer "active" indefinitely.
11. **Media queue placeholder path.** `MediaPlaybackRepository.buildQueueFromIds` puts `""` as `localPath` for remote-only items, which can produce a player item with no data source until a download happens.
12. **Storage lifecycle gaps.** Downloads do not check free space, failed partials are left to a 24h sweep, and there is no explicit distinction between "clear cache" and "remove offline copy" in Settings (`clearLocalCache` deletes every offline file).

## 3. Where the new features integrate

- `core:model` — typed errors and mapping, offline state/verification policy, transfer state additions, download limits + speed estimator, PDF models + tolerant outline parser. All pure Kotlin → JVM testable.
- `core:domain` — repository contract additions (`saveLocalCopy`, `clearLocalCopy`, `markTransferFailed`, reconciliation), AppError mapping for `StorageGatewayException`.
- `core:database` — migration 4 (`offlineSinceEpochSeconds`), DAO/mapper updates, offline queries.
- `app/files` — `OfflineFileRepository` (resolution + reconciliation), rewritten `DownloadCoordinator` (queue, bounded concurrency, verify, retry, cancel, status), `DownloadStatusStore` (observable snapshots), `LocalFileStorage` space/verification helpers.
- `app/pdf` — `PdfDocumentController` (async render, bounded LRU bitmap cache, renderer lifetime), `PdfReadingProgressStore`, `PdfReaderViewModel`, `PdfReaderScreen`, navigation route `pdf/{stableId}`.
- `app/transfers` — bounded drain, stale-row recovery, richer status UI.
- `app/playback`, `app/session`, `app/settings`, `app/files/viewer` — AppError-based messaging and no silent catches.

No new third-party dependency is required: the PDF reader uses the platform `android.graphics.pdf.PdfRenderer` (API 26 baseline; API 35 adds password loading and `Page.searchText`). This avoids adding a native PDF engine that cannot be built or validated in this environment.

## 4. Regression risks

- Room migration 3→4 must keep existing rows readable; transfer state strings are persisted, so new states must be additive.
- Viewer routing changes must not break image/video/audio flows or the mini-player queue.
- Batch downloads must not create duplicate TDLib downloads (gateway rejects a second active download for the same TDLib file id).
- WorkManager must not create a second TDLib client; it reuses the singleton gateway.
- Compose gesture changes must preserve system back/navigation.

## 5. Verification constraints (honest)

This sandbox has no Android SDK/NDK, no device/emulator, no Gradle distribution, and no reachable Maven repository. Android/Gradle builds, lint, instrumented tests, and live Telegram flows **cannot** be executed here.

What can be executed: a JVM harness built from the npm-published Kotlin compiler (`kotlin-compiler@2.4.21`) plus a JDK runtime, compiling `core:model` and running its test sources (excluding `MetadataModels.kt`, which needs kotlinx-serialization, and its test). Every new pure-logic component is covered there. Android/gradle results are reported as *not run*, never as passing.

## 6. Implementation sequence (smallest safe order)

1. Audit (this document).
2. Typed error model + mapping + tests.
3. Transfer states, retry bounds, download limits, speed estimator + tests.
4. Offline availability policy, `FileQuery.localOnly`, reconciliation rules + tests.
5. PDF models, page/progress rules, outline parser + tests.
6. Room migration + repository contract.
7. Offline file repository, storage verification and space checks.
8. Download coordinator rewrite, status store, startup reconciliation, WorkManager bounds.
9. File browser actions (row, details, multi-select), Offline library screen, navigation.
10. PDF reader (controller, progress store, screen, routing).
11. App-wide error-handling pass (auth, upload, sync, media, settings, startup).
12. Final validation and report.

## 7. Implementation results

Everything below is on branch `arena/5207da66-teledrive`, commits `510ceaa`..`5fbcf07`
(18 commits over base `780ca8a`), all pushed as a linear history (no rewrites).

| Commit | Content |
| --- | --- |
| `510ceaa` | This audit |
| `d11b2b9` | Typed application error model, category/operation mapping, redaction, tests |
| `7b5ea1d` | PREPARING/VERIFYING states, retry bounds, download limits, speed estimator, tests |
| `03ca924` | Offline availability policy, `FileQuery.localOnly`, reconcile/stale rules, tests |
| `b7f2a55` | PDF rules, tolerant outline parser, reading progress + scroll offset, tests |
| `6f320d8` | Room v4 offline record, `DownloadCoordinator` (bounded pool, verify, cancel), `DownloadStatusStore`, `StartupReconciler`, bounded drain worker |
| `5b77e78` | File browser/download/transfer UI: per-file state, bulk multi-select with partial report, offline library filter, cache clear |
| `45384ee` | PDF reader: `PdfDocument`, `PdfReaderViewModel`, `PdfReaderScreen`, `pdf/{stableId}` route, progress persistence |
| `7687479` | Overall batch progress from real completed files; search cancellation check |
| `de0cdcb` | `cancelAllNow()` before destructive cache clear, player failure reporting, receive-loop and storage logging, `Throwable`→`Exception` |
| `9aa81aa` | Deterministic throughput simulations |
| `de42b60` | Metadata append failures no longer reported as failed local actions |
| `30663f7` | Published copy probed for readability/size before COMPLETED |
| `ffcf06a` | `tools/jvm-verify` harness so the evidence is reproducible |
| `4f5703f` | This results section |
| `38c4657` | Flood-wait delays parsed from TDLib's natural "retry after N" text |
| `cdb00fa` | Unexpected transfer exceptions are no longer auto-retried |
| `5fbcf07` | Startup settlement skips in-process transfers; direction-neutral message |

### Executed verification

```bash
# Syntax/lexical parse of every Kotlin file in app/ and core/ (no Android deps needed)
kotlinc -d /tmp/syntax-out $(find app/src/main/java core -name '*.kt') 2>&1 \
  | grep -iE "expecting|syntax|unexpected token|unclosed|incorrect|lexical|redeclaration"
# -> no matches (unresolved Android/AndroidX references are expected and filtered out)

# Model layer + tests on a plain JVM
JAVA_HOME=/path/to/jdk KC=/tmp/kit/package bash tools/jvm-verify/run-model-tests.sh
# -> RESULT passed=102 failed=0
#    persistence: naive=1200 throttled=120 ticks=1200
#    scheduling:  downloads=6 workers=3 serial=24000ms bounded=8000ms
#    estimator:   reported=2097144B/s true=2097152B/s error=0.0%
```

The three printed numbers come from deterministic policy simulations
(`TransferThroughputSimulationTest`), not from a device. No TDLib transfer was measured
in this environment, so no absolute speed claim is made.

### Not executed (environment)

`./gradlew assembleDebug`, `testDebugUnitTest`, `lint`, Room instrumented tests, and any
on-device flow (offline PDF/image/video/audio opening, real Telegram download/cancel) were
**not run**: there is no JDK for Android, no Android SDK/NDK, no emulator, and Maven
Central/Google Maven are unreachable. Compilation of the Android modules is therefore
unverified; every Android-side claim in this document is a code-level statement.
