#!/usr/bin/env bash
# Re-enable the accessibility service (needed after every reinstall / force-stop).
cd "$(dirname "$0")/.." && source scripts/env.sh
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services
