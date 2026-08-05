# Laya Music

A premium, lightweight Material Design YouTube Music player for Android — native Kotlin/Jetpack Compose app.

## Project overview
- **Language**: Kotlin 2.4.0 with Jetpack Compose
- **Min SDK**: 24 · **Target SDK**: 35 · **Compile SDK**: 37
- **Architecture**: Clean Architecture (UI → ViewModel → Repository → DataSource/Room)
- **Package**: `ca.ilianokokoro.umihi.music`

## Building the APK on Replit

If release signing credentials and `laya-release.jks` are present, they are used automatically. Otherwise the release build falls back to the standard local debug key so Replit still produces an installable APK; that fallback is suitable for testing, not public distribution. The `YTM_API_KEY` secret is optional and defaults to an empty value.

Run the release build script (handles Java, SDK, and Gradle automatically):

```bash
bash scripts/build-release.sh
```

Output: `app/build/outputs/apk/release/laya.apk`

### Environment notes
- Java 21 (Zulu) is in the Nix store — `scripts/build-release.sh` locates it automatically.
- Android SDK is installed to `~/android-sdk` on first run by `scripts/setup-android.sh`.
- `platforms;android-37.0` is the correct sdkmanager package name (not `android-37`).
- `gradlew` must be `chmod +x` before first run (already done).
- Signing reads the local-only values from `local.properties`; never commit or document the passwords.
- The `YTM_API_KEY` Replit Secret is injected into `BuildConfig.YTM_API_KEY` at compile time.

## Key features implemented
- **Time-synced lyrics** (Spotify/YTM style) — LRC format with line-by-line highlighting, auto-scroll
- **Lyrics source policy**: LRCLIB is the only synchronized source; YouTube Music is plain-text fallback only
- **Local caching**: Room DB (synced 7 days, plain 24 hours, negative 30 minutes) + in-process memory cache
- **Explicit provider outcomes**: not found, rate limited, server error, network error, malformed response, and success
- **Exponential back-off**: bounded retry on LRCLIB 429/5xx
- **300 ms debounce** before firing lyrics request after track skip
- **Background prefetch** of next track's lyrics
- **Timing offset controls**: ±100 ms adjustment with DataStore persistence
- **Color animation** (active line = primary color, inactive = 35% alpha)
- **Full state coverage**: LoadingCache, LoadingSynced, Synced, Plain, Instrumental, NotFound, NetworkError, RateLimited, Unknown, Error

## User preferences
- Keep the existing project structure and naming conventions
