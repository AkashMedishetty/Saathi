#!/usr/bin/env bash
# Re-enable Saathi's accessibility service (needed after every reinstall / force-stop).
# NEVER touches other services (the event's HackTracker must stay enabled at all times):
# we only remove/add our own entry, the others stay in the list throughout.
cd "$(dirname "$0")/.." && source scripts/env.sh
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
others=$(echo "$cur" | tr ':' '\n' | grep -v -i "^$PKG/" | grep -v '^null$' | grep -v '^$' | paste -sd: -)
# Drop only our entry (forces a rebind), then add it back.
[ -n "$others" ] && adb shell settings put secure enabled_accessibility_services "$others"
sleep 0.5
adb shell settings put secure enabled_accessibility_services "${others:+$others:}$SVC"
adb shell settings get secure enabled_accessibility_services
