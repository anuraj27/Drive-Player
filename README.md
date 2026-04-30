# Drive Player

A modern Android video player that streams videos directly from your Google Drive and plays local media. Built with Kotlin and Jetpack Compose, powered by libVLC for broad codec/subtitle support.

## 🚀 Features

- **Google Drive Integration**: Browse, search, pin folders, and stream videos directly from your Google Drive (My Drive + Shared with me).
- **Multi-account**: Sign in with multiple Google accounts and switch between them without re-authenticating.
- **Automatic OAuth refresh**: 401 responses transparently refresh the Drive token (both for REST calls and the libVLC streaming proxy) — long sessions never get stuck on expired credentials.
- **libVLC Playback Engine**: Wide codec coverage including HEVC, AV1, complex ASS, and PGS subtitles.
- **Local Library**: MediaStore-backed local video browser with folder grouping.
- **Offline Downloads**: Queue downloads of any Drive video, with progress tracking, retry, and cancel — and resume from where you left off the next time you play them.
- **Continue Watching**: Resume any cloud video right where you left off; reopens refetch sibling files so external `.srt` auto-attach still works.
- **VLC-like Gestures**:
  - Left vertical swipe: Brightness
  - Right vertical swipe: Volume
  - Horizontal swipe: Seek
  - Pinch: Zoom
  - Double-tap left/right: ±10 s skip
- **Player Toolkit**: Speed 0.25×–3×, A-B loop, sleep timer, aspect-ratio cycling, rotation lock.
- **Visual Tuning**: Brightness, contrast, and saturation sliders all affect the picture (libVLC adjust filter); subtitle text size, colour, and background opacity are honoured at runtime.
- **Subtitle Support**: External `.srt` loading + auto-attach for sibling subtitles in the same Drive folder.
- **Modern UI**: Dark theme with electric blue/purple accents using Jetpack Compose.
- **Secure Authentication**: Google Sign-In with OAuth 2.0 and a localhost proxy that injects the Bearer token for libVLC streaming.

## 📱 Screenshots

*(Add screenshots here)*

## 🛠 Tech Stack

- **Language**: Kotlin 2.0
- **UI Framework**: Jetpack Compose (Single Activity)
- **Player Engine**: libVLC (`org.videolan.android:libvlc-all:3.6.0`)
- **Dependency Injection**: Manual Singleton pattern
- **Navigation**: Custom sealed-class navigation
- **Authentication**: Google Sign-In (`play-services-auth` v21) + `GoogleAuthUtil`
- **Networking**: Retrofit + OkHttp; localhost `DriveAuthProxy` for libVLC streaming
- **Persistence**: DataStore (Preferences) + SharedPreferences
- **Coroutines**: Kotlin Coroutines for async operations

## 📋 Requirements

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 26+ (Android 8.0 Oreo)
- Physical Android device (emulators don't support Google Play Services for sign-in)
- Google Cloud Project with Drive API enabled

## 🚦 Setup

### Prerequisites

1. **Create a Google Cloud Project**
   - Go to [Google Cloud Console](https://console.cloud.google.com)
   - Create a new project named `DrivePlayer`
   - Enable the Google Drive API

2. **Configure OAuth Consent Screen**
   - Go to APIs & Services → OAuth consent screen
   - Choose External
   - Fill in app details and add your Gmail as a test user

3. **Create Android OAuth Client ID**
   - Go to APIs & Services → Credentials
   - Create OAuth client ID → Android
   - Package name: `com.driveplayer`
   - Get your SHA-1 fingerprint:
     ```powershell
     keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
     ```
   - Paste the SHA-1 value and create

### Building the App

1. Clone the repository
2. Open in Android Studio
3. Wait for Gradle sync to complete
4. Connect a physical Android device
5. Click Run ▶

### First Launch

1. Tap "Sign in with Google"
2. Select your Google account
3. Grant Drive read-only permission
4. Browse and play your videos!

## 📂 Project Structure

```
app/src/main/java/com/driveplayer/
├── MainActivity.kt                  # Entry point, edge-to-edge bootstrap
├── di/AppModule.kt                  # Manual singleton DI + active OAuth token + 401 refresh
├── navigation/AppNavigation.kt      # Screen sealed class, per-video VM keying
├── data/
│   ├── auth/GoogleSignInHelper.kt   # Multi-account OAuth helper
│   ├── local/                       # AccountPreferences, LocalVideoRepository
│   ├── model/DriveFile.kt           # Data models
│   └── remote/                      # DriveApiService, DriveRepository
├── player/
│   ├── DriveAuthProxy.kt            # Localhost Bearer-injecting proxy for libVLC
│   ├── DriveDownloadManager.kt      # Wraps Android DownloadManager
│   ├── DownloadStore.kt             # DataStore persistence for downloads
│   ├── PinnedFolderStore.kt         # DataStore persistence for pinned folders
│   ├── WatchHistoryStore.kt         # DataStore persistence for Continue Watching
│   └── PlaybackPositionStore.kt     # SharedPreferences resume positions
└── ui/
    ├── theme/                       # Color, Theme, Type
    ├── login/LoginViewModel.kt      # Owned by CloudScreen
    ├── home/HomeScreen.kt           # Bottom nav: Local / Cloud / Downloads
    ├── local/                       # Local browser + ViewModel
    ├── cloud/                       # Cloud connection state machine
    ├── browser/                     # File browser + ViewModel (search, pins, history, downloads)
    ├── downloads/                   # Downloads tab + ViewModel
    └── player/
        ├── PlayerScreen.kt          # Compose surface with VLCVideoLayout
        ├── PlayerViewModel.kt       # Owns the controllers
        ├── controllers/             # PlayerController, SyncController, DisplayController
        └── components/              # GestureController, OverlayController, panels
```

## 🔧 Key Architecture Decisions

- **Manual DI** to keep build size and dependencies minimal.
- **Custom sealed-class navigation** to avoid serializing rich objects (e.g. `DriveFile`, `DriveRepository`).
- **libVLC + localhost proxy** instead of media3/ExoPlayer — Drive rejects `?access_token=` for media downloads, libVLC has no built-in custom-header support, and ExoPlayer crashed on PGS / complex ASS subtitles.
- **TextureView mode** for libVLC — the GL texture survives screen-off cycles, so we don't have to rebuild the MediaCodec pipeline (avoids multi-second resume delay).
- **Idempotent player release on screen exit** — every played video gets a fresh `viewModel(key)`, but the libVLC instance is freed in `PlayerScreen.onDispose()` instead of waiting for `onCleared()`.

## 🐛 Troubleshooting

| Problem | Fix |
|---------|-----|
| Sign-in failed: 10 | Wrong SHA-1 in Cloud Console. Re-run keytool and update |
| Sign-in failed: 12501 | User cancelled or account not in test users list |
| 403 Forbidden on file list | Drive API not enabled, or wrong scope |
| 401 Unauthorized during video | App refreshes the token automatically; if it persists, the account may have lost Drive consent — re-add the account |
| Video buffering forever | Check internet connection. Large files take 2–5 sec to start |
| .srt not loading | Ensure the .srt file is in the same Drive folder as the video |

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 👤 Author

Anuraj - [@anuraj27](https://github.com/anuraj27)

## 🙏 Acknowledgments

- ExoPlayer team for the excellent media player library
- Jetpack Compose team for the modern UI toolkit
- Google Drive API for cloud storage integration

## 📝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
