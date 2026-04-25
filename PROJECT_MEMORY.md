# Drive Player - Project Memory

This file serves as a persistent memory for the AI agent to quickly understand the project architecture, dependencies, and state without re-reading the entire codebase.

## 🏗 Architecture Overview
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Single Activity `MainActivity`)
- **Player Engine:** `androidx.media3` (ExoPlayer)
- **Dependency Injection:** Manual Singleton (`AppModule.kt`). No Hilt/Dagger.
- **Navigation:** Manual sealed class `Screen` in `AppNavigation.kt`. No Jetpack Navigation component (to avoid object serialization issues).
- **Authentication:** `play-services-auth` (Google Sign-In v21) + `GoogleAuthUtil.getToken()` for direct on-device Bearer token.
- **Networking:** `Retrofit` + `OkHttp`. The *same* `OkHttpClient` instance is shared between Retrofit and ExoPlayer, using an interceptor to inject the `Authorization: Bearer <token>` header automatically.

## 📂 Key File Map
| Component | Path | Description |
|-----------|------|-------------|
| **Entry** | `app/src/main/java/com/driveplayer/MainActivity.kt` | Sets up edge-to-edge, initializes `AppModule`, launches `AppNavigation`. |
| **DI** | `app/src/main/java/com/driveplayer/di/AppModule.kt` | Singleton provider for OkHttp, Retrofit, Repository, Auth, LocalVideoRepository. |
| **Navigation** | `app/src/main/java/com/driveplayer/navigation/AppNavigation.kt` | `Screen` sealed class (Home, LocalPlayer, CloudPlayer). Tab-based navigation (Local/Cloud). |
| **Home UI** | `app/src/main/java/com/driveplayer/ui/home/HomeScreen.kt` | Tab-based home screen with Local/Cloud tabs. |
| **Local Browser** | `app/src/main/java/com/driveplayer/ui/local/LocalBrowserScreen.kt` | MediaStore-based local video browser with folder navigation. |
| **Cloud Browser** | `app/src/main/java/com/driveplayer/ui/cloud/CloudScreen.kt` | Google Drive file browser with auth flow. |
| **Auth Data** | `app/src/main/java/com/driveplayer/data/auth/GoogleSignInHelper.kt` | OAuth token fetching via `GoogleAuthUtil`. |
| **Local Data** | `app/src/main/java/com/driveplayer/data/local/LocalVideoRepository.kt` | MediaStore scanning, folder grouping, local video metadata. |
| **Drive API** | `app/src/main/java/com/driveplayer/data/remote/DriveRepository.kt` | Folder listing, server-side filtering, pagination, stream URL building (`alt=media`). |
| **Player Setup**| `app/src/main/java/com/driveplayer/player/DriveDataSourceFactory.kt` | Wraps OkHttp client for ExoPlayer. |
| **Playback Store**| `app/src/main/java/com/driveplayer/player/PlaybackPositionStore.kt` | SharedPreferences-based playback position persistence. |
| **Player UI** | `app/src/main/java/com/driveplayer/ui/player/PlayerScreen.kt` | Compose UI for player with controllers and gesture logic. |
| **Player VM** | `app/src/main/java/com/driveplayer/ui/player/PlayerViewModel.kt` | Manages ExoPlayer instance, auto-attaches `.srt` files, playback position. |
| **Gesture Ctrl**| `app/src/main/java/com/driveplayer/ui/player/components/GestureController.kt` | VLC-like gestures (seek, brightness, volume, pinch-zoom, double-tap). |
| **Settings Ctrl**| `app/src/main/java/com/driveplayer/ui/player/components/SettingsController.kt` | Player settings panel (audio, subtitle, display). |
| **Theme** | `app/src/main/java/com/driveplayer/ui/theme/Color.kt` | Dark slate background (`0xFF0D0F14`), electric blue/purple accents. |

## 💡 Important Context
- **Dual Video Sources:** App supports both local device videos (via MediaStore) and Google Drive streaming. Local videos use content URIs, Drive uses OkHttp streaming.
- **No Caching (Yet):** Streaming relies on ExoPlayer's internal forward buffer. Drive CDN is fast enough that skipping forward/backward generally handles well.
- **VLC Gestures:** The player implements advanced Compose `pointerInput` for brightness (left vertical), volume (right vertical), seeking (horizontal), pinch-to-zoom, and double-tap seek.
- **Android Settings:** Changing brightness requires modifying the `Window` layout parameters of the Activity. Volume uses `AudioManager`.
- **Playback Persistence:** `PlaybackPositionStore` uses SharedPreferences to save/resume video positions (only saves if >5s to avoid accidental pauses).
- **Permissions:** Local video browsing requires storage permissions (READ_MEDIA_VIDEO for Android 13+, READ_EXTERNAL_STORAGE for older versions).
- **Tab Navigation:** Home screen uses tab-based navigation to switch between Local and Cloud sources, maintaining tab state across navigation.

## 📌 Current State (Phase 4)
- ✅ Project Setup & Auth Flow
- ✅ Google Drive API File Browser
- ✅ ExoPlayer Streaming implementation
- ✅ VLC-like Gestures (seek, brightness, volume, pinch-zoom, double-tap)
- ✅ Local Video Support (MediaStore integration)
- ✅ Tab-based Home Screen (Local/Cloud)
- ✅ Playback Position Persistence
- ✅ Player Settings Panel (audio, subtitle, display)
- ✅ App Icon
- ✅ Picture-in-Picture (PiP) mode — button in overlay, 16:9 aspect ratio
- ✅ Buffering % indicator — shown below spinner during buffering
- ✅ External subtitle loading — file picker for local .srt files
- ✅ Seek direction icon — FastRewind/FastForward based on drag direction
- ✅ Polling loop — while(isActive) with isActive import
- ⚠️ Audio/subtitle delay — stubs only; real impl needs custom AudioProcessor
