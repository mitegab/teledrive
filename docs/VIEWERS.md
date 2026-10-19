# Media viewers — implementation notes

## External-open diagnosis (confirmed 2026-10-19)

| Suspect | Verdict | Evidence |
| --- | --- | --- |
| VIEW intent loses its URI | **Confirmed** | `app/src/main/java/com/teledrive/files/FileOpenRouter.kt`: the code constructed `Intent(ACTION_VIEW, uri)` then assigned `type`; `Intent.setType()` clears the data URI. It now uses `ViewerIntents.view()` and `setDataAndType()`. |
| Package visibility pre-check rejects installed apps | **Confirmed** | `FileOpenRouter.open()` queried `queryIntentActivities(intent, 0)` and treated an empty result as definitive. Target SDK is 36 (`app/build.gradle.kts`); no `<queries>` was present in `app/src/main/AndroidManifest.xml`. The query is removed and `ActivityNotFoundException` is handled at launch. |
| Unknown extension falls back to `*/*` | **Confirmed** | `FileOpenRouter.resolveMimeType()` returned `*/*` for unrecognized extensions, producing ambiguous VIEW intents. It now returns no MIME match; callers receive an actionable export error. Sharing an unknown type retains `application/octet-stream`. |

## Current scope

External VIEW/Share intent construction is isolated in `ViewerIntents`, uses a URI-scoped read grant and ClipData, and MIME lookup uses metadata unless it is generic, then falls back to the filename extension. FileProvider input remains gated by `LocalFileStorage.isAvailable`. ## Built-in viewer implementation

The authenticated navigation graph now has a `viewer/{stableId}` full-screen destination. `DefaultViewerRouter` is a pure decision policy tested against useful MIME metadata, generic MIME fallback, uppercase extensions, and unknown types. Image browsing receives the image IDs from the active filtered file list.

- Images are sampled after a bounds-only decode (dimension/pixel-count cap), EXIF rotations are applied, gestures support pinch/pan/double-tap, and image pages are swipeable. API 28+ GIF and WebP are decoded as bounded `Drawable`s and animated where supported; API 26–27 uses the bounded static bitmap path.
- Video and audio use Media3 ExoPlayer 1.11.1. The datasource uses file/content handling with a `FileDataSource` base and does not install an HTTP datasource. Audio includes seek, ±10 second, play/pause and speed controls; noisy-route and audio focus handling are enabled. Video uses the Media3 `PlayerView`, landscape sensor orientation, pauses on background and keeps the screen awake only during playback.
- Viewers use `DownloadCoordinator` before reading bytes, expose a cancel control while downloading, and only get the coordinator's validated app-managed path. Playback errors and unsupported built-in types show Open in another app and Export actions.
- ExifInterface 1.4.1 is used for orientation metadata. Media3 modules are AndroidX (Apache 2.0); neither adds network access. We use bounded platform bitmap decoding rather than an image-loader/network dependency.

Verification in this workspace is limited: Gradle cannot start because Java is not installed. No device/Compose/live Telegram verification is claimed. Region-based zoom for exceptionally huge images and PDF/text rendering are outside this iteration; such types retain an explicit external-app/export fallback.
