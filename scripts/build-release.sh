#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build-release.sh — Set JAVA_HOME, install SDK components, build release APK.
# ---------------------------------------------------------------------------
set -euo pipefail

# ── 1. Locate Java 21 (nix store, not on PATH by default in Replit) ────────
if ! command -v java &>/dev/null; then
    NIX_JAVA=$(ls /nix/store 2>/dev/null \
        | grep -E '^[a-z0-9]+-zulu-ca-jdk-21\.' \
        | head -1)
    if [ -z "$NIX_JAVA" ]; then
        NIX_JAVA=$(ls /nix/store 2>/dev/null \
            | grep -E '^[a-z0-9]+-openjdk-21' \
            | grep -v 'debug\|headless' \
            | head -1)
    fi
    if [ -n "$NIX_JAVA" ] && [ -f "/nix/store/${NIX_JAVA}/bin/java" ]; then
        export JAVA_HOME="/nix/store/${NIX_JAVA}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
        echo "==> JAVA_HOME: ${JAVA_HOME}"
    else
        echo "ERROR: Java 21 not found in nix store." >&2
        exit 1
    fi
else
    echo "==> Java: $(command -v java)"
fi

java -version 2>&1

# ── 2. Install Android SDK ─────────────────────────────────────────────────
bash "$(dirname "$0")/setup-android.sh"

# ── 3. Export SDK env vars ─────────────────────────────────────────────────
export ANDROID_HOME="${HOME}/android-sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"

# ── 4. Build release APK ───────────────────────────────────────────────────
echo ""
echo "==> Building release APK..."
./gradlew assembleRelease \
    --no-daemon \
    --stacktrace \
    -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=512m"

echo ""
echo "✅  Release APK built successfully."
find app/build/outputs/apk/release -name "*.apk" 2>/dev/null && \
    echo "APK location: app/build/outputs/apk/release/"
