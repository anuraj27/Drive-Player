# Drive Player

A modern Android video player that streams videos directly from your Google Drive. Built with Kotlin and Jetpack Compose, featuring VLC-like gesture controls and ExoPlayer for smooth playback.

## 🚀 Features

- **Google Drive Integration**: Browse and stream videos directly from your Google Drive
- **ExoPlayer Powered**: Smooth video playback with advanced buffering and seeking
- **VLC-like Gestures**: 
  - Left vertical swipe: Brightness control
  - Right vertical swipe: Volume control
  - Horizontal swipe: Seek forward/backward
- **Subtitle Support**: Auto-loads `.srt` subtitle files from the same folder
- **Modern UI**: Dark theme with electric blue/purple accents using Jetpack Compose
- **Secure Authentication**: Google Sign-In with OAuth 2.0

## 📱 Screenshots

*(Add screenshots here)*

## 🛠 Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Single Activity)
- **Player Engine**: androidx.media3 (ExoPlayer)
- **Dependency Injection**: Manual Singleton pattern
- **Navigation**: Custom sealed class navigation
- **Authentication**: Google Sign-In (play-services-auth v21)
- **Networking**: Retrofit + OkHttp with shared client
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
├── MainActivity.kt              # Entry point, edge-to-edge setup
├── di/
│   └── AppModule.kt             # Dependency injection singleton
├── navigation/
│   └── AppNavigation.kt         # Screen sealed class, back-stack management
├── data/
│   ├── auth/
│   │   └── GoogleSignInHelper.kt # OAuth token fetching
│   ├── model/
│   │   └── DriveFile.kt         # Data models
│   └── remote/
│       ├── DriveApiService.kt   # Retrofit API interface
│       └── DriveRepository.kt    # Drive API operations
├── player/
│   └── DriveDataSourceFactory.kt # OkHttp client wrapper for ExoPlayer
└── ui/
    ├── theme/                   # Color, Theme, Type
    ├── login/                   # Login screen and ViewModel
    ├── browser/                 # File browser screen and ViewModel
    └── player/                  # Player screen and ViewModel
```

## 🔧 Key Architecture Decisions

- **Manual DI**: No Hilt/Dagger to keep dependencies minimal
- **Custom Navigation**: Manual sealed class to avoid object serialization issues
- **Shared OkHttp Client**: Same instance used by Retrofit and ExoPlayer with auth interceptor
- **No Caching**: Relies on ExoPlayer's internal forward buffer and Drive CDN speed

## 🐛 Troubleshooting

| Problem | Fix |
|---------|-----|
| Sign-in failed: 10 | Wrong SHA-1 in Cloud Console. Re-run keytool and update |
| Sign-in failed: 12501 | User cancelled or account not in test users list |
| 403 Forbidden on file list | Drive API not enabled, or wrong scope |
| 401 Unauthorized during video | Token expired — sign out and sign back in |
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
