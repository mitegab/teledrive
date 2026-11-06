# Audio player (Amplify workspace)

The audio route (`audio` and `audio/{stableId}?all=…`) renders a single-screen listening
workspace modelled on the Amplify reference design: a persistent rail, a playlist header with
cover art, a sortable/filterable track list, and a waveform timeline that owns transport,
volume and speed controls.

Everything on this screen is driven by real playback and library state. Nothing is a static
mock: the track list is the live queue, the sidebar filters run against embedded audio tags,
and the timeline scrubs the actual ExoPlayer position.

## Layout

| Area | Wide (≥ 880 dp) | Compact (phones) |
| --- | --- | --- |
| Rail | Always visible, 238 dp | Behind a modal drawer (menu button) |
| Header | Title block + 154 dp cover card | Title block + 88 dp cover card |
| Controls | Shuffle / Edit / sort selector | Same, horizontally scrollable |
| List | Fills remaining height | Fills remaining height |
| Timeline | Label, waveform, transport, volume | Label, waveform, transport (volume hidden) |

The previous implementation stacked a centred artwork, a Material slider and two rows of
scattered chips; the mini player also stayed docked under the full player, duplicating
transport controls. Media routes now hide the mini player and the navigation bar, so the
player owns the whole window.

## Sections

- **Discover** – the current playback queue, filtered and sorted. This is the default view.
- **My Library** – every indexed audio file (`FileKind.AUDIO`), newest metadata included;
  tapping a row starts that file and rebuilds the queue from the library context. Rows that
  are already queued show a check instead of an add button.
- **Queue** – the live queue with move up/down and remove controls. Removing the track that is
  currently playing is intentionally not offered.
- **Genres** – the playlist grouped by the genre tag stored in each file; untagged files are
  grouped separately so they are never hidden.

## Mood selector

Mood chips are keyword filters over metadata the file actually carries (genre, album, title,
artist). They never invent a mood attribute:

| Mood | Matches text containing |
| --- | --- |
| Chill | chill, ambient, downtempo, lo-fi, lounge, acoustic, bossa, soft |
| Energetic | house, techno, dance, edm, drum, bass, rock, punk, metal, rap, hip hop, trance |
| Focus | ambient, instrumental, classical, piano, drone, minimal, study, concentration |
| Nostalgic | soul, funk, jazz, blues, oldies, retro, indie, 80s, 90s, vinyl |
| Atmospheric | ambient, cinematic, soundtrack, score, experimental, shoegaze, post-rock, space |
| Late Night | lo-fi, jazz, soul, r&b, trip hop, downtempo, night, dark |

Selecting a mood (or a genre filter) switches the main pane to Discover so the effect is
visible immediately. Empty results are explained with a "Clear filters" action instead of a
blank list.

## Genre filters

The list is built from the embedded `MediaMetadataRetriever.METADATA_KEY_GENRE` tag of each
queued file, with counts. Multiple genres are OR-ed; a mood and a genre selection are AND-ed.
Files without a genre tag only appear when no genre filter is selected.

## Sorting

`Custom` keeps queue order. `Title`, `Artist`, `Duration` and `Date Added` sort a *view* of the
queue: every row keeps its original queue index, so play/remove/reorder actions stay correct
after sorting or filtering.

## Track list behaviour

Each row shows its queue position (or an animated equalizer for the current track), artwork
extracted from the file's embedded picture, title, artist/album/offline state, duration, and an
actions menu:

- **Play now** – jumps straight to that queue index.
- **Remove from queue** – hidden for the row that is currently playing.

Files that are still remote-only carry a download glyph; selecting them resolves (and if
needed downloads) the file before playback, as before.

## Timeline

- **Waveform**: bar heights are a deterministic signature derived from the track id, so the
  same track always renders the same shape. The Telegram file is not pre-analysed for real
  peak data; the played portion, buffered region and playhead are real. Tap or drag anywhere
  to seek; dragging previews the target before committing.
- **Transport**: previous track, back 10 s, play/pause, forward 10 s, next track. The
  mini player keeps the platform "restart after 3 s" behaviour for its previous button; the
  full player always moves in the queue.
- **Volume**: slider plus mute toggle, applied to ExoPlayer immediately and persisted
  (debounced) in the playback DataStore.
- **Speed**: pill opens the speed sheet (0.5×–2×).
- **Repeat / shuffle / queue** shortcuts live on the timeline so they are reachable without
  returning to the playlist header.

## State and errors

- Preparing/downloading a track shows an inline "Fetching the audio from Telegram…" strip.
- Playback errors replace the strip with a card that offers Retry when the failure is
  retryable.
- An empty queue, an empty library and empty filter results each get their own explanation and
  a single next action.

## Supporting changes outside the screen

- `PlaybackItem` now carries `genre` (extracted with the rest of the metadata).
- `PlayerUiState` carries `volume`; `PlaybackController.setVolume`, `seekRelative`,
  `previousTrack` and volume restoration/persistence were added.
- `MediaPlayerViewModel` exposes the audio library flow plus cached artwork decoding, so
  scrolling does not re-run `MediaMetadataRetriever` for every row.
- `PlaybackController.playSingle` now rebuilds the queue when the requested track is not part
  of the current queue, instead of silently replacing the queue with a single item.
- The authenticated scaffold hides the mini player and navigation bar on media routes so the
  player is not duplicated.

## Validation status

- `AmplifyPlaylistTest` covers playlist naming, stats, clock/duration formatting, genre
  buckets, search/genre/mood filters, sort stability of queue indices, offline labels and
  waveform determinism.
- The sandbox used for this change has no Android SDK, so no Gradle build, lint run or APK is
  claimed here. Compose code was type-checked against API stubs and the pure playlist logic was
  compiled and its tests executed on the JVM. Run `./gradlew testDebugUnitTest lintDebug
  assembleDebug` on a machine with the documented toolchain before shipping.

## Accessibility notes

- Icon-only controls (search, sort, transport, row actions, volume) have content descriptions.
- Filters expose their state through text ("Clear", counts) as well as colour.
- The waveform is a seek surface with a redundant numeric position/duration readout, so
  playback position is not conveyed by visuals alone.
