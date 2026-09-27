#!/usr/bin/env bash
# Switch the phone's active helper to the basic Saathi (on-device). Only the two Saathi entries change; HackTracker stays on.
cd "$(dirname "$0")/.." && ./scripts/enable-service.sh >/dev/null; sleep 3
list=$(source scripts/env.sh; adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "$list" | tr ':' '\n'
echo "$list" | grep -q "com.saathi.app/" && ! echo "$list" | grep -q "com.saathi.app.pro/" && echo "OK: the basic Saathi is the active helper" || echo "CHECK: the list above is not basic-only"
echo "$list" | grep -qi hacktracker && echo "OK: HackTracker still on" || echo "WARNING: HackTracker missing from the list; open HackTracker"
