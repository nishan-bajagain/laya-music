# Laya Music

<p align="center">
  <img src="assets/banner.jpg" alt="Laya Music Banner" width="100%">
</p>

<p align="center">
  <strong>A premium, lightweight Material Design YouTube Music player for Android.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white&style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white&style=flat-square" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Jetpack-4285F4?logo=jetpackcompose&logoColor=white&style=flat-square" alt="Compose">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License">
</p>

---

**Laya Music** is a native Android music client built around minimalism, speed, and modern Android design patterns. It connects directly to YouTube Music to stream your favorite music without ads or performance overhead.

---

## ✨ Features

- **No Ads, Ever** – Fully ad-free streaming and uninterrupted playback.
- **YouTube Music Integration** – Stream any track, album, or playlist directly from YouTube Music.
- **Offline Downloads** – Cache and download songs for offline playback.
- **Brand Account Support** – Log in with your Google account to access personal playlists, history, and recommendations.
- **Time-Synced Lyrics** – Spotify/YTM-style line-by-line synchronized lyrics with auto-scroll, multi-provider fallback, and offline caching.
- **Dynamic Theming** – Material You with dynamic color palette extraction from album artwork.
- **Hardware-Accelerated Audio** – Optional Audio Offload for improved battery life.
- **Android Auto** – Integrated support for hands-free playback.
- **Playback History Sync** – Plays are reported back to YouTube Music watch history.
- **Built-in Auto-Updater** – Check for and apply updates from within the app.

---

## 🛠️ Built With

- **UI**: [Jetpack Compose](https://developer.android.com/compose) (BOM `2026.06.01`) + Material 3
- **Language**: Kotlin 2.4.0 / JVM Toolchain 21
- **Playback**: [Media3 / ExoPlayer](https://developer.android.com/guide/topics/media/media3) + MediaSession
- **Stream Resolver**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- **Async**: Kotlin Coroutines + Flow
- **Database**: Room 2.8.x (songs, playlists, lyrics cache)
- **Image Loading**: [Coil 3](https://coil-kt.github.io/coil/)
- **Preferences**: Jetpack DataStore
- **Background Work**: WorkManager (downloads, update checks)
- **Crash Recovery**: [CustomActivityOnCrash](https://github.com/Ereza/CustomActivityOnCrash)

---

## 🚀 Getting Started

### Prerequisites

- JDK 21
- Android SDK 37 (preview channel — see note below)
- Android Studio Ladybug or newer

### Cloning & Building Locally

```bash
git clone https://github.com/<your-username>/laya-music.git
cd laya-music
```

Create a `local.properties` file (never committed — see `.gitignore`):

```properties
sdk.dir=/path/to/your/android-sdk
ytm.api.key=YOUR_YTM_API_KEY
keystore.password=YOUR_KEYSTORE_PASSWORD
key.alias=YOUR_KEY_ALIAS
key.password=YOUR_KEY_PASSWORD
```

Then build:

```bash
# Debug APK (no signing required)
./gradlew assembleDebug

# Signed release APK (requires local.properties signing values above)
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/laya.apk`

> **Android SDK 37 note:** SDK 37 ships on the preview channel.
> Install it with:
> ```bash
> sdkmanager --channel=3 "platforms;android-37.0"
> ```

---

## 🔐 Security & Secrets

All secrets are kept out of source control:

| What | Where |
|---|---|
| YTM API key | `local.properties` (local) / `YTM_API_KEY` GitHub Secret (CI) |
| Keystore password | `local.properties` (local) / `KEYSTORE_PASSWORD` GitHub Secret (CI) |
| Key alias | `local.properties` (local) / `KEY_ALIAS` GitHub Secret (CI) |
| Key password | `local.properties` (local) / `KEY_PASSWORD` GitHub Secret (CI) |

- `local.properties` and `*.jks` are excluded by `.gitignore`.
- Secrets are injected into `BuildConfig` at compile time — never appear in Kotlin source.
- See `.env.example` for a full template of required variables.

### Setting Up GitHub Secrets (for CI/CD)

Go to your repository → **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret name | Value |
|---|---|
| `YTM_API_KEY` | Your YouTube Music internal API key |
| `KEYSTORE_PASSWORD` | Password for `laya-release.jks` |
| `KEY_ALIAS` | Key alias in the keystore |
| `KEY_PASSWORD` | Key password |

---

## 🤖 CI/CD (GitHub Actions)

The `Build.yml` workflow runs on every push to `main` and on tag pushes (`v*`):

- **On push to `main`**: Compiles a signed release APK and uploads it as a build artifact.
- **On tag push (`v*`) / GitHub Release**: Additionally creates a GitHub Release and attaches `laya-music-release.apk`.

The workflow automatically:
1. Sets up JDK 21 (Zulu distribution)
2. Installs the Android SDK including the SDK 37 preview platform
3. Injects signing credentials and the API key from GitHub Secrets
4. Builds and signs the release APK

---

## 🛡️ License

Distributed under the GPL-3.0 license.
