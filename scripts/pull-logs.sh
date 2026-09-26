#!/usr/bin/env bash
# Bring back the on-phone field-test log(s) + a crash dump, into logs/<time>/ for review.
cd "$(dirname "$0")/.." && source scripts/env.sh
out="logs/$(date +%H%M)"; mkdir -p "$out"
for f in $(adb shell run-as "$PKG" ls files/logs | tr -d '\r'); do adb exec-out run-as "$PKG" cat "files/logs/$f" > "$out/$f"; done
adb logcat -d -b crash > "$out/crash-buffer.txt"
wc -l "$out"/* ; grep -hE "CRASH|W/|error" "$out"/*.log | tail -30
