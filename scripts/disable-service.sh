#!/usr/bin/env bash
# Turn Saathi's accessibility service off (removes the overlay), keeping other services enabled.
cd "$(dirname "$0")/.." && source scripts/env.sh
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
others=$(echo "$cur" | tr ':' '\n' | grep -v -i "^$PKG/" | grep -v '^null$' | grep -v '^$' | paste -sd: -)
adb shell settings put secure enabled_accessibility_services "${others:-null}"
adb shell settings get secure enabled_accessibility_services
