# Laya Music

<p align="center">
  <img src="assets/banner.jpg" alt="Laya Music" width="100%">
</p>

<p align="center">
  A native Android client for listening to YouTube Music with a focused, lightweight interface.
</p>

<p align="center">
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"></a>
  <a href="https://developer.android.com/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="GPL-3.0"></a>
</p>

## Overview

Laya Music is built with Kotlin and Jetpack Compose for Android. It provides YouTube Music search, streaming playback, playlists, downloads, lyrics, Android Auto support, and a local cache for frequently used data.

The project is designed to keep the playback experience responsive while retaining a conventional Android architecture that is easy to build, inspect, and extend.

## Features

- Search and stream tracks, albums, and playlists from YouTube Music.
- Background playback through Media3 and a media session.
- Local downloads and offline playback support.
- Account sign-in with access to personal playlists, history, and recommendations.
- Line-synchronised lyrics from LRCLIB, with plain-text lyrics as a fallback.
- Lyrics caching in Room for faster repeat access and offline use.
- Material 3 interface with dynamic colour support.
- Android Auto media controls and voice search handling.
- Playback history synchronisation.
- Playlist creation and local playlist management.
- Background work for downloads and update checks.

## Technology

| Area | Implementation |
| --- | --- |
| Language | Kotlin 2.4.0 |
| UI | Jetpack Compose and Material 3 |
| Build | Android Gradle Plugin 9.3.0 |
| Playback | AndroidX Media3 / ExoPlayer |
| Stream extraction | NewPipeExtractor |
| State and concurrency | Kotlin Coroutines, Flow, and ViewModel |
| Local storage | Room and DataStore |
| Networking | OkHttp through the project data sources |
| Images | Coil 3 |
| Background work | WorkManager |
| Minimum Android version | Android 7.0 / API 24 |
| Target Android version | API 35 |
| Compile Android version | API 37 |

## Project structure

```text
app/
├── src/main/java/        Application and feature source
├── src/main/res/         Android resources and translations
├── src/main/assets/      Bundled runtime assets
├── build.gradle.kts      App module configuration
└── proguard-rules.pro    Release shrinker rules

gradle/                   Version catalog and Gradle wrapper files
scripts/                  Android SDK and local build helpers
.github/workflows/        GitHub Actions build workflow
```

The application package is `ca.ilianokokoro.umihi.music`.

## Requirements

For a local build, install:

- JDK 21
- Android SDK with the API 37 preview platform
- Android SDK build tools
- Git

Android Studio with a recent stable release is recommended. The project also includes command-line scripts for environments where Android Studio is not available.

## Build locally

Clone the repository and enter its directory:

```bash
git clone <repository-url>
cd laya-music
```

Create `local.properties` in the project root and point it to your Android SDK:

```properties
sdk.dir=/path/to/your/android-sdk
```

The file is intentionally ignored by Git. If you use account features that require the YouTube Music API key, add it locally as `ytm.api.key` or provide it through the `YTM_API_KEY` environment variable.

Install the preview platform if it is not already available:

```bash
sdkmanager --channel=3 "platforms;android-37.0"
```

Build a debug APK:

```bash
./gradlew assembleDebug
```

Build a release APK:

```bash
./gradlew assembleRelease
```

The release output is written to:

```text
app/build/outputs/apk/release/laya.apk
```

On Replit or another Nix-based environment, the project scripts can prepare Java and the Android SDK automatically:

```bash
bash scripts/build-debug.sh
bash scripts/build-release.sh
```

The convenience command `build-apk.sh` runs the release build.

## Release signing

Release builds use a configured private keystore when all signing values are present. Local builds read these values from `local.properties`; CI builds read them from environment variables. A local debug-key fallback is available for development and testing when no private release keystore is configured. Do not use that fallback for a public release.

Example local properties:

```properties
keystore.password=...
key.alias=...
key.password=...
```

Never commit these values, the keystore, or a real `.env` file. The provided `.env.example` documents the supported variable names without containing credentials.

## Continuous integration

The GitHub Actions workflow in `.github/workflows/Build.yml` and the root `Build.yml` build release APKs on pushes to `main` and on version tags. The workflow:

1. Installs JDK 21 and the required Android SDK components.
2. Creates the CI-only `local.properties` SDK entry.
3. Builds the release APK with Gradle.
4. Uploads the APK as a workflow artifact.
5. Attaches the APK to a GitHub release for version tags and published releases.

Configure `YTM_API_KEY`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` as repository secrets when a signed public release is required.

## Development notes

The app follows a layered structure:

```text
Compose UI → ViewModel → Repository → Data source / Room
```

Playback is managed centrally through Media3, while feature state is exposed through observable flows. Lyrics use a provider policy that treats LRCLIB as the source for timed lyrics and YouTube Music as the plain-text fallback. Cached results are used before making a network request where appropriate.

Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

## Contributing

Keep changes focused, preserve the existing package structure, and avoid committing generated output or local configuration. Before opening a pull request, run the relevant tests and at least one debug build. For changes that affect playback, downloads, authentication, or lyrics, include the device and Android version used for verification.

## Security

Please do not report security issues in public issue threads. Follow the instructions in [SECURITY.md](SECURITY.md) instead.

## License

Laya Music is distributed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for the full text.
