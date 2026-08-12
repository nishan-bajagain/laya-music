# 🎵 Laya Music

<p align="center">
  <img src="assets/banner.jpg" alt="Laya Music" width="100%">
</p>

<p align="center">
  <strong>A lightweight, modern Android music client for YouTube Music.</strong>
</p>

<p align="center">
  <a href="https://developer.android.com/">
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android">
  </a>
  <a href="https://kotlinlang.org/">
    <img src="https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  </a>
  <a href="https://developer.android.com/compose">
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="GPL-3.0">
  </a>
</p>

<p align="center">
  <a href="#-features">Features</a> •
  <a href="#-screenshots">Screenshots</a> •
  <a href="#-technology">Technology</a> •
  <a href="#-installation">Installation</a> •
  <a href="#-contributing">Contributing</a>
</p>

---

## ✨ About

**Laya Music** is a native Android music player built with **Kotlin** and **Jetpack Compose**, designed to provide a clean, fast, and focused YouTube Music experience.

It combines streaming, playlists, downloads, synchronized lyrics, background playback, Android Auto, and local caching into a single lightweight application.

> 🎧 **Simple music experience. No unnecessary clutter.**

---

## 🚀 Features

* 🎵 Search and stream music from YouTube Music
* 🔎 Search for songs, albums, artists, and playlists
* ▶️ Background playback
* 📥 Download music for offline playback
* 🎤 Time-synchronized lyrics
* 📝 Plain-text lyrics fallback
* 📚 Playlist creation and management
* ❤️ Personal library, history, and recommendations
* 🚗 Android Auto support
* 🎨 Modern Material 3 interface
* 🌈 Dynamic Android colors
* ⚡ Local caching for a faster experience
* 🔄 Background update checks
* 💾 Offline-friendly lyrics and downloaded music

---

## 📸 Screenshots

<p align="center">
  <img src="assets/home.jpg" width="30%" alt="Home">
  <img src="assets/search.jpg" width="30%" alt="Search">
  <img src="assets/player.jpg" width="30%" alt="Music Player">
</p>

<p align="center">
  <img src="assets/lyrics.jpg" width="30%" alt="Lyrics">
  <img src="assets/library.jpg" width="30%" alt="Library">
  <img src="assets/settings.jpg" width="30%" alt="Settings">
</p>

> **More screenshots:**
> Check the [`assets`](https://github.com/nishan-bajagain/laya-music/tree/main/assets) directory for the complete collection.

---

## 🛠️ Technology

| Component        | Technology                   |
| ---------------- | ---------------------------- |
| Language         | Kotlin                       |
| UI               | Jetpack Compose + Material 3 |
| Playback         | AndroidX Media3 / ExoPlayer  |
| Music extraction | NewPipeExtractor             |
| Local storage    | Room + DataStore             |
| Networking       | OkHttp                       |
| Images           | Coil 3                       |
| Background tasks | WorkManager                  |
| Architecture     | ViewModel + Repository       |
| Minimum Android  | Android 7.0 (API 24)         |

---

## 📱 Installation

### Download

Download the latest APK from the project's **GitHub Releases** page and install it on your Android device.

### Build from source

```bash
git clone https://github.com/nishan-bajagain/laya-music.git
cd laya-music

./gradlew assembleDebug
```

The debug APK will be generated inside:

```text
app/build/outputs/apk/debug/
```

> **Note:** Android Studio with a recent stable Android SDK is recommended for development.

---

## 🏗️ Project Structure

```text
laya-music/
├── app/
│   └── src/
│       └── main/
├── assets/
├── gradle/
├── scripts/
├── .github/
├── LICENSE
└── README.md
```

---

## 🔐 Security

Never commit:

* Keystores
* API keys
* Passwords
* `.env` files containing secrets
* Local configuration containing credentials

For security-related issues, please follow the instructions in [`SECURITY.md`](SECURITY.md).

---

## 🤝 Contributing

Contributions, bug reports, feature requests, and improvements are welcome.

Before submitting a pull request:

1. Test your changes.
2. Make sure the project builds successfully.
3. Keep changes focused and clean.
4. Avoid committing generated files or secrets.

---

## 📄 License

Laya Music is licensed under the **GNU General Public License v3.0**.

See [`LICENSE`](LICENSE) for the complete license.

---

<p align="center">
  Made with ❤️ using Kotlin & Jetpack Compose
</p>

<p align="center">
  <strong>Laya Music</strong> — Your music, your way. 🎧
</p>
