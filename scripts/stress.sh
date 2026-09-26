#!/usr/bin/env bash
# Stress + edge cases on the phone: rapid goals, commands mid-task, app switching, lock/unlock, back/home spam,
# process kill while a task runs, all three languages. Pass = no crash in logcat, service still bound, overlay clean.
# Never touches HackTracker (no force-stop, no a11y list changes).
cd "$(dirname "$0")/.." && source scripts/env.sh
say() { adb shell am broadcast -a com.saathi.GOAL -p "$PKG" "$@" >/dev/null; }
goal() { g=$(printf '%s' "$1" | sed "s/'/'\\\\''/g"); say --es goal "'$g'" ${2:+--es lang $2}; }
adb logcat -c; adb shell input keyevent KEYCODE_WAKEUP
N=${1:-3}
for round in $(seq 1 "$N"); do
  echo "round $round"
  for g in "make the text bigger" "turn on wifi" "open youtube" "what is on this screen" "read my messages" "turn on the torch" "turn off the torch" "tv volume up" "what's my BP tablet?"; do
    goal "$g"; sleep 0.6
  done
  goal "अक्षर बड़े करो" HI; sleep 1; say --es cmd doit; sleep 0.4; say --es cmd doit; sleep 0.4; adb shell input keyevent KEYCODE_HOME; sleep 1
  goal "కొడుకుకి వీడియో కాల్ చేయి" TE; sleep 1.2; adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; sleep 0.5
  goal "do it all for me: make the text bigger" EN; sleep 2; adb shell input swipe 700 2400 700 1200 200; sleep 1   # person takes over
  for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_APP_SWITCH; sleep 0.3; adb shell input keyevent KEYCODE_BACK; done
  adb shell input keyevent KEYCODE_POWER; sleep 1; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; sleep 1
  say --es cmd scam_sms; sleep 0.8; say --es cmd stop
  say --es cmd dump >/dev/null
done
say --es cmd stop --es lang EN; adb shell input keyevent KEYCODE_HOME
echo "── crashes (want none):"; adb logcat -d -b crash | grep -A8 "$PKG" | head -30
echo "── ANRs (want none):"; adb logcat -d | grep -i "ANR in $PKG" | head
echo "── service bound:"; adb shell dumpsys accessibility | grep -c "SaathiService"
echo "── app memory (PSS KB):"; adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL:" | head -2
