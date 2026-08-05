#!/usr/bin/env bash
set -euo pipefail

# Build the distributable release APK using the project's environment setup.
bash "$(dirname "$0")/scripts/build-release.sh"