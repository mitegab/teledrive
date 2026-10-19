# TeleDrive fix report — "Chat not found", broken sync, broken upload

Date: 2026-10-19 (UTC) · Branch: `arena/a15aa970-teledrive` · Base: `485f2b3`

## 1. Diagnosis table

| Suspect | Verdict | Evidence |
| --- | --- | --- |
| 3.1 Saved Messages via `getChat(me.id)` → "Chat not found" | **Confirmed** | `TdLibJsonGateway.kt:172-189` (before fix); `getChat` is offline-only for known chats (td_api.tl:11898); `createPrivateChat` exists (td_api.tl:13706) |
| 3.2 No wait for `authorizationStateReady` | **Confirmed** | `start()` returned immediately (`TdLibJsonGateway.kt:91-112`); `FileLibraryViewModel.synchronize()` had no readiness gate; `TransferDrainWorker.kt:29-33` already used the correct pattern |
| 3.3 Upload loses the file name | **Confirmed** | `UploadCoordinator.kt:195` staged `upload-*.part`; TDLib derives document names from the `inputFileLocal` path; `UploadRequest.displayName` never read |
| 3.4 Empty/short page completes scan + reconciles deletions | **Confirmed** | `SyncFilesUseCase.kt:36-47` (before fix); td_api.tl:12221 documents short pages "even before the end of history" |
| 3.5 Error mapping hides cause / mislabels | **Confirmed** | `GatewayErrorMapper.kt:14` (`contains("CODE"/"AUTH"/"PHONE")`); "Chat not found" mapped to plain `"400"` |
| 3.6 Request-shape drift vs TDLib 1.8.68 | **Confirmed (2 real)** | `inputMessageDocument` shape wrong (td_api.tl:6435 + 6162 require nested `inputDocument`); `checkAuthenticationEmailCode` needs `emailAddressAuthenticationCode` wrapper (td_api.tl:11716 + 89). `sendMessage`, `inputMessageText`, `getChatHistory`, `downloadFile`, `cancelDownloadFile`, `deleteMessages`, `setTdlibParameters` verified correct |
| 3.7 Lifecycle/state leaks | **Confirmed** (state) / **Unverifiable** (live timing) | `tdlibParametersSent` never reset (`TdLibJsonGateway.kt:84,481`); `savedMessagesChat` cleared only in `logOut()`; send-success correlation matches td_api.tl:10775/10781 but live ordering untestable here |

## 2. Changes (files → why)

- `docs/DIAGNOSIS.md` — per-suspect verdicts with citations (added).
- `core/telegram/TdLibRequests.kt` — all TDLib request builders, shapes pinned to td_api.tl (added).
- `core/telegram/SavedMessagesChatResolver.kt` — getChat → createPrivateChat → loadChats resolution, cache, invalidation, one `CHAT_NOT_FOUND` self-heal retry (added).
- `core/telegram/AuthorizationReadiness.kt` — `awaitReadySession` + `TdLibParametersGate` (added).
- `core/telegram/TdLibJsonGateway.kt` — readiness gating, resolver wiring, self-heal wraps, corrected request shapes, lifecycle resets, diagnostics events.
- `core/telegram/TdLibJson.kt` — builders moved out to `TdLibRequests`.
- `core/model/UploadStagingRules.kt` — `staging/<uuid>/<displayName>` layout rules with traversal rejection and legacy `upload-*.part` recovery (added).
- `core/model/FileModels.kt` — `FilenameRules` truncation now keeps extensions.
- `core/model/PaginationModels.kt` — `FilesPage.messageCount` (raw messages per page).
- `core/model/Diagnostics.kt` — redacted structured logger `TeleDriveLog` (tag `TeleDrive`) (added).
- `core/domain/SyncFilesUseCase.kt` — short/empty-page retries, `messagesSeen` guard on reconcile, sync diagnostics.
- `core/domain/GatewayErrorMapper.kt` — explicit `CHAT_NOT_FOUND`/auth-marker classification, separator-normalized matching.
- `app/files/UploadCoordinator.kt`, `app/files/LocalFileStorage.kt` — staging layout adoption + recovery compatibility.
- `app/files/FileLibraryViewModel.kt` — step-aware UI errors.
- `app/TeleDriveApplication.kt` — wires the log sink to logcat.
- Tests: `SavedMessagesChatResolverTest`, `AuthorizationReadinessTest`, `TdLibParametersGateTest`, `TdLibRequestsTest`, `UploadStagingRulesTest`, `TeleDriveLogTest` (new); `GatewayErrorMapperTest`, `SyncFilesUseCaseTest` (extended).
- `docs/BUGFIX_PLAN.md` — session record (updated).

## 3. Verification

- `./gradlew testDebugUnitTest`, `lintDebug`, `assembleDebug` — **could not run**: the Gradle distribution (services.gradle.org) and Maven repositories are unreachable from this sandbox (SSL peer shutdown). No Gradle result is claimed.
- Offline harness (Kotlin 2.4.21 + JDK 25; JUnit-compatible assertions; org.json and kotlinx-serialization compiled from pinned GitHub sources) compiled `core/model`, `core/domain`, `core/telegram`, `core/testing` and ran their real test sources:
  **`RESULT passed=80 failed=0`** (34 pre-existing tests + 46 new/updated).
- Not run: anything requiring the Android SDK/NDK (`app/`, `core/database`, instrumented tests), TDLib native libraries, or a live Telegram account.

## 4. Residual risk (needs a real device + account)

1. **Live send with the corrected `inputMessageDocument`** — steps: log in, upload `report.pdf`, watch it arrive in Saved Messages as `report.pdf`. Log lines: `upload.start file_name=report.pdf`, `tdlib.request type=sendMessage outcome=ok`, `upload.done`.
2. **Cold-DB sync** — steps: fresh install, log in, tap Sync once immediately. Look for `sync.page page=1 ...`, `sync.done complete=true`, and no destructive re-list on the next sync.
3. **Chat resolution** — after login, `chat.resolve path=... chat_id=...` should appear (path=createPrivateChat or getChat), never `chat.resolve path=failed`.
4. **Re-login after logout** — logout, login again; `auth.state` events must reach `authorizationStateReady` again (tests cover the gate; live TDLib ordering is unverified).
5. `updateMessageSendSucceeded`/`Failed` correlation and the 30-minute upload waiter under real networks remain code-verified only.

## 5. Manual test script for the owner

1. Fresh install (or clear app data), launch, complete login with phone + code.
2. Tap **Sync** → the file list populates from Saved Messages; UI shows "Indexed from Saved Messages".
3. Tap **Upload**, pick a PDF named `report.pdf` → it appears in Telegram Saved Messages with the exact name `report.pdf`, and in the app list with the same name.
4. Kill the app, reopen → sync is stable (no files vanish, no "Chat not found").
5. Log out, log in again → Sync and Upload still work.
6. If anything fails: capture logcat with `adb logcat -s TeleDrive` and send those lines (they are redacted; no phone numbers, codes, or paths are included).
