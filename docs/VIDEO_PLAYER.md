# Video player

The video workspace is the counterpart of `docs/AUDIO_PLAYER.md`: a full-window listening
surface built on the singleton `PlaybackController`, with an Amplify-styled chrome, a gesture
layer over the picture, a five-tab settings sheet and a library/playlist browser.

```
app/src/main/java/com/teledrive/playback/
├─ video/
│  ├─ VideoPlaybackOptions.kt      pure: aspect/rotation/skip/caption options, frame maths, format labels
│  ├─ VideoMediaState.kt           pure: track descriptors → display options, resume rules, decoder stats
│  ├─ VideoPlaylist.kt             pure: playlists, encode/decode, search/sort/filters, drag moves
│  ├─ VideoLibraryState.kt         pure: everything the library sheet renders
│  ├─ EngineCapabilities.kt        pure: what Media3 can and cannot do, per capability
│  ├─ VideoPlaylistStore.kt        DataStore: named playlists + recently-played order
│  └─ VideoLibraryViewModel.kt     Hilt VM: library, search/sort, favourites, playlists
├─ VideoTrackAdapter.kt            the only Media3 ⇄ plain-model boundary (tracks, formats, overrides)
├─ PlaybackAudioEffects.kt         Equalizer + LoudnessEnhancer on the player audio session
├─ PlaybackController.kt           + track snapshot, video size, audio session, frame stepping, precise seeking
├─ MediaPlayerViewModel.kt         + video workspace pass-throughs
└─ ui/video/
   ├─ AmplifyVideoPlayerScreen.kt  orchestration: state, gestures, PiP, orientation, resume chip, errors
   ├─ VideoSurface.kt              PlayerView, aspect/zoom/stretch, 90° rotation, caption styling
   ├─ VideoChrome.kt               top bar, centre transport, bottom timeline + quick actions, HUD, lock
   ├─ VideoScrubber.kt             custom timeline with frame preview while dragging
   ├─ VideoThumbnails.kt           MediaMetadataRetriever frame loader (mutex + LRU, downscaled)
   ├─ VideoSettingsSheet.kt        Playback / Captions / Audio / Display / Info
   └─ VideoLibrarySheet.kt         Library / Continue / Recent / Favourites / Playlists / Queue
```

`ui/VideoPlayerScreen.kt` is now a thin wrapper so the `video/{stableId}?all={ids}` route and its
callers do not change.

## Controls

| Group | What is available |
| --- | --- |
| Transport | play/pause (also resumes from `ENDED` and re-resolves from `IDLE`), previous/next item, ±skip with a configurable 5/10/15/30 s interval, restart |
| Frame stepping | 1 frame back/forward — pauses, then seeks one frame duration (`1000 / fps`, 33 ms fallback) with `SeekParameters.EXACT` |
| Timeline | tap to seek, drag to preview (frame thumbnail + time bubble, seek committed on release), buffered range, elapsed/remaining toggle |
| Volume | slider + mute in the chrome and in settings; the value is persisted by `PlaybackProgressStore` |
| Full screen | the workspace is full-window; system bars hide while playing with the chrome dismissed |
| Picture in picture | top-bar and bottom-bar buttons call `enterPictureInPictureMode` with the decoded aspect ratio |
| Resume | the controller restores the saved position automatically; a chip offers *Start over* for 7 s |
| Aspect | Fit / Zoom / Stretch mapped to `AspectRatioFrameLayout` `RESIZE_MODE_FIT/ZOOM/FILL` |
| Rotation | 90° steps rendered through a layout that swaps width/height on quarter turns |
| Orientation | Auto / Landscape / Portrait via `requestedOrientation` (restored on exit) |

### Gestures (surface layer)

* tap — toggle chrome
* double tap — seek ∓ the configured skip interval (left/right half), HUD confirms
* horizontal drag — scrub with live frame preview; seek on release
* vertical drag, left half — screen brightness (`WindowManager.LayoutParams.screenBrightness`)
* vertical drag, right half — player volume
* lock button — hides every control except the unlock affordance; back unlocks first

## Settings sheet

* **Playback** — speed (0.25×–2×), volume/mute, skip interval, repeat off/one/all, shuffle,
  restart, resume.
* **Captions** — on/off, automatic vs a specific text track, four text sizes and four palettes
  applied to Media3's `SubtitleView` (embedded ASS styling and font sizes are overridden so
  captions stay legible). Embedded SRT/WebVTT/TTML/ASS/CEA/DVB/PGS tracks are listed; sidecar
  files are not merged by the player.
* **Audio** — audio track selection (multi-language, forced/default badges), stream details
  (codec, channels, sample rate, bitrate, language), and a real **equalizer** (presets plus
  per-band gains from `android.media.audiofx.Equalizer`) with **volume amplification** up to
  +15 dB (`LoudnessEnhancer`). Both attach to the player's audio session and are re-applied when
  the session changes.
* **Display** — decoded size, stream size, frame rate, dynamic range, aspect, rotation,
  orientation, and per-video-track selection when a container carries several.
* **Info** — file name, container, size,duration, MIME, video/audio codec, HDR, track counts,
  decoder pipeline and rendered/dropped frame counters, playback speed.

Every capability the list asks for but the engine cannot expose is stated explicitly in the
sheet (`EngineCapabilities.kt`) instead of a switch that does nothing:

| Capability | Status |
| --- | --- |
| Equalizer, volume amplification | Device dependent (`AudioEffect`) |
| Track selection, frame stepping, aspect/rotation/zoom | Supported |
| Hardware decoding, HDR playback, 60/120 fps | Device dependent |
| Stereo/mono, L/R balance, normalisation, passthrough, A/V sync offset, external audio track | Not exposed by Media3 |
| Colour grading, deinterlacing, denoise, sharpen, upscale | Not exposed by Media3 |

## Library and playlists

The library sheet works on `FileIndexRepository.observeFiles(kinds = VIDEO)`:

* search by filename, sort by name/duration/date/size (tap a field to flip direction)
* favourites (`setFavorite` on the index) and a "continue watching" filter
* **Continue** — anything with a saved position, newest first
* **Recent** — the persisted play order (`VideoPlaylistStore.recents`)
* **Playlists** — create, rename, delete, play, long-press-and-drag to reorder, toggle
  membership per video; persisted in the `video_playlists` Preferences datastore
* **Queue** — the live playback queue with move up/down, remove, clear, shuffle and repeat

Each row shows a lazily extracted frame, extension badge, duration, size, remaining time and a
watch-progress bar. Playback progress per file comes from `PlaybackProgressStore`.

## Formats

Container detection is `FileKindDetector` (MP4, M4V, MKV, WebM, MOV, AVI, FLV, TS, MTS, M2TS,
3GP, WMV, MPG/MPEG, F4V). Codec playback is whatever the device's MediaCodec stack supports —
typically H.264, H.265/HEVC, VP9 and, on newer devices, AV1 and MPEG-4 part 2 — with Media3's
software fallbacks where the app ships them. Audio codecs follow the same rule (AAC, MP3, Opus,
Vorbis, FLAC, AC-3/E-AC-3 on capable devices). The Info tab reports the codec actually parsed
from the container (`Format.sampleMimeType`/`codecs`), so an unsupported file fails visibly with
a mapped `MediaErrorMapper` message instead of silently.

## Platform notes

* `AndroidManifest.xml`: `MainActivity` declares `supportsPictureInPicture="true"` and the
  `configChanges` set that lets rotation, PiP and density changes avoid an activity restart.
* `WAKE_LOCK` is not required: the surface keeps the screen on through `PlayerView`/window flags
  while mounted.
* Entering PiP requires Android 8.0+ (`Build.VERSION_CODES.O`), matching `minSdk 26`.

## Validation

The sandbox has no Android SDK, so verification uses JVM harnesses:

```bash
# 1. pure logic (34 tests: options, media state, playlists)
kotlinc -Xallow-kotlin-package ... app/src/test/java/com/teledrive/playback/video/*.kt

# 2. Compose UI type-check against stubs
kotlinc -cp kotlinx-coroutines-core-jvm.jar <compose stubs> \
  app/src/main/java/com/teledrive/playback/ui/{amplify,video}/*.kt ...

# 3. view-model / store type-check against stubs (coroutines + datastore + hilt)

# 4. Media3 adapter type-check against media3 stubs

# 5. ast-grep Kotlin parse of every touched file
```

All five pass. A device build is still required to confirm hardware decoding, HDR output,
caption rendering and PiP behaviour, which depend on the platform.
