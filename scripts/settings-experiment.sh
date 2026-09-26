#!/usr/bin/env bash
# Which condition makes the phone switch Saathi off when Settings opens? 4 conditions × N launcher-style opens.
# Touches only Saathi's own accessibility entry (enable-service.sh). Never HackTracker.
cd "$(dirname "$0")/.." && source scripts/env.sh
N=${1:-5}
B() { adb shell am broadcast -a com.saathi.GOAL -p "$PKG" "$@" >/dev/null; }
on() { adb shell settings get secure enabled_accessibility_services | grep -c saathi; }
ensure() { [ "$(on)" = 1 ] || { ./scripts/enable-service.sh >/dev/null 2>&1; sleep 5; }; }
brainpss() { adb shell dumpsys meminfo com.saathi.app:brain 2>/dev/null | grep "TOTAL PSS" | awk '{print int($3/1024)}'; }
run() { # name brain(0/1) overlay(0/1)
  local kills=0
  for t in $(seq 1 $N); do
    ensure
    if [ "$2" = 1 ]; then B --es cmd brain_load; for w in $(seq 1 30); do [ "$(brainpss)" -gt 1500 ] 2>/dev/null && break; sleep 1; done
    else B --es cmd brain_unload; sleep 2; fi
    if [ "$3" = 1 ]; then B --es cmd overlay_on; else B --es cmd overlay_off; fi
    adb shell input keyevent KEYCODE_HOME; sleep 1.5
    adb shell monkey -p com.android.settings -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
    [ "$(on)" = 1 ] || kills=$((kills+1))
    adb shell input keyevent KEYCODE_HOME; sleep 1
  done
  echo "$1: switched off $kills / $N   (brain PSS now $(brainpss) MB)"
}
run "A brain OFF, windows ON " 0 1
run "B brain OFF, windows OFF" 0 0
run "C brain ON,  windows ON " 1 1
run "D brain ON,  windows OFF" 1 0
ensure; B --es cmd overlay_on
