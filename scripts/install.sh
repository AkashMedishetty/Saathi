#!/usr/bin/env bash
# Build, install, re-enable the service, open the app.
set -euo pipefail
cd "$(dirname "$0")/.." && source scripts/env.sh
./gradlew assembleDebug -q
adb install -r -g "$APK"
./scripts/enable-service.sh
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
