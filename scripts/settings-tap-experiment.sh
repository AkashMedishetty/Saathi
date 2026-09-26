#!/usr/bin/env bash
# Tap the Settings app icon (like a person) with Saathi's glow on it vs with Saathi's windows removed.
cd "$(dirname "$0")/.." && source scripts/env.sh
N=${1:-5}
B() { adb shell am broadcast -a com.saathi.GOAL -p "$PKG" "$@" >/dev/null; }
on() { adb shell settings get secure enabled_accessibility_services | grep -c saathi; }
ensure() { [ "$(on)" = 1 ] || { ./scripts/enable-service.sh >/dev/null 2>&1; sleep 5; }; }
trial() { # hide(0/1)
  ensure; B --es cmd stop; B --es cmd overlay_on; adb shell input keyevent KEYCODE_HOME; sleep 1; adb logcat -c
  B --es lang EN --es goal "'make the letters bigger'"; sleep 3
  adb shell input swipe 720 1700 720 500 250; sleep 2.5
  local b=$(adb logcat -d -s SaathiLog:I | grep "key=open_settings" | tail -1 | grep -o "bounds=\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | grep -o "[0-9][0-9]*" | tr '\n' ' ')
  set -- $b; [ -z "$1" ] && { echo "  (icon not found)"; return 2; }
  [ "$HIDE" = 1 ] && { B --es cmd overlay_off; sleep 0.8; }
  adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 3
  local r=$(on); adb shell input keyevent KEYCODE_HOME; sleep 1; B --es cmd stop
  [ "$r" = 1 ] && return 0 || return 1
}
for HIDE in 0 1; do
  k=0; n=0
  for t in $(seq 1 $N); do trial; rc=$?; [ $rc = 2 ] && continue; n=$((n+1)); [ $rc = 1 ] && k=$((k+1)); done
  echo "glow $( [ $HIDE = 1 ] && echo REMOVED || echo ON ) while tapping the Settings icon: switched off $k / $n"
done
ensure; B --es cmd overlay_on
