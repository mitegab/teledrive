# Media viewers — implementation notes

## External-open diagnosis (confirmed 2026-10-19)

| Suspect | Verdict | Evidence |
| --- | --- | --- |
| VIEW intent loses its URI | **Confirmed** | `app/src/main/java/com/teledrive/files/FileOpenRouter.kt`: the code constructed `Intent(ACTION_VIEW, uri)` then assigned `type`; `Intent.setType()` clears the data URI. It now uses `ViewerIntents.view()` and `setDataAndType()`. |
| Package visibility pre-check rejects installed apps | **Confirmed** | `FileOpenRouter.open()` queried `queryIntentActivities(intent, 0)` and treated an empty result as definitive. Target SDK is 36 (`app/build.gradle.kts`); no `<queries>` was present in `app/src/main/AndroidManifest.xml`. The query is removed and `ActivityNotFoundException` is handled at launch. |
| Unknown extension falls back to `*/*` | **Confirmed** | `FileOpenRouter.resolveMimeType()` returned `*/*` for unrecognized extensions, producing ambiguous VIEW intents. It now returns no MIME match; callers receive an actionable export error. Sharing an unknown type retains `application/octet-stream`. |

## Current scope

External VIEW/Share intent construction is isolated in `ViewerIntents`, uses a URI-scoped read grant and ClipData, and MIME lookup uses metadata unless it is generic, then falls back to the filename extension. FileProvider input remains gated by `LocalFileStorage.isAvailable`. Built-in image/video/audio full-screen viewers are the next implementation stage.
