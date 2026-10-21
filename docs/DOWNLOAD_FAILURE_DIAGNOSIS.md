# Download failure diagnosis — "Download failed. Check your connection and try again."

Date: 2026-10-21 · Branch: `arena/130796c3-teledrive`
Report: every download in TeleDrive failed with
`Download failed. Check your connection and try again.` while the device's
internet worked.

## 1. Where the sentence comes from

The sentence is not produced by the network layer. It was the default text of
`ErrorCategory.DOWNLOAD_FAILED` (`core/model/.../ErrorModels.kt`), the fallback
category for a whole class of unrelated failures. Three independent paths could
print it:

| Path | Code | What it actually meant |
| --- | --- | --- |
| Screens re-classified the coordinator's typed error | `FileLibraryViewModel.retryDownload` / `open`, `ViewerViewModel.load`, `MediaPlaybackRepository.ensureLocalFile`, `PdfReaderViewModel.load`, `TransferViewModel.retry` called `Throwable.toAppError(ErrorOperation.DOWNLOAD)` | `DownloadException` already carries a precise `AppError`; the domain mapper ignored it and produced the default download sentence for **any** exception |
| Session not ready | `awaitReadySession` threw `NOT_READY` for startup timeouts, logged-out and closing sessions; `AppErrorMapper` mapped it to `DOWNLOAD_FAILED` | The TDLib session was not usable (or the user had to log in), which has nothing to do with connectivity |
| Local write / publish failed | `LocalStorageException.toAppError(WRITE_FAILED, PUBLISH_FAILED)` used `ErrorCategory.DOWNLOAD_FAILED` | The copy into app-managed storage, or the final `renameTo`, failed on this device |
| `TDLib` completed without `local.path` | `TdLibJsonGateway.downloadFile` threw `DOWNLOAD_PATH_MISSING`, also mapped to `DOWNLOAD_FAILED` | TDLib never handed the bytes over; retrying the file transfer is what helps |

A persisted `TransferState.FAILED` row with a blank message also rendered the
same sentence (`OfflineModels.kt`, `DownloadSnapshot.statusLabel`).

In other words: **the message proved nothing about the connection**, and it
replaced every storage, session, verification and hand-off cause with the same
accusation. That is the defect that hid the real reason for the failed
downloads.

## 2. Changes

### 2.1 Stop re-classifying typed download failures

- `app/.../files/TransferFailures.kt`
  - `toTransferAppError` now returns `DownloadException.error` unchanged.
  - New `Throwable.toDownloadAppError()` for screens that start a download.
  - New `localOpenFailure()` so opening a saved copy (no handler, provider
    failure) is reported as a local file problem, not a download failure.
- `FileLibraryViewModel`: `retryDownload`, `open` and the `beginDownload`
  completion handler use `toDownloadAppError()`; `open` now downloads and opens
  in two separate failure scopes.
- `ViewerViewModel`, `MediaPlaybackRepository`, `PdfReaderViewModel`: same
  helper, same reason.

### 2.2 Make each cause say what it is (`core/model/ErrorModels.kt`)

- `DOWNLOAD_FAILED` → "The download didn't finish. Try again." and
  `UPLOAD_FAILED` → "The upload didn't finish. Try again." No connection blame.
- New `ErrorCategory.SESSION_NOT_READY` → "Telegram is still getting ready in
  TeleDrive. Try again in a moment."; `NOT_READY` / `TDLIB_NOT_STARTED` map here
  and stay retryable. Logged-out states now throw `NOT_AUTHENTICATED` from
  `awaitReadySession` and map to `AUTHENTICATION_REQUIRED` with "Log in to
  Telegram to continue." (not retryable). `AuthorizationState.Error` maps to
  `AUTHENTICATION_ERROR`.
- `DOWNLOAD_PATH_MISSING` → "Telegram finished downloading but didn't provide
  the file on this device. Try again." (retryable).
- `FILE_REFERENCE_EXPIRED` → "Telegram's link to this file expired. Try again to
  fetch it fresh." (retryable).
- `TRANSFER_ALREADY_ACTIVE` → "This file is already being downloaded…" instead
  of `UNKNOWN`.
- `AppError.logLine()` writes `error_code=` instead of `code=`; the latter was
  scrubbed to `[redacted]` by `TeleDriveLog` (it is an SMS/auth-code key), so
  the failure classification never reached logcat.

### 2.3 Recover a completed download that TDLib reports without a path

- `TdLibRequests.getFile(fileId)` (`td_api.tl: getFile file_id:int32 = File`).
- `TdLibJsonGateway.resolveDownloadedPath()` asks TDLib for the file object once
  when the completion response/update has no `local.path`, and only then fails
  with `DOWNLOAD_PATH_MISSING`. Bytes that are already on disk are no longer
  discarded because an update omitted the path.

### 2.4 Do not treat "size unknown" (0) as an expected size

- `LocalFileStorage.importCompleted` only enforces the size gate when the
  expected size is positive (TDLib documents `file.size = 0` as "unknown";
  a zero-byte source is still rejected as `TRUNCATED`).
- `DownloadCoordinator` derives the expected/verified size from the published
  copy and only stores a positive size, so an unknown size can no longer mark a
  completed file as `CORRUPT_FILE`, and cannot make an offline copy unusable.
- `DownloadCoordinator` now distinguishes "remote message deleted" from "row has
  no Telegram file id" instead of reporting both as "no longer available".

## 3. Verification

Executed in this environment:

```bash
JAVA_HOME=/tmp/jdkpkg/jdk4py/java-runtime KC=/tmp/kit/package \
  bash tools/jvm-verify/run-model-tests.sh
# RESULT passed=106 failed=0
```

New tests (all passing):

- `onlyNetworkFailuresBlameTheConnection` — only `NETWORK_UNAVAILABLE` may
  mention the connection/network; `DOWNLOAD_FAILED`/`UPLOAD_FAILED` may not.
- `aSessionThatIsNotReadyIsNotADownloadOrNetworkFailure` — `NOT_READY` →
  `SESSION_NOT_READY` (retryable, no connection text); `NOT_AUTHENTICATED` →
  `AUTHENTICATION_REQUIRED` (not retryable, asks the user to log in).
- `downloadHandoffAndExpiredReferenceGetConcreteSentences`.
- `logLineKeepsTheFailureClassificationVisible` — the log line contains
  `error_code=DOWNLOAD_PATH_MISSING`, not `[redacted]`.

Syntax/parse check of every changed Kotlin file with the standalone
`kotlinc` 2.4.21 (reports "expecting/unexpected token" style errors only; the
Android/TDLib/kotlinx classpath is unavailable, so this is not a compilation).
`core/model` is fully compiled and its tests executed by the command above.

**Not verified here:** no Android SDK, device or emulator, so the Android
modules (`app`, `core:telegram`, `core:domain`, `core:database`) were not
compiled, no UI test and no real download ran against Telegram.

## 4. Confirming on a device

`TeleDriveApplication` installs a logcat sink (`tag TeleDrive`). On a failed
download the coordinator now records:

```
download.failed retryable=<bool> attempt=<n>
error category=<category> operation=DOWNLOAD retryable=<bool> error_code=<code> cause=<sanitized>
```

`category` / `error_code` identify the real cause
(`LOCAL_FILE_INACCESSIBLE` + `WRITE_FAILED`/`PUBLISH_FAILED`,
`SESSION_NOT_READY` + `NOT_READY`, `DOWNLOAD_FAILED` + `DOWNLOAD_PATH_MISSING`,
`CORRUPT_FILE`, `STORAGE_FULL`, …), and the snackbar shows the matching sentence
instead of the connection text.
