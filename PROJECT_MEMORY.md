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
| `app/src/main/java/com/driveplayer/navigation/AppNavigation.kt` | `Screen` sealed class (`Home`, `LocalPlayer`, `CloudPlayer`). `playerSession` int rotates the player VM key per video so each playback gets a fresh ViewModel. Played downloads carry `LocalVideo.positionKey = "download_<fileId>"` so they resume. |

### Auth & cloud data
| Path | Purpose |
|---|---|
| `data/auth/GoogleSignInHelper.kt` | OAuth Drive-readonly token via `GoogleAuthUtil`. Caches per-email clients for multi-account switching. `invalidateToken(token)` is used by `AppModule.refreshAccessToken()` so a future `getToken()` returns a fresh value. |
| `data/local/AccountPreferences.kt` | DataStore-backed list of `SavedAccount` (email + displayName + id), plus the active account email. |
| `data/remote/DriveApiService.kt` | Retrofit interface — `listFiles(q, fields=…, pageSize, orderBy, pageToken)`. **Fields include `owners(displayName,emailAddress)` and `parents`** (the latter so a `WatchEntry` can later refetch its sibling files). |
| `data/remote/DriveRepository.kt` | `listFolder` (My Drive), `getSharedFiles` (Shared with me), `searchVideos`. `listFolder`/`getSharedFiles` paginate to completion and sort folders-first. **`searchVideos` tokenises** the query on whitespace, builds a `name contains 'tok'` clause per word joined by `and` (so multi-word queries hit any word-prefix in the file name), and **caps pagination at `MAX_SEARCH_PAGES = 5`** (≈ 500 results) to keep latency bounded on huge drives. Both `\\` and `'` are escaped per token. |
| `data/model/DriveFile.kt` | Data class with derived `isFolder`/`isVideo`/`isSrt` and `formattedSize`. Includes `parents: List<String>?`. |

### Local data
| Path | Purpose |
|---|---|
| `data/local/LocalVideo.kt` | `LocalVideo` + `VideoFolder` data classes. `LocalVideo.positionKey: String?` is an explicit override for the resume key (used by played downloads where MediaStore id is `-1`). |
| `data/local/LocalVideoRepository.kt` | MediaStore scan, grouped by folder, sorted by name. Filters out 0-duration entries. `searchVideos(q)` does a tokenised AND substring match across the concatenated `title + folderName + path` haystack so a single token can hit any of those fields. |

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
| `ui/player/components/GestureController.kt` | VLC-like gestures: pinch-zoom, double-tap ±10 s, horizontal drag = seek, left-half vertical = brightness, right-half vertical = volume. Volume changes go straight to `AudioManager`. |
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
| `ui/browser/FileBrowserScreen.kt` + `FileBrowserViewModel.kt` | My Drive / Shared tabs, breadcrumb path, search w/ 350 ms debounce, Continue Watching carousel (root only), Pinned Folders chip row (root only), per-file download icon, account dropdown w/ switch/add/logout. Continue-Watching reopens refetch the parent folder via `repo.listFolder(parentId)` so external `.srt` auto-attach still works. Cloud search results also expose a download icon and refetch siblings on open (so a `.srt` next to the matched file is still picked up by the player). Recent cloud searches surface as removable chips when the field is blank. |
| `ui/downloads/DownloadsScreen.kt` + `DownloadsViewModel.kt` | Shows queued/running/completed/failed/cancelled. Single-active poll loop (`MAX_CONCURRENT = 1`) advances the queue and refreshes byte progress every 500 ms. Reconciles in-flight DM ids on app start. The "play" callback now passes `(uri, fileId)` so `AppNavigation` can build a stable `LocalVideo.positionKey`. |
| `ui/login/LoginViewModel.kt` | Owned by CloudScreen — silent sign-in, intent result handling, sign-out. (LoginScreen.kt was removed; CloudScreen has its own embedded `ConnectScreen`.) |

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

## ⚠️ Known Limitations
- **Visual-setting changes briefly re-prepare the media.** The user sees a sub-second flicker when contrast/saturation/subtitle styling is committed, because libVLC 3.x cannot mutate those options live. The playhead is preserved via `pendingResumeMs` so position is not lost.
- **PiP has been removed.** If desired in a future revision it would need a fresh implementation (manifest `supportsPictureInPicture`, `enterPictureInPictureMode`, surface re-attach on exit, lifecycle handling).
- **No background playback.** Lifecycle observer pauses on `ON_PAUSE` to prevent leaked audio behind the lock screen.
