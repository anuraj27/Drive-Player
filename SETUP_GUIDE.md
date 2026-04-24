# Google Cloud Console — One-Time Setup Guide

This is a **one-time setup** before you can build and run the app.
Estimated time: 10–15 minutes.

---

## Step 1 — Create a Google Cloud Project

1. Go to https://console.cloud.google.com
2. Click the project dropdown at the top → **New Project**
3. Name it `DrivePlayer` → **Create**
4. Wait for creation, then select the new project

---

## Step 2 — Enable the Google Drive API

1. In the Cloud Console, go to **APIs & Services → Library**
2. Search for `Google Drive API`
3. Click it → **Enable**

---

## Step 3 — Configure the OAuth Consent Screen

1. Go to **APIs & Services → OAuth consent screen**
2. Choose **External** → **Create**
3. Fill in:
   - App name: `Drive Player`
   - User support email: your Gmail
   - Developer contact: your Gmail
4. Click **Save and Continue** (skip Scopes for now)
5. On "Test users" screen → **Add users** → add your own Gmail address
6. Click **Save and Continue** → **Back to Dashboard**

> **Why "External" mode?**  
> Internal requires Google Workspace. External with test users allows only the listed
> Gmail accounts to log in — perfect for personal use.

---

## Step 4 — Create an Android OAuth Client ID

1. Go to **APIs & Services → Credentials**
2. Click **Create Credentials → OAuth client ID**
3. Application type: **Android**
4. Name: `DrivePlayer Android`
5. Package name: `com.driveplayer`
6. SHA-1 certificate fingerprint: (get this from the command below)

### Get your SHA-1 fingerprint (Debug Keystore)

Run this command on your machine:

**Windows (PowerShell):**
```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

Copy the **SHA1** value (looks like `AA:BB:CC:...`)
Paste it into the SHA-1 field in the Cloud Console.

7. Click **Create**

> **Note:** For release builds later, you'll need a separate SHA-1 from your release keystore.

---

## Step 5 — No Client ID in Code Needed

Unlike web OAuth, the Android OAuth client type works by matching your **package name + SHA-1**.
There is **no client ID to paste into the app code**.

The `GoogleSignInOptions` in `AppModule.kt` is already configured correctly.

---

## Step 6 — Open the Project in Android Studio

1. Open Android Studio
2. **File → Open** → select the `online video` folder
3. Wait for Gradle sync to complete (first sync downloads ~200MB of dependencies)
4. Connect a **physical Android device** (API 26+, Android 8.0+)
   - Emulators don't have Google Play Services for real sign-in
5. Click **Run ▶**

---

## Step 7 — First Launch

1. App opens → tap **Sign in with Google**
2. Pick your Google account
3. Grant Drive read-only permission
4. You should see your Drive files listed!

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Sign-in failed: 10` | Wrong SHA-1 in Cloud Console. Re-run keytool and update |
| `Sign-in failed: 12501` | User cancelled or account not in test users list |
| `403 Forbidden` on file list | Drive API not enabled, or wrong scope |
| `401 Unauthorized` during video | Token expired — sign out and sign back in |
| Video buffering forever | Check your internet connection. Large files take 2–5 sec to start |
| `.srt` not loading | Ensure the .srt file is in the same Drive folder as the video |

---

## File Structure Reference

```
d:\projects\online video\
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/
        │   ├── strings.xml
        │   └── themes.xml
        └── java/com/driveplayer/
            ├── MainActivity.kt
            ├── di/AppModule.kt
            ├── navigation/AppNavigation.kt
            ├── data/
            │   ├── auth/GoogleSignInHelper.kt
            │   ├── model/DriveFile.kt
            │   └── remote/DriveApiService.kt / DriveRepository.kt
            ├── player/DriveDataSourceFactory.kt
            └── ui/
                ├── theme/ (Color.kt, Theme.kt, Type.kt)
                ├── login/ (LoginScreen.kt, LoginViewModel.kt)
                ├── browser/ (FileBrowserScreen.kt, FileBrowserViewModel.kt)
                └── player/ (PlayerScreen.kt, PlayerViewModel.kt)
```
