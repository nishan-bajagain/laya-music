#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build-debug.sh — Set JAVA_HOME, install SDK components, build debug APK.
# Run via the "Build Debug APK" Replit workflow.
# ---------------------------------------------------------------------------
set -euo pipefail

# ── 1. Locate Java (nix store, not on PATH by default in Replit) ───────────
if ! command -v java &>/dev/null; then
    NIX_JAVA=$(ls /nix/store 2>/dev/null \
        | grep -E '^[a-z0-9]+-zulu-ca-jdk-21\.' \
        | head -1)
    if [ -z "$NIX_JAVA" ]; then
        NIX_JAVA=$(ls /nix/store 2>/dev/null \
            | grep -E '^[a-z0-9]+-openjdk-21' \
            | head -1)
    fi
    if [ -n "$NIX_JAVA" ] && [ -f "/nix/store/${NIX_JAVA}/bin/java" ]; then
        export JAVA_HOME="/nix/store/${NIX_JAVA}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
        echo "==> JAVA_HOME: ${JAVA_HOME}"
    else
        echo "ERROR: Java not found. Install JDK 21 or set JAVA_HOME." >&2
        exit 1
    fi
else
    echo "==> Java: $(command -v java)"
fi

# ── 2. Install Android SDK ─────────────────────────────────────────────────
bash "$(dirname "$0")/setup-android.sh"

# ── 3. Build debug APK ─────────────────────────────────────────────────────
export ANDROID_HOME="${HOME}/android-sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"

echo ""
echo "==> Building debug APK..."
./gradlew assembleDebug --no-daemon
