# Bug-fix plan: uploads, media sync, local storage, viewers

Diagnosis performed against the checked-out code and the **pinned TDLib source**
(`tools/.tdlib-source`, commit `c15d3f5a`, version 1.8.68, `td/generate/scheme/td_api.tl`).
No live Telegram account was used; everything below was derived from the schema and the code.

## Baseline (before this work)

| Check | Result |
| --- | --- |
| `./gradlew lintDebug` | passes |
| `:core:model:test` | 1 failing: `QueryAndFolderRulesTest.filtersFavoritesAndType` (PDF classified as `TEXT`) |
| `:core:domain:test` | 1 failing: `MetadataSyncTest.mapperCanBeUsedWithGatewayWithoutAndroid` (test input does not start with the metadata prefix, so the codec correctly returns `NotMetadata`; the expectation is wrong) |
| `:core:testing:test` | does not compile: module has no JUnit dependency |
| `:core:telegram` tests | none exist; `org.json` is stubbed in Android JVM tests, so the mapper cannot be tested without a real `org.json` |
| Devices | one physical device and one emulator attached (instrumented tests possible) |

## Root causes

### A. "Telegram did not return the uploaded document"

`TdLibJson.extractFile()` looks for a nested object named `file`:

```
"messageDocument" -> safeObject("document")?.safeObject("file")
```

In TDLib the file object is named after the media, not `file`:

```
document ... document:file
video    ... video:file
audio    ... audio:file
animation... animation:file
voiceNote... voice:file
photoSize... photo:file
```

So `toStoredFile()` returns `null` for every non-photo message, and `uploadDocument()` turns
that `null` into `UPLOAD_RESPONSE_INVALID`. Contributing defects in the same flow:

1. The failure is thrown *after* Telegram accepted the message. It is classed retryable, so the
   WorkManager drain re-uploads the file and creates duplicates.
2. `transferByMessageId` is registered only after the `sendMessage` response has been mapped. A
   fast `updateMessageSendSucceeded` can arrive first and be dropped, leaving the waiter pending
   for 30 minutes.
3. Uploads are staged as `upload-<random>.part`, so Telegram stores the document under that name
   and a wrong MIME type.
4. TDLib's `local.path` for an uploaded file points at the staged temp file that is deleted
   afterwards. `toStoredFile()` adopts it as the "downloaded" copy, which is wrong.
5. The earlier `inputDocument` nesting bug (already fixed in `b2308a6`).

### B. Only images synchronise

Same root cause as A. `messagePhoto` is the only content type whose file lives at a path the
mapper actually reads (`photo.sizes[].photo`). Documents, videos, audio, animations and voice
notes are all discarded by `toStoredFile()`. Further gaps:

* Video notes and stickers are not mapped at all; `messageVideoNote` / `messageSticker` fall through.
* Photos always report `image/jpeg` and are named `Photo-<id>.jpg`.
* `FileKindDetector` classifies `application/pdf` as `TEXT`, which pollutes the Text category.
* Live updates (`updateNewMessage`, `updateMessageContent`, `updateDeleteMessages`) are ignored,
  so uploads and edits appear only after a manual full sync.
* A manual sync never reconciles messages deleted in Telegram.
* The schema has no media-category, download-state or thumbnail columns, only a single TDLib file id.

Pagination itself is sound (it advances by the oldest message id of the raw page, so a page with
no files does not end the scan, and an empty page ends it). It is kept, with regression tests.

### C. Local storage

Downloads work but lean entirely on TDLib's own cache directory:

* `localPath` is TDLib's path; clearing TDLib's cache silently breaks the record.
* Availability is never re-verified against the filesystem.
* "Remove local copy" deletes a file TDLib still tracks, so TDLib may report the file as present.
* No app-managed copy, no safe naming, no partial-file handling, no storage-usage reporting.
* `deleteRemote` marks the file DELETED but leaves the local copy.
* `tdlibFileId` is the only way back to the bytes. TDLib file ids are stable only while the file
  database is kept, so a stale id must be recoverable from the message.

### D. Viewers

* `DownloadCoordinator.launchLocalFile` builds `Intent(ACTION_VIEW, uri)` and then sets
  `type = mimeType`. `Intent.setType()` **clears the data URI**, so external viewers receive no file.
* `file_paths.xml` only exposes `files/tdlib-files/`; any other app-managed directory would throw
  `IllegalArgumentException` from `FileProvider`.
* The only built-in viewer is `BitmapFactory.decodeFile` on the full-size image: no downsampling
  (OOM risk), no EXIF orientation, no zoom, and no PDF, audio or video support at all.
* There is no download-then-open flow for remote-only files.

## Plan (one commit per verified increment)

0. Repair the red baseline (test-only/build-config fixes, `FileKindDetector` PDF category).
1. Upload: correct file extraction, register correlation before sending, reconcile
   `updateNewMessage` / `updateMessageSendSucceeded` / `updateMessageSendFailed`, preserve the real
   filename and MIME type, never retry an upload Telegram already accepted.
2. Sync: single `TdMessageMapper` for all media types, generic media schema + Room migration,
   live update handling, deletion reconciliation, library filters and icons.
3. Local storage: `LocalFileStorage` (app-managed, temp-then-publish), download queue states,
   availability verification, SAF export, separate remove-local / export / delete-remote actions.
4. Image viewer, 5. PDF viewer, 6. audio player, 7. video player behind one `FileOpenRouter`.
8. Integration: unit, migration and instrumented tests, lint.
9. Final validation: debug APK, git state, report.

## Known limits (stated up front)

* Live Telegram behaviour cannot be verified here. Tests use TDLib JSON fixtures shaped from the
  pinned schema and a fake gateway; they prove mapping and state handling, not network behaviour.
* Large-file streaming playback is not implemented; playback is from the completed local copy.
