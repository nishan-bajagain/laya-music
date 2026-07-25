---
name: Android SDK Build Setup
description: How to set up the Android build environment on Replit and build the APK
---

## Rule
Android SDK 37 is required by the library dependencies (Compose BOM 2026.06.01 and newer AndroidX). It is only available via `--channel=3` in sdkmanager, not the default stable channel.

**Why:** The project uses AGP 9.3 with `compileSdk = release(37)`. Public (stable) sdkmanager only goes to 35. Channel 3 (beta/preview) exposes `platforms;android-37.0`.

## How to apply
Every shell session needs these env vars:
```
export JAVA_HOME=/nix/store/2ds1jrzlmx4n08sp7flga5sxf000l2sl-zulu-ca-jdk-21.0.4
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_SDK_ROOT=/home/runner/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
```

First-time SDK setup (after wiping /home/runner):
```
sdkmanager "platform-tools" "build-tools;35.0.0" "build-tools;36.0.0"
sdkmanager --channel=3 "platforms;android-37.0"
```

Build command: `./gradlew assembleRelease --no-daemon` or `bash build-apk.sh`

Output APK: `app/build/outputs/apk/release/laya.apk` (~5.9 MB, signed)

Keystore credentials are in `build-apk.sh` (already embedded — no secrets needed).
