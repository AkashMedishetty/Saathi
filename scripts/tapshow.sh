#!/usr/bin/env bash
# Tap the centre of the last glowed target (the last "[show] … bounds=[l,t][r,b]" line), like the person would.
cd "$(dirname "$0")/.." && source scripts/env.sh
b=$(adb logcat -d -s SaathiLog:I | grep "\[show\]" | grep -v "bounds=null" | tail -1 | sed -n 's/.*bounds=\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p')
[ -z "$b" ] && { echo "no glow to tap"; exit 1; }
set -- $b; x=$(( ($1+$3)/2 )); y=$(( ($2+$4)/2 )); adb shell input tap $x $y; echo "tapped $x,$y"
