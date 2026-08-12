<div align="center">
  <img src="assets/banner.jpg" alt="Laya Music Banner" width="100%">

  # Laya Music

  *A native Android client for streaming YouTube Music with a focused, lightweight, and beautiful interface.*

  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"></a>
  <a href="https://developer.android.com/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="GPL-3.0"></a>
</div>

---

## 📸 Screenshots

<!-- NOTE: Replace 'screen1.png', 'screen2.png', etc., with your actual image file names in the 'assetss' folder -->
<div align="center">
  <img src="https://raw.githubusercontent.com/nishan-bajagain/laya-music/main/assetss/screen1.png" width="24%" alt="Screenshot 1">
  <img src="https://raw.githubusercontent.com/nishan-bajagain/laya-music/main/assetss/screen2.png" width="24%" alt="Screenshot 2">
  <img src="https://raw.githubusercontent.com/nishan-bajagain/laya-music/main/assetss/screen3.png" width="24%" alt="Screenshot 3">
  <img src="https://raw.githubusercontent.com/nishan-bajagain/laya-music/main/assetss/screen4.png" width="24%" alt="Screenshot 4">
</div>

---

## ✨ Key Features

Laya Music prioritizes a responsive playback experience while retaining a clean, maintainable architecture.

*   **🎧 Seamless Streaming:** Search and stream tracks, albums, and playlists directly from YouTube Music.
*   **💾 Offline Mode:** Download your favorite music for local playback.
*   **🎤 Synchronized Lyrics:** Real-time, line-synced lyrics powered by LRCLIB, with plain-text fallback and Room caching.
*   **🎨 Material You:** Beautiful Material 3 interface with dynamic color theming.
*   **🚗 Android Auto:** Full media controls and voice search handling for your daily commute.
*   **👤 Account Sync:** Sign in to access personal playlists, history, and tailored recommendations.
*   **⚡ Background Playback:** Reliable background audio powered by Media3 and ExoPlayer.

## 🛠️ Technology Stack

| Component | Technology |
| :--- | :--- |
| **Language** | Kotlin 2.4.0 |
| **UI** | Jetpack Compose & Material 3 |
| **Playback** | AndroidX Media3 / ExoPlayer |
| **Concurrency** | Coroutines & Flow |
| **Storage** | Room & DataStore |
| **Networking**| OkHttp & Coil 3 (Images) |
| **Extraction**| NewPipeExtractor |
| **SDK Specs** | Min: API 24 (Android 7.0) / Target: API 35 |

## 🚀 Getting Started

### Prerequisites
*   JDK 21
*   Android SDK (API 37 preview platform)
*   Android Studio (Recommended)

### Build Locally

1. **Clone the repository:**
   ```bash
   git clone [https://github.com/nishan-bajagain/laya-music.git](https://github.com/nishan-bajagain/laya-music.git)
   cd laya-music
