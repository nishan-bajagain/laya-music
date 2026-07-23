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

**Laya Music** (formerly Umihi Music) is a native Android music client designed around minimalism, speed, and modern Android design patterns. Under the hood, Laya connects directly to YouTube Music to scrape, stream, and play your favorite music without intrusive ads or performance overhead. 

---

## ✨ Features

- **No Ads, Ever** – Fully ad-free streaming and clean, uninterrupted playback.
- **YouTube Music Integration** – Stream any track, album, or playlist directly from YouTube Music.
- **Offline Downloads** – Cache and download your favorite songs for offline, local playback.
- **Brand Account Support** – Log in using your YouTube / Google account (including Brand accounts) to fetch personal playlists, history, and recommendations.
- **Dynamic Theming** – Expressive Material Design 3 UI featuring **Dynamic Theme Color Palette Extraction** that adapts the background player UI to match current album artwork.
- **Hardware-Accelerated Audio** – Optional **Audio Offload** to pass decoding tasks directly to dedicated hardware, significantly improving battery life.
- **Android Auto** – Integrated support for Android Auto, allowing you to control playback on the go safely.
- **Built-in Auto-Updater** – Check for and apply updates directly from the app interface.
- **Custom Error Handling** – Integrated crash protection screen to copy diagnostic logs for easy troubleshooting.

---

## 🛠️ Built With (Modern Android Stack)

- **UI Framework**: [Jetpack Compose](https://developer.android.com/compose) (with Compose BOM `2026.06.01`)
- **Language**: Kotlin 2.4.0 (configured with JVM Toolchain Java 21)
- **Local Storage**: [Room Database](https://developer.android.com/training/data-storage/room) for offline song metadata and local playlist indexing
- **Playback Engine**: [Android Jetpack Media3 (ExoPlayer & MediaSession)](https://developer.android.com/guide/topics/media/media3)
- **Data Scraping**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) for light parsing and streaming direct URL resolution
- **Async & Threading**: Kotlin Coroutines and Flows for reactive data streams
- **Image Loading**: [Coil 3](https://coil-kt.github.io/coil/) (with OkHttp networking)
- **Preferences**: [Jetpack DataStore](https://developer.android.com/topic/libraries/architecture/datastore) for persistent key-value configuration
- **Background Tasks**: [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) for reliable background update checks and assets downloading
- **Crash Recovery**: [CustomActivityOnCrash](https://github.com/Ereza/CustomActivityOnCrash) to capture exceptions gracefully

---

## 🚀 Getting Started

### Prerequisites
* JDK 21
* Android SDK 37 (Build Tools `37.0.0`)
* Android Studio (Ladybug or newer recommended)

### Compiling and Running Locally
Clone the repository and compile using the Gradle Wrapper:
```bash
# Clone the repository
git clone https://github.com/nishan-bajagain/laya-music.git
cd laya-music

# Build the debug APK
./gradlew assembleDebug

# Build the signed release APK
./gradlew assembleRelease
```
The compiled release APK will be generated at:
`app/build/outputs/apk/release/laya.apk`

---

## 🤖 Automated Builds & CI/CD Releases

Laya Music uses GitHub Actions to automate release distribution. You don't have to manually compile APKs to share updates.

* **On Push to `main`**: A check runs to ensure that the code compiles cleanly.
* **On Tag Push (`v*`) / GitHub Release**: When you tag a release (e.g. `v1.0.2`), GitHub Actions will:
  1. Boot up a clean Ubuntu runner.
  2. Set up JDK 21 and cache Gradle dependencies.
  3. Compile and sign the release build using the embedded keystore configuration.
  4. Automatically create a new GitHub Release and upload the signed APK (`laya-music-release.apk`) directly to it.

---

## 🛡️ License

Distributed under the MIT License. See `LICENSE` for more information (if applicable).
