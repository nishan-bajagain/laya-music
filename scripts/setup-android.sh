#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-android.sh — Install the Android SDK into ~/android-sdk and write
#                    local.properties so Gradle can find it.
# Run once before building; safe to re-run (skips steps already done).
# ---------------------------------------------------------------------------
set -euo pipefail

ANDROID_HOME="${HOME}/android-sdk"
CMDLINE_TOOLS_VERSION="11076708"          # latest stable as of mid-2026
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"

echo "==> Android SDK root: ${ANDROID_HOME}"

# ── 1. Download command-line tools if not already present ─────────────────
if [ ! -d "${ANDROID_HOME}/cmdline-tools/latest/bin" ]; then
    echo "==> Downloading Android command-line tools..."
    mkdir -p "${ANDROID_HOME}/cmdline-tools"
    wget -q --show-progress "${CMDLINE_TOOLS_URL}" -O "/tmp/${CMDLINE_TOOLS_ZIP}"
    unzip -q "/tmp/${CMDLINE_TOOLS_ZIP}" -d "${ANDROID_HOME}/cmdline-tools"
    # Google zips the tools into a directory called "cmdline-tools"; rename to "latest"
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" 2>/dev/null || true
    rm -f "/tmp/${CMDLINE_TOOLS_ZIP}"
    echo "==> Command-line tools installed."
else
    echo "==> Command-line tools already present, skipping download."
fi

SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"

# ── 2. Accept licenses ─────────────────────────────────────────────────────
echo "==> Accepting SDK licenses..."
yes | "${SDKMANAGER}" --licenses > /dev/null 2>&1 || true

# ── 3. Install required SDK components ────────────────────────────────────
# platforms;android-35  → targetSdk = 35 (stable channel)
# build-tools;35.0.1    → proven working with AGP 9.x
# AGP auto-downloads android-37 at build time (not available via sdkmanager stable channel)
PACKAGES=(
    "platform-tools"
    "platforms;android-37"
    "build-tools;36.0.0"
)

for pkg in "${PACKAGES[@]}"; do
    if "${SDKMANAGER}" --list_installed 2>/dev/null | grep -qF "${pkg}"; then
        echo "==> Already installed: ${pkg}"
    else
        echo "==> Installing: ${pkg}"
        # Use || true so a single package warning/failure does not abort the script
        yes | "${SDKMANAGER}" "${pkg}" 2>&1 | grep -v "^$" || true
        echo "==>   Done: ${pkg}"
    fi
done

# ── 4. Write local.properties ──────────────────────────────────────────────
LOCAL_PROPS="$(dirname "$(realpath "$0")")/../local.properties"
if [ ! -f "${LOCAL_PROPS}" ] || ! grep -q "^sdk.dir=" "${LOCAL_PROPS}"; then
    echo "==> Writing local.properties..."
    echo "sdk.dir=${ANDROID_HOME}" >> "${LOCAL_PROPS}"
else
    echo "==> local.properties already contains sdk.dir, skipping."
fi

echo ""
echo "✅  Android SDK setup complete."
echo "    ANDROID_HOME=${ANDROID_HOME}"
echo "    Run './gradlew assembleDebug' to build the debug APK."
