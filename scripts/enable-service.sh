#!/usr/bin/env bash
# Re-enable Saathi's accessibility service (needed after every reinstall / force-stop).
#   scripts/enable-service.sh        → the basic Saathi (com.saathi.app) is the active helper
#   scripts/enable-service.sh pro    → Saathi Pro (com.saathi.app.pro) is the active helper
# Exactly ONE Saathi is on at a time (two would both draw cards and glows).
# NEVER touches other services (the event's HackTracker must stay enabled at all times):
# we only remove/add the two Saathi entries; every other entry stays in the list throughout.
cd "$(dirname "$0")/.." && source scripts/env.sh
target="com.saathi.app/com.saathi.app.service.SaathiService"
[ "${1:-}" = "pro" ] && target="com.saathi.app.pro/com.saathi.app.service.SaathiService"
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
others=$(echo "$cur" | tr ':' '\n' | grep -v -i "^com\.saathi\.app/" | grep -v -i "^com\.saathi\.app\.pro/" | grep -v '^null$' | grep -v '^$' | paste -sd: -)
# Drop the Saathi entries (forces a rebind), then add the chosen one back.
[ -n "$others" ] && adb shell settings put secure enabled_accessibility_services "$others"
sleep 0.5
adb shell settings put secure enabled_accessibility_services "${others:+$others:}$target"
adb shell settings get secure enabled_accessibility_services
