#!/usr/bin/env bash
# Screenshot the phone to shots/<name>.png (never use uiautomator dump: it kills our a11y service).
cd "$(dirname "$0")/.." && source scripts/env.sh
mkdir -p shots; out="shots/${1:-$(date +%H%M%S)}.png"
adb exec-out screencap -p > "$out" && echo "$out"
