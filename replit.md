# Laya Music

A premium, lightweight Material Design YouTube Music player for Android — native Kotlin/Jetpack Compose app.

## Project overview
- **Language**: Kotlin 2.4.0 with Jetpack Compose
- **Min SDK**: 24 · **Target SDK**: 35 · **Compile SDK**: 37
- **Architecture**: Clean Architecture (UI → ViewModel → Repository → DataSource/Room)
- **Package**: `ca.ilianokokoro.umihi.music`

## Building the APK on Replit

```bash
export JAVA_HOME=/nix/store/2ds1jrzlmx4n08sp7flga5sxf000l2sl-zulu-ca-jdk-21.0.4
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_SDK_ROOT=/home/runner/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export KEYSTORE_PASSWORD=layamusic2026
export KEY_ALIAS=laya
export KEY_PASSWORD=layamusic2026
export GRADLE_OPTS="-Xmx3g -Dfile.encoding=UTF-8"
./gradlew assembleRelease --no-daemon
```

Output: `app/build/outputs/apk/release/laya.apk`

Or just run: `bash build-apk.sh`

### First-time SDK setup (already done — skip if /home/runner/android-sdk exists)
```bash
# Download SDK command-line tools
wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O /tmp/cmdtools.zip
unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools_dir
mkdir -p /home/runner/android-sdk/cmdline-tools/latest
cp -r /tmp/cmdtools_dir/cmdline-tools/. /home/runner/android-sdk/cmdline-tools/latest/

# Install required components
export PATH=$PATH:/home/runner/android-sdk/cmdline-tools/latest/bin
yes | sdkmanager --licenses
sdkmanager "platform-tools" "build-tools;35.0.0" "build-tools;36.0.0"
sdkmanager --channel=3 "platforms;android-37.0"
```

## Key features implemented
- **Time-synced lyrics** (Spotify/YTM style) — LRC format with line-by-line highlighting, auto-scroll
- **Multi-provider fallback**: YTM timedLyricsRenderer → Better Lyrics → LRCLIB
- **Local caching**: Room DB (7 days TTL) + in-process memory cache for instant re-renders
- **Exponential back-off**: 3 retries (1s→2s→4s) on LRCLIB 429/5xx
- **300 ms debounce** before firing lyrics request after track skip
- **Background prefetch** of next track's lyrics
- **Timing offset controls**: ±100 ms adjustment with DataStore persistence
- **Color animation** (active line = primary color, inactive = 35% alpha)
- **Full state coverage**: LoadingCache, LoadingSynced, Synced, Plain, Instrumental, NotFound, Offline, Error

## User preferences
- Keep the existing project structure and naming conventions
