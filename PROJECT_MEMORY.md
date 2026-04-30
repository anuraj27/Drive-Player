# Drive Player — Project Memory

This file is the persistent memory for the AI agent. Read it first; update it as the codebase evolves.

## 🏗 Architecture Overview
- **Language:** Kotlin 2.0
- **UI Framework:** Jetpack Compose (single Activity `MainActivity`)
- **Player Engine:** **libVLC** (`org.videolan.android:libvlc-all:3.6.0`). Replaced ExoPlayer because Media3's TextRenderer crashed on PGS / complex ASS subtitles. libVLC handles every subtitle/codec format the app cares about.
- **Dependency Injection:** Manual singleton (`AppModule`). No Hilt/Dagger.
- **Navigation:** Sealed-class navigation in `AppNavigation.kt`. No Jetpack Navigation Component (avoids serializing rich objects between screens).
- **Authentication:** `play-services-auth` 21 + `GoogleAuthUtil.getToken()` for direct on-device Bearer token. Multi-account aware via per-email cached `GoogleSignInClient`. **Token auto-refresh** is wired centrally — see _Active credentials & 401 refresh_ below.
- **Networking:** Retrofit + OkHttp for the Drive REST API. **For media streaming**, libVLC is given a localhost URL pointing at `DriveAuthProxy` (a tiny socket server that adds the `Authorization: Bearer …` header to outbound requests). Drive returns HTTP 403 on `?access_token=` query params for media downloads, so a header-injecting proxy is necessary.
- **Persistence:** Jetpack DataStore (Preferences) for accounts, downloads, pinned folders, watch history. SharedPreferences for playback positions (small, hot-path read).

## 📂 Key File Map

### Entry & DI
| Path | Purpose |
|---|---|
| `app/src/main/java/com/driveplayer/MainActivity.kt` | Edge-to-edge setup, calls `AppModule.init`. (No PiP — removed.) |
| `app/src/main/java/com/driveplayer/di/AppModule.kt` | Singletons + active credentials store: holds the current OAuth token + email, exposes `refreshAccessToken()`, builds an OkHttp/Retrofit/Drive repo whose interceptor pulls the latest token on every request and whose Authenticator refreshes on 401. |
| `app/src/main/java/com/driveplayer/navigation/AppNavigation.kt` | `Screen` sealed class (`Home`, `Settings`, `LocalPlayer`, `CloudPlayer`). `playerSession` int rotates the player VM key per video so each playback gets a fresh ViewModel. Played downloads carry `LocalVideo.positionKey = "download_<fileId>"` so they resume. **On cold start it reads `SettingsStore.snapshot().defaultHomeTab` once via `runBlocking` to seed the active tab** — subsequent in-session tab changes by the user are NOT clobbered by the preference. |

### Auth & cloud data
| Path | Purpose |
|---|---|
| `data/auth/GoogleSignInHelper.kt` | OAuth Drive-readonly token via `GoogleAuthUtil`. Caches per-email clients for multi-account switching. `invalidateToken(token)` is used by `AppModule.refreshAccessToken()` so a future `getToken()` returns a fresh value. |
| `data/local/AccountPreferences.kt` | DataStore-backed list of `SavedAccount` (email + displayName + id), plus the active account email. |
| `data/SettingsStore.kt` | DataStore-backed singleton for **all** app-wide preferences: default home tab, **theme mode (System/Dark/Light)**, resume toggle, default playback speed, skip duration, network-buffer ms, **default orientation, repeat-one, keep-screen-on, controls auto-hide ms, show-gesture-hints**, **volume boost (1.0..2.0), equalizer enable+preset (libVLC preset index), background audio**, per-gesture toggles (brightness/volume/seek/double-tap/pinch), subtitle defaults (**enabled-by-default**, auto-load, scale 10..400, colour 0xRRGGBB, bg alpha 0..255), Wi-Fi-only downloads, **auto-delete completed downloads after N days (0=never)**, **hardware acceleration (AUTO/DISABLED)**. Exposes `snapshotFlow: Flow<Snapshot>` (live, single emission per change — used by the Settings screen, the player chrome, and the theme system) AND `suspend snapshot()` (one-shot, used by `PlayerController` / `DriveDownloadManager` / `DownloadService.runAutoCleanup` at construction/enqueue/cleanup time). All setters clamp into a documented valid range. Defaults colocated under `SettingsStore.Defaults`. `resetAll()` calls `prefs.clear()` — the `?: Defaults.X` chain in every flow takes care of resetting visible state. |
| `data/remote/DriveApiService.kt` | Retrofit interface — `listFiles(q, fields=…, pageSize, orderBy, pageToken)`. **Fields include `owners(displayName,emailAddress)` and `parents`** (the latter so a `WatchEntry` can later refetch its sibling files). |
| `data/remote/DriveRepository.kt` | `listFolder` (My Drive), `getSharedFiles` (Shared with me), `searchVideos`. `listFolder`/`getSharedFiles` paginate to completion and sort folders-first. **`searchVideos` tokenises** the query on whitespace, builds a `name contains 'tok'` clause per word joined by `and` (so multi-word queries hit any word-prefix in the file name), and **caps pagination at `MAX_SEARCH_PAGES = 5`** (≈ 500 results) to keep latency bounded on huge drives. Both `\\` and `'` are escaped per token. |
| `data/model/DriveFile.kt` | Data class with derived `isFolder`/`isVideo`/`isSrt` and `formattedSize`. Includes `parents: List<String>?`. |

### Local data
| Path | Purpose |
|---|---|
| `data/local/LocalVideo.kt` | `LocalVideo` + `VideoFolder` data classes. `LocalVideo.positionKey: String?` is an explicit override for the resume key (used by played downloads where MediaStore id is `-1`). |
| `data/local/LocalVideoRepository.kt` | MediaStore scan, grouped by folder, sorted by name. Filters out 0-duration entries. **Defensive video-only filter:** in addition to using the `MediaStore.Video.Media` collection (which is already video-typed), we project `MIME_TYPE` and drop entries that are neither `video/*` nor have a known video extension (mp4, mkv, webm, mov, avi, ts, mts, etc.). Catches the rare cases where MediaStore mis-tags an audio container as video and keeps `VideoFolder.videoCount` honest. `searchVideos(q)` does a tokenised AND substring match across the concatenated `title + folderName + path` haystack so a single token can hit any of those fields. |

### Player runtime (libVLC)
| Path | Purpose |
|---|---|
| `ui/player/PlayerScreen.kt` | Compose surface; hosts `VLCVideoLayout` (TextureView mode), gesture controller, overlay, settings panels, sleep timer dialog, brightness window override, lifecycle observer for screen-off, system-bar handling. **Releases the player in onDispose** to avoid leaking libVLC instances per video. Pushes the user's current visual settings into `PlayerController.updateVisualSettings(...)` whenever they change. |
| `ui/player/PlayerViewModel.kt` | Holds `PlayerController`, `SyncController`, `DisplayController`. `startPlaybackOnce()` is invoked AFTER `attachViews(...)` runs in the `AndroidView` factory — calling `play()` before the surface is attached starves libVLC's vout permanently. |
| `ui/player/controllers/PlayerController.kt` | Owns the singleton libVLC `MediaPlayer`. **Idempotent `release()`**. StateFlows for playing/buffering/position/duration/speed/AB-loop/buffer-fill-percent. Runs a localhost `DriveAuthProxy` for cloud sources, fd / file-path branch for local. Persists resumable position into `PlaybackPositionStore` keyed by Drive id (cloud), `local_<MediaStore id>` (local browser), or the explicit `LocalVideo.positionKey` (played downloads). Buffered POSITION is estimated as `mediaPlayer.time + 1500ms` when libVLC reports 100% buffer fill (matches `--network-caching=1500`). Reads the OAuth token from `AppModule.currentAccessToken()` on each upstream connection — no static token captured at construction. `restartWithCurrentOptions()` re-prepares the same media (preserving playhead position via `pendingResumeMs`) so contrast/saturation/subtitle-style changes take effect mid-session. |
| `ui/player/controllers/SyncController.kt` | Audio + subtitle track listing/selection. Uses libVLC's native `audioTrack`/`spuTrack` IDs. Fans out off `PlayerController.addEventListener` because libVLC allows exactly ONE `setEventListener` per MediaPlayer. Audio/SPU delay write directly to `mediaPlayer.audioDelay` / `spuDelay` (microseconds). Subtitle size/color/bg-alpha StateFlows are surfaces for the UI; the values are forwarded to `PlayerController.updateVisualSettings(...)` and applied as per-`Media` options on the next prepare. |
| `ui/player/controllers/DisplayController.kt` | Brightness state (`-1f` = system default). Contrast/saturation StateFlows are surfaces for the UI; values are forwarded into `PlayerController.updateVisualSettings(...)` and applied via libVLC's `:contrast=` / `:saturation=` Media options. The adjust filter is attached **per-Media** (`:video-filter=adjust`) only when the user has actually moved a slider away from neutral — applying it globally produced a runaway "Too high level of recursion (3) / Failed to create video filter 'adjust'" loop on Adreno-class GPUs. |
| `player/DriveAuthProxy.kt` | Localhost HTTP proxy (random port, IPv4) that prepends the OAuth Bearer header (read fresh from `tokenProvider`) and forwards the client's `Range` header. On a 401 it calls `onRefreshNeeded` and retries the upstream request once. One proxy for the video, optionally a second for the subtitle. Stopped in `PlayerController.release()`. |
| `player/PlaybackPositionStore.kt` | SharedPreferences map of fileKey → position ms. Save threshold: 5 s. |
| `player/PlaybackStateStore.kt` | DataStore + JSON map of `fileKey → PlaybackState` (audio/subtitle track id, external `.srt` URI, audio delay µs, subtitle delay µs, playback rate). Sibling to `PlaybackPositionStore`: position is hot (every 5 s, SharedPrefs) so the richer state — written from the same poll loop and on `release()` — lives in DataStore to keep the position write path lean. Restored at `LengthChanged` after the position seek; cleared on `EndReached`. Honoured only when "Resume playback" is on. |
| `player/WatchHistoryStore.kt` | DataStore-backed `WatchEntry` list (cap 20, newest first). Cloud only. Stores `parentFolderId` so a Continue-Watching reopen can refetch siblings. |
| `player/PinnedFolderStore.kt` | DataStore-backed pinned folder list (cap 20). |
| `player/DownloadStore.kt` | DataStore-backed `DownloadEntry` list (status, dmId, bytes, accessToken cached for retry). |
| `player/DriveDownloadManager.kt` | Wraps Android `DownloadManager` with Drive's `?alt=media` URL + Authorization header + per-file destination in `getExternalFilesDir`. |
| `player/DownloadService.kt` | Foreground service that owns the queue advancement + DownloadManager poll loop + reconcile-on-startup. Posts an ongoing progress notification while at least one entry is QUEUED/RUNNING and self-stops when the queue is empty. `START_STICKY` so a kill mid-download is auto-resumed. Its `ACTION_CANCEL_ALL` is wired to the notification's "Cancel all" action. |
| `player/DownloadNotifications.kt` | Notification channel definitions (`drive_downloads_progress` LOW, `drive_downloads_completed` DEFAULT) and builders for the ongoing FGS notification + per-file completion / failure alerts. Uses Android's built-in `stat_sys_download` icons. |
| `player/RecentSearchStore.kt` | DataStore-backed list of recent search queries, partitioned by `Namespace.LOCAL` and `Namespace.CLOUD` (separate histories so a Drive query never appears as a suggestion on the Local tab and vice-versa). Newest-first, capped at `MAX_ENTRIES = 8`, case-insensitive de-duped on insert. |

### Player UI components
| Path | Purpose |
|---|---|
| `ui/player/components/GestureController.kt` | VLC-like gestures: pinch-zoom, double-tap (configurable skip duration), horizontal drag = seek, left-half vertical = brightness, right-half vertical = volume. Volume changes go straight to `AudioManager`. **All five gestures are individually toggleable from Settings → Player gestures**; a disabled gesture is gated at the `pointerInput` block so it's never even detected. The double-tap skip duration is also driven by Settings → Playback → Skip duration (5/10/15/30 s). |
| `ui/player/components/OverlayController.kt` | Top bar (back, title, sleep timer, subs, audio, more), centre play/skip, bottom seekbar w/ buffer indicator behind, lock + rotation lock + aspect-ratio cycle. Shows the actual libVLC buffer-fill percent during buffering. (No PiP button — removed.) |
| `ui/player/components/SettingsController.kt` | Side-panel main menu: speed, resize, subtitles+loop, audio, video filters. Contrast/saturation sliders fire `onContrastCommit` / `onSaturationCommit` on release; PlayerScreen wires those to `PlayerController.restartWithCurrentOptions()`. |
| `ui/player/components/AudioPanel.kt` | Modal sheet: track picker, audio delay slider (±2 s), volume slider. PlayerScreen re-polls system volume when this panel opens so it stays in sync with gesture changes. |
| `ui/player/components/SubtitlePanel.kt` | Modal sheet: enable/disable, track picker, external `.srt` loader, size/colour/bg-alpha/position/delay. Size/colour/bg-alpha each fire a commit callback on release/select; PlayerScreen wires those to `PlayerController.restartWithCurrentOptions()` so the next libVLC `Media` picks up the new options. |
| `ui/player/components/SettingsTab.kt` | Enum for the active settings tab. |

### Browser & home screens
| Path | Purpose |
|---|---|
| `ui/home/HomeScreen.kt` | Bottom navigation: `LOCAL`, `CLOUD`, `DOWNLOADS`. Crossfade between content. |
| `ui/local/LocalBrowserScreen.kt` + `LocalBrowserViewModel.kt` | Permission gate (READ_MEDIA_VIDEO ≥ 33, else READ_EXTERNAL_STORAGE), folder list, video list. **Search bar** (top-bar search icon) with 250 ms debounce → tokenised AND match on title/folder/path; result rows show "duration · size · folder" as the subtitle so users can disambiguate identically-named files. Recent local searches surface as removable chips when the field is blank. |
| `ui/cloud/CloudScreen.kt` + `CloudViewModel.kt` | Drives connection state machine. `connectWith(token, email, …)` writes the active credentials into `AppModule` so the OkHttp interceptor / 401 Authenticator / `DriveAuthProxy` all read the same token. |
| `ui/browser/FileBrowserScreen.kt` + `FileBrowserViewModel.kt` | My Drive / Shared tabs, breadcrumb path, search w/ 350 ms debounce, Continue Watching carousel (root only), Pinned Folders chip row (root only), per-file download icon, account dropdown w/ switch/add/logout. Continue-Watching reopens refetch the parent folder via `repo.listFolder(parentId)` so external `.srt` auto-attach still works. Cloud search results also expose a download icon and refetch siblings on open (so a `.srt` next to the matched file is still picked up by the player). Recent cloud searches surface as removable chips when the field is blank. **The browse list renders folders + videos only**; the repository still pulls `.srt` files into `s.files` (so the player can auto-attach them) but they're filtered out at the render layer — `s.files` is passed through unchanged as the sibling argument to the click handler. |
| `ui/downloads/DownloadsScreen.kt` + `DownloadsViewModel.kt` | Shows queued/running/completed/failed/cancelled. Single-active poll loop (`MAX_CONCURRENT = 1`) advances the queue and refreshes byte progress every 500 ms. Reconciles in-flight DM ids on app start. The "play" callback now passes `(uri, fileId)` so `AppNavigation` can build a stable `LocalVideo.positionKey`. |
| `ui/login/LoginViewModel.kt` | Owned by CloudScreen — silent sign-in, intent result handling, sign-out. (LoginScreen.kt was removed; CloudScreen has its own embedded `ConnectScreen`.) |
| `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt` | App-wide settings, structured as VLC-style sections (**Library, Appearance, Playback, Audio, Player gestures, Subtitles, Downloads, Advanced, Privacy, About**). `SettingsViewModel.state: StateFlow<Snapshot>` collects `SettingsStore.snapshotFlow` so the screen stays live. Equalizer preset list resolved once via `MediaPlayer.Equalizer.getPresetCount()/getPresetName(i)` — only renders when `equalizerEnabled = true`. Privacy actions: clear watch history, clear search history, sign out of all accounts, **reset all settings**. About reads `PackageInfo.versionName` and opens `GITHUB_URL` via an `ACTION_VIEW` intent. The ViewModel exposes a `FALLBACK_SNAPSHOT` companion that mirrors `SettingsStore.Defaults` — reused by `PlayerScreen` for its initial-value seed. |
| `ui/theme/Color.kt` + `Theme.kt` | Two-mode theme (Dark + Light) routed through `LocalAppColors` (`staticCompositionLocalOf`). Top-level color names (`DarkBackground`, `TextPrimary`, …) are exposed as `@Composable @ReadOnlyComposable get()` shims that read the current `AppColors`, so existing call-sites keep compiling unchanged. `DrivePlayerTheme` reads the user's "Theme" preference live (`SettingsStore.themeMode`) and resolves SYSTEM via `isSystemInDarkTheme()`. `DarkOnlyTheme` is a sibling provider used by `AppNavigation` to lock the player route to dark regardless of the global theme — a light chrome over a moving video viewport washes the picture out. |
| `ui/common/TopBarOverflow.kt` | Shared 3-dot overflow `IconButton` + `DropdownMenu` rendered in every Home tab's top bar. Centralised so adding a future menu entry (Help, Send feedback, …) lands in one place rather than three. |

### Theme
| Path | Purpose |
|---|---|
| `ui/theme/Color.kt` / `Theme.kt` / `Type.kt` | Dark slate background `#0D0F14` with electric blue/purple accents. |

## 💡 Important Behavioural Context

### Player lifecycle
- **One libVLC instance per `PlayerController`.** `PlayerController.release()` is idempotent (`isReleased` flag) and is wired into three places: `AndroidView.onRelease` (primary — fires while the surface is still attached, so libVLC's vout can stop cleanly), `PlayerScreen`'s `DisposableEffect.onDispose` (safety net), and `PlayerViewModel.onCleared()` (when the activity dies).
- Without the onRelease/onDispose release, every played video would keep its libVLC + MediaPlayer alive until the activity died — `viewModel(key="player_$session")` keeps an entry in the ViewModelStore for every key.
- The `playerSession` int in `AppNavigation` rotates the key per video so each new video gets a clean ViewModel, regardless of source (local/cloud/download).
- `attachViews(layout, null, false /* useTextureView */, true)` — TextureView mode is critical: the GL texture survives screen-off/on so we don't need to rebuild the MediaCodec pipeline (multi-second freeze otherwise).
- **Crash-on-back hardening:** `PlayerController` keeps a reference to its background polling `pollJob`; `release()` cancels it FIRST and sets `isReleased = true` before freeing the native MediaPlayer/LibVLC. The polling loop also re-checks `isReleased` at the top of every iteration AND wraps each `mediaPlayer.*` call in `try { } catch (_: Exception) { return@launch }`. Without this guard, the loop would resume from `delay(200)` after the native instance was freed and call `mediaPlayer.isPlaying` on a destroyed `VLCObject`, yielding `IllegalStateException: can't get VLCObject instance` and crashing the app on back navigation. All public mutators (`play/pause/seekTo/seekBy/setSpeed/setLoopStart/End/setResizeMode/retryPlayback/loadExternalSubtitle/prepareAndPlay*/restartWithCurrentOptions`) are likewise guarded so a stale callback during disposal can't reach a freed native object.

### Active credentials & 401 refresh
- A single OAuth token + email pair lives in `AppModule.activeToken` / `AppModule.activeEmail`. `CloudViewModel.connectWith(...)` is the only writer for "user signed in"; `disconnect()` and `signOutFromAllAccounts()` clear it.
- `AppModule.buildOkHttpClient()` builds an interceptor that reads the latest token on every request, plus an **Authenticator** that on a 401 calls `refreshAccessToken()` (suspend, coalesced via a Mutex) and retries with the new Bearer header. `priorResponse != null` guard prevents infinite loops.
- `DriveAuthProxy(targetUrl, tokenProvider, onRefreshNeeded)` reads `tokenProvider()` on every upstream request. On a 401 it calls `onRefreshNeeded()` synchronously (`runBlocking { refreshAccessToken() }`) and replays the request once.
- Net result: the user never has to manually re-authenticate during a long browsing or playback session.

### Position persistence
- Cloud media: keyed by Drive `fileId`.
- Local media (LocalBrowser source, real MediaStore IDs ≥ 0): keyed by `local_<id>`.
- Played downloads (synthetic `LocalVideo.id == -1`): keyed by an explicit `LocalVideo.positionKey = "download_<driveFileId>"` so they survive across launches.
- Save threshold: only persists when position > 5000 ms (avoids accidentally saving "0" on instant-pause). Cleared on `EndReached`.
- A `restartWithCurrentOptions()` flow can override the threshold via `pendingResumeMs` so a slider-driven media restart resumes from the exact playhead.

### Per-file player state ("Save media settings")
- Same key as the position store; lives in DataStore (`PlaybackStateStore`) as a JSON `Map<key, PlaybackState>`. Two stores instead of one because position writes are hot (every 5 s) and benefit from SharedPreferences' synchronous path; the rest of the state writes through DataStore + JSON, which is fine at the same cadence but would be wasteful for the hot position path.
- Persisted fields: audio track id, subtitle track id (or external `.srt` URI), audio delay (µs), subtitle delay (µs), playback rate. Stored as `Int`/`Long`/`Float` directly mirroring libVLC's API to avoid lossy conversions.
- Capture points: every 5 s in `PlayerController.pollJob` alongside the position write, plus a final flush in `PlayerController.release()` (uses `GlobalScope`/`Dispatchers.IO` because `release()` itself is sync — safe because `PlaybackStateStore` has no other dependencies on the controller).
- Restore points: at `LengthChanged`, after the position seek. `PlayerController.applyRestoredState(...)` writes track ids, delays, and rate directly to libVLC (audio/sub track writes for unknown ids are silently ignored, so a stale state from an older release of the same file gracefully falls back to the file's defaults). It then fans out to `PlayerViewModel.onStateRestored`, which forwards the delay values into `SyncController`'s StateFlows so the in-player Audio/Subtitle delay sliders show the restored offsets.
- Subtitle restore semantics: `subtitleTrackId == -1` means "user explicitly disabled" — `applySubtitleDefault` skips its global-toggle logic when a state has been restored (`hasRestoredSubtitleChoice = true`) so the per-file choice always wins over the "Subtitles enabled by default" toggle. `subtitleTrackId == -2` means "no record yet" (e.g. brand-new entry) and is left alone.
- Restore is gated by the `resumePlayback` setting: a user who turned off "Resume playback" doesn't expect any per-file state to come back either.
- Cleared on `EndReached` (alongside the position) — once a video has played through, the next session starts clean.

### Default subtitle handling (smart-fallback)
- `SettingsStore.subtitlesEnabledByDefault` interacts with libVLC's auto-pick rules (1. `default=1` track from the file, 2. `--sub-language` match, 3. nothing). Many community rips don't set the default flag, so libVLC's "nothing" branch is hit and the user — who asked for subtitles to be on — sees no subs.
- `applySubtitleDefault()` (called on the `Playing` event) reconciles:
  - Toggle OFF → forces `spuTrack = -1`, overriding any default the file picked.
  - Toggle ON  → keeps libVLC's pick if it found one; otherwise picks the first non-`-1` entry from `mediaPlayer.spuTracks` (libVLC always inserts a synthetic "Disable" track at id == -1; we filter it out).
  - When a per-file state was restored, both branches are skipped — the user's saved choice for THIS file always wins over the global default.

### Watch history (Continue Watching)
- Cloud only. Saved every 5 s during playback. Deduplicated by `fileId`, capped at 20 entries.
- `WatchEntry.parentFolderId` is captured at save time. When the user reopens the entry from the carousel, `FileBrowserScreen.openWatchEntry` calls `repo.listFolder(parent)` to fetch siblings, so an external `.srt` in the same folder is still auto-attached. Old entries (no parent) fall back to the empty-siblings path.

### Drive auth proxy
- Cleartext to `127.0.0.1` is allowed via `network_security_config.xml`; all *real* traffic is HTTPS to `googleapis.com`.
- One proxy for the video, optionally a second for the subtitle. Both stopped in `PlayerController.release()`.
- The proxy's token provider is a lambda that reads `AppModule.currentAccessToken()`, so a refresh elsewhere automatically reaches the streaming side without restarting the proxy.

### Buffer indicator
- libVLC fires `MediaPlayer.Event.Buffering` with `event.buffering ∈ [0, 100]` (network buffer fill, NOT absolute byte position).
- We expose this as `bufferingPercent: StateFlow<Float>` and show it in the centre of the buffering spinner.
- `bufferedPosition` is best-effort: when the buffer reports 100% fill, we set it to `mediaPlayer.time + 1500 ms` (matching `--network-caching=1500`). libVLC has no API for absolute buffered bytes.

### Visual settings (subtitles + adjust filter)
- The adjust filter is enabled **per-Media on demand**: `applyVisualOptions()` adds `:video-filter=adjust` plus the `:contrast=…` / `:saturation=…` options ONLY when the user has actually moved those sliders away from neutral (`|value - 1| > 0.01`). Default playback gets neither the filter nor the options.
- Why on-demand: enabling `--video-filter=adjust` globally on `LibVLC` (the previous approach) makes libVLC insert the adjust filter into the output pipeline for every video, and on Adreno-class GPUs the chroma converter chain can't satisfy the filter's input format. The result is logcat spam ("Too high level of recursion (3)" / "Failed to create video converter" / "Failed to create video filter 'adjust'") and a starved vout. Per-Media activation only impacts videos where the user actually wants the adjustment.
- Subtitle styling uses `:sub-text-scale=N` (10..400, 100 = default), `:freetype-color=0xRRGGBB`, and `:freetype-background-opacity=0..255`. These are always added because they're cheap and have no chroma-conversion side-effects.
- All these options are per-`Media` — libVLC 3.x cannot mutate them on a live stream. The UI updates the in-memory state immediately, and `restartWithCurrentOptions()` rebuilds the same `Media` (preserving playhead via `pendingResumeMs`) when the user releases the slider so the change becomes visible without losing position.

### Downloads
- Single concurrent download (`MAX_CONCURRENT = 1`). Queue order is FIFO by `enqueuedAt`.
- The queue/poll loop now lives inside `DownloadService` (a foreground service), NOT in the ViewModel. This is what lets downloads keep advancing — and queued #2 actually start — while the app is closed or the user is on another screen. The VM (`DownloadsViewModel : AndroidViewModel`) is a pure UI binder: it observes the `DownloadStore` flow combined with `AppModule.liveDownloadProgress` and forwards user actions.
- Service is started from three places (all idempotent via `ContextCompat.startForegroundService`): `MainActivity.onCreate` if any non-terminal entries exist, `FileBrowserViewModel.downloadFile` after enqueuing, and `DownloadsViewModel.retry`. The service self-stops as soon as the queue is empty.
- Bytes are written into `AppModule.liveDownloadProgress: MutableStateFlow<Map<fileId, (downloaded, total)>>` every ~500 ms (no DataStore writes on the hot path); the VM combines that with the store flow to render the progress bar.
- Status transitions (queued → running → completed/failed/cancelled) and the FINAL byte counts at termination ARE persisted to DataStore so they survive app restart.
- Notifications: an ongoing foreground notification on the LOW-importance "Downloads in progress" channel shows the active file's title + percent + "X in queue" subtext, with a "Cancel all" action wired through `ACTION_CANCEL_ALL`. Each completed file gets a one-shot alert on the DEFAULT-importance "Downloads completed" channel; failures get the same channel with an error icon. Tapping a completion notification deep-links into the Downloads tab via `MainActivity.ACTION_OPEN_DOWNLOADS` → `AppModule.requestedHomeTab` (consumed by `AppNavigation`).
- The Android `DownloadManager` ALSO shows its own system notification per file (`VISIBILITY_VISIBLE_NOTIFY_COMPLETED`); the in-app FGS notification is for queue-level visibility while ours actively manages the queue.

### Notification permission
- `POST_NOTIFICATIONS` is requested at first launch on Android 13+ via `MainActivity.notifPermissionLauncher`. Refusal is non-fatal: downloads still run, the user just doesn't see the live progress notification.
- The two notification channels are created idempotently in `DownloadService.onCreate()` (and again on first use elsewhere if needed) — calling `createNotificationChannel` for an existing channel is a no-op and preserves user-customised channel settings.

### Multi-account
- `GoogleSignInHelper.clientCache` keeps a `GoogleSignInClient` per email so the account picker can be skipped when switching to a known account.
- `getAccessTokenForEmail(email)` works via `AccountManager` for *any* Google account on the device — doesn't require an active GSI session, which is what makes silent reconnect across accounts reliable.
- `signOutCurrentClient()` is invoked before launching the sign-in intent for "add account" so Google Play Services shows the picker instead of pre-selecting the current user.

### Search

#### Cloud (Google Drive)
- 350 ms debounce in `FileBrowserViewModel.setSearchQuery`.
- Repository tokenises on whitespace and emits one `name contains 'tok'` clause per word, joined with `and`. Drive's `name contains` is **case-insensitive word-prefix** matching, so `"summer beach 2023"` matches *Summer Vacation Beach 2023.mp4* even though no contiguous substring matches the full phrase.
- Pagination capped at 5 pages (`MAX_SEARCH_PAGES`) — first page is the most relevant under Drive's default ordering, and unbounded pagination on a 50k-file account would hang the UI for ~10 s otherwise.
- A successful, non-empty search records the query into `RecentSearchStore[CLOUD]` (so typos and zero-result queries don't pollute the chip row).
- On a result tap, the screen calls `vm.fetchSiblingsFor(file)` (= `repo.listFolder(file.parents.first())`) before navigating, so external `.srt` auto-attach still works for files reached via search. The fetch is best-effort: a failure or missing parent still opens the player with empty siblings.
- Result rows expose the per-file download icon (parity with browse).

#### Local (MediaStore)
- 250 ms debounce in `LocalBrowserViewModel.setSearchQuery`.
- `LocalVideoRepository.searchVideos` runs a tokenised AND match against the lowercased haystack `title + folderName + path`, so a single token hits any of those fields. Results are returned in MediaStore scan order (DATE_MODIFIED desc).
- A successful, non-empty search records the query into `RecentSearchStore[LOCAL]`.
- Result rows show "duration · size · folder" so identically-named files in different folders are visually distinct.

#### Recents UX
- Both surfaces share `RecentSearchStore` but use separate `Namespace`s (`LOCAL`, `CLOUD`). When the search field is empty, the screen renders a `FlowRow` of `AssistChip`s (newest first, max 8). Tapping a chip refills the query; the trailing × removes a single entry; a top-right "Clear" button wipes the namespace.

### Settings (app-wide preferences)
- **Where it lives:** `SettingsStore` (DataStore Preferences). One file, one snapshot. The Settings screen renders sections that mirror the VLC settings model (Library, Appearance, Playback, Audio, Player gestures, Subtitles, Downloads, Advanced, Privacy, About).
- **Entry point:** A 3-dot overflow menu (`ui/common/TopBarOverflow.kt`) in every Home tab's top bar — Local, Cloud, Downloads — pointing at `Screen.Settings`. Reachable in one tap regardless of sign-in state.
- **Live vs. one-shot reads:**
  - **Live (`snapshotFlow`)** — collected by the player chrome so per-gesture toggles, controls auto-hide ms, keep-screen-on, and background-audio gating take effect *without* closing the video. Used in `PlayerScreen` via `collectAsStateWithLifecycle`. The theme system also subscribes live so flipping Light/Dark in Settings recolours every screen on the next frame.
  - **One-shot (`suspend snapshot()`)** — captured at `PlayerController` construction (default speed, network cache, default subtitle styling, resume toggle, repeat-one, hardware acceleration, equalizer, volume boost, subtitles-enabled-by-default), at every `DriveDownloadManager.enqueue` (Wi-Fi only), and at `DownloadService.runAutoCleanup` (auto-delete days). Changes apply on the *next* prepare / next enqueue / next cleanup pass, matching VLC's "default" semantics.
- **Defaults baked in:** Cloud as default home tab; SYSTEM theme; resume ON; 1.0× speed; 10 s skip; 1500 ms network buffer; AUTO orientation; repeat-one OFF; keep-screen-on ON; 3 s controls auto-hide; gesture hints ON; volume boost 100 %; equalizer OFF (preset index -1); background audio OFF; all gestures ON; subtitles enabled-by-default ON; subtitles auto-load ON; 100 % subtitle scale; white text; 0 % bg; Wi-Fi-only downloads OFF; auto-delete OFF (0 days); hardware acceleration AUTO.
- **Theme system:** `ui/theme/Color.kt` defines `AppColors` (data class) with two instances (`DarkAppColors`, `LightAppColors`). All historical top-level color names are now `@Composable @ReadOnlyComposable get()` shims over `LocalAppColors.current.<field>`, so every existing call-site (`color = TextPrimary`) compiles unchanged. `DrivePlayerTheme` collects `themeMode` live, resolves SYSTEM via `isSystemInDarkTheme()`, and provides the chosen `AppColors` + a matching M3 `colorScheme`. `DarkOnlyTheme` overrides `LocalAppColors` for the player route — putting light chrome over a moving video viewport looks broken.
- **Resume toggle:** `PlayerController` checks `settingsSnapshot.resumePlayback` in the `LengthChanged` listener — when OFF, the auto-seek to the saved 5-second-threshold position is skipped, but `restartWithCurrentOptions()`'s explicit `pendingResumeMs` (used when committing a slider change mid-playback) still fires.
- **Default speed:** Applied in `applyDefaultSpeed()` AFTER `mediaPlayer.play()` because libVLC's `rate` setter only sticks once a stream is active. Updates `_playbackSpeed.value` so the in-player chip reflects the seeded value.
- **Default subtitle styling:** Seeds both `PlayerController.pendingSubtitle*` AND the `SyncController` StateFlows that back the in-player sliders. Without seeding both, the LaunchedEffect that pushes the slider state into `updateVisualSettings(...)` would immediately overwrite the seeded defaults with the panel's hardcoded 100 %/white/50 %.
- **Subtitles enabled-by-default:** When OFF, `applySubtitleDefault()` (called from the `Playing` event) sets `mediaPlayer.spuTrack = -1`. Has to happen post-`Playing` because libVLC doesn't enumerate subtitle tracks until the stream parses. Auto-load (separate toggle) also still gates external `.srt` attachment in `PlayerViewModel.startPlaybackOnce()`.
- **Default orientation:** `PlayerScreen.DisposableEffect` reads the preference once and calls `requestedOrientation = orientationFromPreference(...)`. AUTO ⇒ `FULL_SENSOR`, LANDSCAPE ⇒ `SENSOR_LANDSCAPE`, PORTRAIT ⇒ `PORTRAIT`. The in-player rotation toggle still wins after the user explicitly tilts.
- **Repeat-one:** `PlayerController.loopOne` is now seeded from `settingsSnapshot.repeatOne` so users who turned it on globally don't have to re-flip the in-player toggle. `PlayerScreen.isLooping` is seeded from the same snapshot for visual sync.
- **Keep-screen-on:** Bound to the `VLCVideoLayout`'s `keepScreenOn` property in both the `factory` block (initial state) and the `update` block (live updates) — flipping it in Settings takes effect on the next composition without recreating the view.
- **Controls auto-hide:** The `LaunchedEffect(controlsVisible, isPlaying, ...)` block now reads `userSettings.controlsAutoHideMs` and re-runs whenever the value changes. Default 3 s; selectable as 3 / 5 / 10 s.
- **Volume boost:** `applyVolumeBoost()` calls `mediaPlayer.volume = (snapshot.volumeBoost * 100).toInt()` after `play()` *only* when above 102 %. Below that we leave the OS / AudioManager fully in charge so the physical volume rocker still feels right.
- **Equalizer:** `applyEqualizer()` builds `MediaPlayer.Equalizer.createFromPreset(idx)` from the saved preset index when `equalizerEnabled` is true. The Settings UI dynamically lists presets via `MediaPlayer.Equalizer.getPresetCount()/getPresetName(i)` so we don't have to hard-code names that may change between libVLC builds.
- **Background audio:** `PlayerScreen` captures the preference via `rememberUpdatedState` and the lifecycle observer skips its `pause()` calls when the toggle is on. Audio decoder keeps producing samples after `ON_PAUSE`/`ON_STOP`; foregrounding restores the surface automatically because we use TextureView.
- **Hardware acceleration:** `Media.setHWDecoderEnabled(snapshot.hardwareAcceleration == "AUTO", false)` in both prepare paths. DISABLED forces the software decoder — useful as a fallback on devices with flaky MediaCodec for a particular codec.
- **Auto-hide gesture hints:** `GestureHintsOverlay` renders four chip-styled hints positioned around the video for 4 s after first frame. Composable returns nothing when the toggle is OFF; otherwise the hint fades in via `AnimatedVisibility` and out after the timer.
- **Auto-load subtitles toggle:** Gated in `PlayerViewModel.startPlaybackOnce()`. When OFF, the `.srt` slave is never even attached for cloud playback (the file is also still hidden from the browse list as before — separate concern).
- **Wi-Fi-only downloads:** `DriveDownloadManager.enqueue()` reads the preference inline (`runBlocking { snapshot() }`) so flipping the switch in Settings affects *future* enqueues immediately — no app restart, no manager rebuild. Calls `setAllowedOverMetered(!wifiOnly)` AND `setAllowedOverRoaming(!wifiOnly)`.
- **Auto-delete completed downloads:** `DownloadService.onCreate` launches `runAutoCleanup()` which: (1) reads `autoDeleteDownloadsDays`, (2) selects COMPLETED entries with `completedAt < now - N*86_400_000`, (3) deletes the on-disk file (file:// or content:// uri), (4) removes the DataStore entry. `completedAt` was added to `DownloadEntry` as a nullable Long (back-compat: null = "skipped by cleanup until next finish"); set in both `pollOne()` (live promotion) and `reconcile()` (cold-start sweep).
- **Privacy actions:** Four irreversible buttons, each behind a destructive `AlertDialog`. "Clear watch history" → `WatchHistoryStore.clearAll()`. "Clear search history" → `RecentSearchStore.clear(LOCAL)` + `clear(CLOUD)`. "Sign out of all accounts" → `AccountPreferences.clearAllAccounts()` + `GoogleSignInHelper.signOut()` + `AppModule.clearActiveCredentials()`. **"Reset all settings" → `SettingsStore.resetAll()`** which `prefs.clear()`s the entire DataStore — the `?: Defaults.X` chain rebuilds visible state automatically. Watch / search history / accounts are explicitly NOT touched (separate DataStores).
- **About:** Version pulled from `PackageManager.getPackageInfo(...).versionName`. GitHub link opens via `ACTION_VIEW` intent with `FLAG_ACTIVITY_NEW_TASK`. The link itself is hard-coded as `GITHUB_URL` constant — change in one place if the repo ever moves.

## 📌 Current State
- ✅ Multi-account Google Sign-In with cached per-email clients.
- ✅ **Automatic OAuth token refresh** on 401 — both Retrofit/OkHttp and the libVLC streaming proxy.
- ✅ Google Drive browser (My Drive + Shared with me + tokenised search w/ 350 ms debounce, sibling-aware open, recent-search chips).
- ✅ Local video search w/ 250 ms debounce — tokenised AND match across title / folder / path, recent-search chips.
- ✅ Pinned folders (long-press in browser).
- ✅ Continue Watching carousel — refetches siblings on reopen so external `.srt` auto-attach works.
- ✅ Per-file Download to local storage with queue, retry, cancel, delete.
- ✅ **Background downloads** — a foreground `DownloadService` keeps the queue advancing while the app is closed, with a live progress notification + per-file completion alerts.
- ✅ Local video browser (MediaStore, folder grouping).
- ✅ libVLC playback for local + cloud + downloaded videos.
- ✅ VLC-like gestures (pinch zoom, double-tap, drag seek/brightness/volume).
- ✅ Audio panel (track picker, ±2 s delay, volume slider).
- ✅ Subtitle panel (track picker, external `.srt`, ±5 s delay, runtime size/colour/bg-alpha via media restart).
- ✅ Resume playback for cloud, local, **and played downloads**.
- ✅ Playback speed 0.25–3 ×, A-B loop, single-track loop.
- ✅ Sleep timer (Off/15/30/45/60 min).
- ✅ Rotation lock + manual rotate.
- ✅ Buffer fill % indicator (real libVLC value).
- ✅ Owner shown on Shared-with-me file rows.
- ✅ Idempotent player release on screen exit (no per-video VM leak, no native crash on back navigation).
- ✅ Contrast / saturation now actually affect the picture (libVLC adjust filter attached per-Media on demand + media restart). Default playback no longer triggers adjust-filter recursion errors on Adreno GPUs.
- ✅ **App-wide Settings screen** (Library / Appearance / Playback / Audio / Gestures / Subtitles / Downloads / Advanced / Privacy / About) reachable from a 3-dot overflow on every Home tab. Backed by a single `SettingsStore` (DataStore) with live (`snapshotFlow`) and one-shot (`snapshot()`) readers. Live-wired into the **theme system** (System/Dark/Light, with the player route locked to dark via `DarkOnlyTheme`), `PlayerController` (default speed, network cache, resume toggle, default subtitle styling, **repeat-one, hardware acceleration, equalizer preset, volume boost, subtitles enabled-by-default**), `PlayerScreen` (**default orientation, keep-screen-on, controls auto-hide ms, gesture-hints overlay, background audio**), `GestureController` (per-gesture toggles + skip duration), `PlayerViewModel` (auto-load subtitles), `DriveDownloadManager` (Wi-Fi only), `DownloadService` (**auto-delete completed downloads after N days**), and `AppNavigation` (default home tab). Privacy section wipes watch / search history, signs out of all accounts, and **resets every preference to defaults**; About surfaces version + GitHub link.

## ⚠️ Known Limitations
- **Visual-setting changes briefly re-prepare the media.** The user sees a sub-second flicker when contrast/saturation/subtitle styling is committed, because libVLC 3.x cannot mutate those options live. The playhead is preserved via `pendingResumeMs` so position is not lost.
- **PiP has been removed.** If desired in a future revision it would need a fresh implementation (manifest `supportsPictureInPicture`, `enterPictureInPictureMode`, surface re-attach on exit, lifecycle handling).
- **Background playback is opt-in.** Lifecycle observer pauses on `ON_PAUSE` by default; users who turn on "Background audio" in Settings keep audio running while the activity is backgrounded. There is no `MediaSessionService` / lock-screen controls integration yet — only the in-app surface controls are exposed.
