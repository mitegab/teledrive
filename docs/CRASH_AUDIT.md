# TeleDrive crash audit — app killed from unrelated places

Date: 2026-11-06 · Branch: `arena/40ed600f-teledrive2` · Base: `ed872c7`

Report: the app crashes while using assorted features, and the crash is not tied to one
screen. A screen-by-screen patch cannot fix that: the failures are shared infrastructure —
the playback singleton, the thread-safety of shared caches, the coroutine entry points every
view model uses, and the on-disk state the whole app reads at startup.

## 1. Root causes found

| # | Root cause | Where | Why it kills the process |
| --- | --- | --- | --- |
| 1 | `ExoPlayer.Builder.setWakeMode(C.WAKE_MODE_LOCAL)` with **no `WAKE_LOCK` permission** | `PlaybackController.ensurePlayer()`, `AndroidManifest.xml` | `PowerManager.newWakeLock()` throws `SecurityException`; ExoPlayer acquires the wake lock from its playback thread, so the throw has no catch site. Every audio playback start was a crash. |
| 2 | `MediaSessionService` started with `startForegroundService` while playback often never starts | `PlaybackController.ensureService()` / `prepareAndPlay()` | The platform kills the process with `RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()` when the service has no notification within ~5 s. Missing local file / unsupported codec / instant error all produced exactly that. |
| 3 | Two `MediaSession`s for one `ExoPlayer` | `PlaybackController.ensurePlayer()` and `MediaPlaybackService.onCreate()` | The service built a fallback session and the controller built its own; duplicate notification providers and controller registrations surface as `IllegalStateException`s from Media3 callbacks. |
| 4 | Unsynchronised metadata cache | `MediaMetadataExtractor.cache` (`mutableMapOf`, eviction by `cache.keys.take(20)`) | Written from `Dispatchers.IO` by artwork rows, playlist rows and the video library at once → lost writes, `HashMap` corruption, `ConcurrentModificationException` during eviction. |
| 5 | Unguarded `controllerScope.launch { … }` | `PlaybackController.next/previous/previousTrack/addToQueue/addAllToQueue/handleTrackEnded`, progress polling and persistence | Room, DataStore and file-system failures escape a coroutine nobody joins → default handler → process death. `PlaybackProgressStore.setPosition()` writes every 5 s, so a full disk or revoked storage crashed mid-playback. |
| 6 | Unguarded `viewModelScope.launch { controller.playSingle(…) }` | `VideoLibraryViewModel.play/playPlaylist` | `playSingle` resolves, may download and records progress; any throw crashed the app when a video was opened. |
| 7 | Corrupt preferences file | the three top-level `preferencesDataStore(...)` delegates | `CorruptionException` escapes `data.first()` on Android and is fatal — whichever screen read settings or progress first crashed. |
| 8 | Failing flow upstreams collected without a handler | `stateIn(...)` in the media/video view models, `collect { }` in library/settings/transfers/PDF/viewer | An exception inside `stateIn` or a `collect` is an unhandled coroutine exception. A failing index query crashed the screen that happened to subscribe. |
| 9 | Duplicate ids in a queue / nav-argument id list | `PlaybackQueue.setQueue`, `MediaPlaybackRepository.buildQueueFromIds` | `LazyColumn` keys must be unique: duplicate `stableId` rows throw `IllegalArgumentException` from the queue sheet and track lists. |
| 10 | Legacy viewer polled and seeked a released player | `ViewerScreen.MediaViewer` | `while(true) { player.currentPosition }` runs in a composition coroutine after `onDispose` released the player. |
| 11 | `FileProvider.getUriForFile` on a path outside the declared roots | `FileOpenRouter.open` | `IllegalArgumentException` for stale rows written by older builds; only `ActivityNotFoundException` was handled. |
| 12 | `WorkManager.enqueue()` outside `runCatching` at startup | `TeleDriveApplication` | `IllegalStateException` when WorkManager is not initialised takes down the process before the UI settles. |
| 13 | `!!` on nullable derived state | `PlaybackController.ensurePlayer`, `FileOpenRouter`, `ViewerScreen` | Unprovable-at-compile-time nulls become NPEs on the main thread. |

## 2. Changes

- `app/src/main/AndroidManifest.xml` — declares `WAKE_LOCK` with the reason ExoPlayer needs it.
- `playback/PlaybackController.kt` — `safeLaunch()` wrapper for every fire-and-forget task
  (logs `playback.<tag>` and routes to `reportFailure`), single-owner `ensureSession()` /
  `getOrCreateSession()`, service started with `startService` only once an item is actually
  prepared, progress reads/writes guarded, polling stops when the player becomes unreadable,
  `release()` releases session → player → service in that order, no `!!`.
- `playback/MediaPlaybackService.kt` — no player or session of its own; `onGetSession`
  forwards to the controller, notification setup is guarded, null-safe `NotificationManager`.
- `playback/MediaMetadataExtractor.kt` — bounded, synchronised access-order LRU
  (`MAX_CACHE_ENTRIES = 80`) instead of an unsynchronised map.
- `playback/PlaybackProgressStore.kt`, `playback/video/VideoPlaylistStore.kt`,
  `preferences/PreferencesRepository.kt` — `ReplaceFileCorruptionHandler` so an unreadable
  file resets to defaults instead of crashing.
- `support/FlowGuards.kt` (new) — `Flow<T>.guarded(tag, fallback)`: logs the redacted event
  and completes instead of propagating into `stateIn`/`collect`.
- `playback/MediaPlayerViewModel.kt`, `playback/video/VideoLibraryViewModel.kt`,
  `files/FileLibraryViewModel.kt`, `files/pdf/PdfReaderViewModel.kt`,
  `files/viewer/ViewerViewModel.kt`, `settings/SettingsViewModel.kt`,
  `transfers/TransferViewModel.kt` — guarded upstreams, guarded `playSingle`, guarded index
  reads, guarded remote-deletion handling.
- `playback/PlaybackQueue.kt`, `playback/MediaPlaybackRepository.kt` — the queue is
  deduplicated by `stableId` (and the start index re-resolved), the id list for a queue build
  is deduplicated; navigation semantics are unchanged.
- `files/ViewerScreen.kt` — polling loop stops when the player is unreadable, transport
  callbacks cannot throw, no `!!`.
- `files/FileOpenRouter.kt` — provider `IllegalArgumentException` reported as an ordinary
  "cannot open" failure; `mimeType!!` removed.
- `TeleDriveApplication.kt` — WorkManager enqueue cannot abort startup.

## 3. Verification

| Check | Result |
| --- | --- |
| Kotlin parse of every changed file (`kotlinc`, JDK 25) | clean |
| `PlaybackQueue` behaviour tests (dedupe, start-index remap, empty queue, out-of-range start, `next`/`previous`, repeat-ALL wrap, `addToQueue` dedupe) | 16/16 passed |
| Bounded LRU cache under 8 concurrent writers | bounded at 5 entries, no corruption |
| `safeLaunch` label/`return@` pattern (compile replica of the controller code) | compiles |
| `Flow<T>.guarded` signature against a `catch { }` with the real `FlowCollector` receiver | compiles |

Not verifiable in this environment: no Android SDK, Media3, Room, Hilt or Compose artifacts are
available, so the app itself cannot be assembled here; the checks above are the compile-level
and behavioural substitutes.

## 4. Deliberate non-changes

- No blanket `Thread.setDefaultUncaughtExceptionHandler`: swallowing every exception hides
  real failures and leaves the UI in an undefined state. Each fix above logs a redacted event
  and, where the UI can recover, shows an error through the existing state/`reportFailure`.
- `PdfDocument.check/require` stay as internal contracts: every caller bounds the page index
  and runs inside `try/catch`, so the throw is already a recoverable page error.
