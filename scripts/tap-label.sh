#!/usr/bin/env bash
# Tap the first on-screen node whose text/desc/id matches <regex> in the latest fixture file (or a fresh dump).
# Usage: scripts/tap-label.sh <fixture.txt> <regex>
cd "$(dirname "$0")/.." && source scripts/env.sh
line=$(grep -E "$2" "$1" | grep -v "Rect(-" | head -1)
[ -z "$line" ] && { echo "no match for $2"; exit 1; }
set -- $(echo "$line" | grep -o "Rect([0-9, -]*)" | grep -o "[0-9][0-9]*")
adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); echo "tapped $(( ($1+$3)/2 )),$(( ($2+$4)/2 ))"
