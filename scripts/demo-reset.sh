#!/usr/bin/env bash
# Between demo runs: stop any task, English, back to the home screen, wake the phone. Never touches HackTracker.
cd "$(dirname "$0")/.." && source scripts/env.sh
adb shell input keyevent KEYCODE_WAKEUP
adb shell am broadcast -a com.saathi.GOAL -p "$PKG" --es cmd stop --es lang "${1:-EN}" >/dev/null
adb shell input keyevent KEYCODE_HOME
echo "reset (lang ${1:-EN})"
