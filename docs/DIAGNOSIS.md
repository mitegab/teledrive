# TeleDrive fix session — diagnosis note

Date: 2026-10-19 (UTC)
Branch: `arena/a15aa970-teledrive`
Scope: the reported failures — "Chat not found" errors, sync not populating the
file list, and upload not working. Each hypothesis from the fix brief was checked
against the source and against `td_api.tl` from TDLib **1.8.68** at commit
`c15d3f5a5de6e3ba5839822c451152e5e18bb700` (the commit pinned in
`tools/TDLIB_VERSION`).

Verdicts: **confirmed** / **refuted** / **unverifiable**.

## 3.1 "Chat not found" root cause — CONFIRMED

`TdLibJsonGateway.getSavedMessagesChat()` (core/telegram/.../TdLibJsonGateway.kt:172-189)
calls `getCurrentUser()` and then `getChat` with `chat_id = user.id`
(TdLibJsonGateway.kt:175). TDLib's `getChat` (td_api.tl:11898, "Returns information
about a chat by its identifier") only answers for chats the client already knows;
right after login the Saved Messages chat is frequently not loaded yet, so TDLib
answers `error 400 "Chat not found"`. Every file flow
(`getFilesPage` :192, `getMetadataMessagesPage` :222, `uploadDocument` :249,
`cancelTransfer` :360, `sendMetadataRecord` :388) funnels through this method, so
sync, upload and metadata all fail with one error.

`createPrivateChat user_id:int53 force:Bool = Chat` exists in the pinned API
(td_api.tl:13706: "Returns an existing chat corresponding to a given user") and
loads-or-creates the chat without requiring it to be known already.
`loadChats chat_list:ChatList limit:int32` (td_api.tl:11974) is a valid fallback.

## 3.2 No wait for authorization readiness — CONFIRMED

`start()` (TdLibJsonGateway.kt:91-112) creates the client and fires one `getOption`
(:111), then returns. `FileLibraryViewModel.synchronize()`
(app/.../FileLibraryViewModel.kt:89-102) and the upload path invoke gateway methods
immediately; nothing waits for `authorizationStateReady`. Calls issued before the
session is Ready fail with `TDLIB_NOT_STARTED` (request() at TdLibJsonGateway.kt:420),
`error 401`, or "Chat not found". `TransferDrainWorker` (app/.../TransferDrainWorker.kt:29-33)
already waits for readiness, which confirms the pattern is missing elsewhere.

## 3.3 Upload loses the file name — CONFIRMED

`UploadCoordinator.stage()` (app/.../UploadCoordinator.kt:195) writes the picked
content to `File.createTempFile("upload-", ".part", filesDir/staging)` and
`uploadDocument` sends that path as `inputFileLocal` (TdLibJsonGateway.kt:265).
TDLib derives the document file name from the local path, so documents land in
Telegram as `upload-83729.part`. `UploadRequest.displayName` exists
(TelegramStorageGateway.kt:58-64) but is never read by the gateway. Downstream,
`LocalFileStorage.stagedFile` (LocalFileStorage.kt:117-125) hardcodes the flat
`*.part` layout, as do `isStagedSource`, `removeStagedSource`,
`cleanupTemporaryFiles` and the retry gate in `TransferDrainWorker.kt:45`.

## 3.4 Dangerous empty-page handling in sync — CONFIRMED

`getFilesPage` returns `nextCursor = null` when the message array is empty
(TdLibJsonGateway.kt:213-218). `SynchronizeFilesUseCase`
(core/domain/.../SyncFilesUseCase.kt:36-47) treats a null next cursor as end of
history (`complete = true`) and, for a full-history scan, calls
`repository.reconcileRemoteFiles(seenRemoteIds)` (:46) — which marks every remote
file that was not seen as deleted. The pinned `getChatHistory` (td_api.tl:12221)
documents "the number of returned messages is chosen by TDLib and can be smaller
than the specified limit" and supports `from_message_id = 0` with
`only_local = false`, so short/empty answers can occur before history is truly
exhausted (cold local message database). An empty first page (or an empty page
after a short one) can therefore complete a "full scan" with zero or few files and
wrongly reconcile real files away.

## 3.5 Error mapping hides the real cause — CONFIRMED

`GatewayErrorMapper.fromTdlib` (core/domain/.../GatewayErrorMapper.kt:6-21)
classifies by substring: "Chat not found" falls through to `code?.toString()`
(="400"), and `contains("CODE")` / `contains("AUTH")` / `contains("PHONE")` (:14)
mislabels unrelated texts (for example "Wrong type of input document; expected
code ...") as `AUTHENTICATION_ERROR`. There is no `CHAT_NOT_FOUND`/`NOT_READY`
mapping and no self-healing retry around the cached Saved Messages chat.

## 3.6 Request-shape drift against TDLib 1.8.68 — CONFIRMED (two real mismatches)

Verified field-by-field against `td_api.tl` at `c15d3f5a`:

| Call | Repo shape | Pinned API | Verdict |
| --- | --- | --- | --- |
| `sendMessage` | `chat_id, topic_id, reply_to, options, reply_markup, input_message_content` | td_api.tl:12592 same fields | OK (nullable class fields sent as JSON null) |
| `inputMessageDocument` | `document: inputFileLocal`, `thumbnail`, `disable_content_type_detection`, `caption` (TdLibJson.kt:205-210) | td_api.tl:6435 `document:inputDocument caption:formattedText` | **BROKEN** — `thumbnail`/`disable_content_type_detection` moved into `inputDocument` (td_api.tl:6162) and `document` must wrap the `InputFile` |
| `inputMessageText` | `text, link_preview_options, clear_draft` | td_api.tl:6415 same | OK |
| `getChatHistory` | `chat_id, from_message_id, offset, limit, only_local` | td_api.tl:12221 same | OK |
| `downloadFile` | `file_id, priority, offset, limit, synchronous` | td_api.tl:14379 same | OK |
| `cancelDownloadFile` | `file_id, only_if_pending` | td_api.tl:14385 same | OK |
| `deleteMessages` | `chat_id, message_ids, revoke` | td_api.tl:12674 same | OK |
| `setTdlibParameters` | all 15 fields (TdLibJson.kt:226-240) | td_api.tl:11684 same | OK |
| `createPrivateChat` | not used | td_api.tl:13706 `user_id, force` | needed for 3.1 |
| `checkAuthenticationEmailCode` | `code: string` (TdLibJsonGateway.kt:141) | td_api.tl:11716 `code:EmailAddressAuthentication` (`emailAddressAuthenticationCode code:string`, td_api.tl:89) | **BROKEN** — email-code login sends a malformed request |

The `inputMessageDocument` mismatch alone makes every document upload fail (or be
mis-decoded) at the JSON boundary — consistent with "upload does not work".

## 3.7 Lifecycle and state leaks — CONFIRMED (state), UNVERIFIABLE (timing)

- `tdlibParametersSent` (TdLibJsonGateway.kt:84) is set once (:481-482) and never
  reset in `logOut()` (:149-152) or `close()` (:402-410); a later session in the
  same process may never answer `authorizationStateWaitTdlibParameters`.
- `savedMessagesChat` (:85) is cleared only in `logOut()` (:151), not when
  authorization moves to `LoggingOut`/`Closing`/`Closed` (:513-515) for any other
  reason, and never on `close()`.
- The flat 60 s `request()` timeout (:429) is fine for `sendMessage` (TDLib sends
  files asynchronously and reports progress via `updateFile`); **unverifiable**
  without a live account.
- `updateMessageSendSucceeded`/`updateMessageSendFailed` correlation uses
  `old_message_id → message_id` (TdLibJsonGateway.kt:564-598) matching the pinned
  update shapes (td_api.tl:10775, 10781). Correct in code; live ordering is
  **unverifiable** here.

## Additional notes

- The `getChat`-miss behaviour itself is **unverifiable** in this sandbox (no live
  Telegram account); the fix follows the pinned API contract and is covered by
  JSON-fixture tests of the real mapping code.
- No cause outside the listed set was found during this pass; request-shape drift
  in `checkAuthenticationEmailCode` (3.6) is the only extra defect and is folded
  into that fix.

## Environment honesty

`./gradlew testDebugUnitTest` cannot run here: the Gradle wrapper distribution
(services.gradle.org) and Maven repositories are unreachable from this sandbox.
Verification uses an offline harness (Kotlin 2.4.21 compiler + JDK 25) that runs
the real JVM test sources of `core/model`, `core/domain`, `core/telegram`, and
`core/testing` with JUnit-compatible assertions. No live Telegram/Android result
is claimed.
