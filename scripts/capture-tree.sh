#!/usr/bin/env bash
# Save the current screen's accessibility tree (via Saathi's own service, never uiautomator) as a fixture.
# Usage: scripts/capture-tree.sh <app> <screen>
cd "$(dirname "$0")/.." && source scripts/env.sh
mkdir -p "fixtures/trees/$1"
adb logcat -c; adb shell am broadcast -a com.saathi.GOAL -p "$PKG" --es cmd dump >/dev/null; sleep 1.5
adb logcat -d -s SaathiDump:I | sed -n 's/^.*SaathiDump: //p' | sed '/^READ:/,$d' > "fixtures/trees/$1/$2.txt"
adb shell screencap -p > "fixtures/trees/$1/$2.png"
echo "$1/$2: $(wc -l < "fixtures/trees/$1/$2.txt") nodes"
