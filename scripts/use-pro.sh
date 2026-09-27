#!/usr/bin/env bash
# Switch the phone's active helper to Saathi Pro (cloud). Only the two Saathi entries change; HackTracker stays on.
cd "$(dirname "$0")/.." && ./scripts/enable-service.sh pro >/dev/null; sleep 3
list=$(source scripts/env.sh; adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "$list" | tr ':' '\n'
echo "$list" | grep -q "com.saathi.app.pro/" && ! echo "$list" | grep -q "com.saathi.app/" && echo "OK: Saathi Pro is the active helper" || echo "CHECK: the list above is not Pro-only"
echo "$list" | grep -qi hacktracker && echo "OK: HackTracker still on" || echo "WARNING: HackTracker missing from the list; open HackTracker"
