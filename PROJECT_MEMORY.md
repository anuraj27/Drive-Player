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
| **DI** | `app/src/main/java/com/driveplayer/di/AppModule.kt` | Singleton provider for OkHttp, Retrofit, Repository, Auth. |
| **Navigation** | `app/src/main/java/com/driveplayer/navigation/AppNavigation.kt` | `Screen` sealed class (Login, Browser, Player). Manages back-stack state. |
| **Auth Data** | `app/src/main/java/com/driveplayer/data/auth/GoogleSignInHelper.kt` | OAuth token fetching via `GoogleAuthUtil`. |
| **Drive API** | `app/src/main/java/com/driveplayer/data/remote/DriveRepository.kt` | Folder listing, server-side filtering, pagination, stream URL building (`alt=media`). |
| **Player Setup**| `app/src/main/java/com/driveplayer/player/DriveDataSourceFactory.kt` | Wraps OkHttp client for ExoPlayer. |
| **Player UI** | `app/src/main/java/com/driveplayer/ui/player/PlayerScreen.kt` | Compose UI for player, contains `PlayerControlsOverlay` and gesture logic. |
| **Player VM** | `app/src/main/java/com/driveplayer/ui/player/PlayerViewModel.kt` | Manages ExoPlayer instance, auto-attaches `.srt` files if found in siblings. |
| **Theme** | `app/src/main/java/com/driveplayer/ui/theme/Color.kt` | Dark slate background (`0xFF0D0F14`), electric blue/purple accents. |

## 💡 Important Context
- **No Caching (Yet):** Streaming relies on ExoPlayer's internal forward buffer. Drive CDN is fast enough that skipping forward/backward generally handles well.
- **VLC Gestures:** The player implements advanced Compose `pointerInput` for brightness (left vertical), volume (right vertical), and seeking (horizontal).
- **Android Settings:** Changing brightness requires modifying the `Window` layout parameters of the Activity. Volume uses `AudioManager`.

## 📌 Current State (Phase 2)
- ✅ Project Setup & Auth Flow
- ✅ Google Drive API File Browser
- ✅ ExoPlayer Streaming implementation
- 🚧 Adding VLC-like Gestures & App Icon (In Progress)
