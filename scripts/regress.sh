#!/usr/bin/env bash
# Demo regression: runs the frozen demo scenarios on the phone and checks the field log for the expected behaviour.
# Usage: scripts/regress.sh [scenario-number ...]   (no args = all). Needs Wi-Fi for R2/R3 lookups.
# Never touches HackTracker. Pass = expected log line appears in time and no crash.
cd "$(dirname "$0")/.." && source scripts/env.sh
B() { adb shell am broadcast -a com.saathi.GOAL -p "$PKG" "$@" >/dev/null; }
goal() { g=$(printf '%s' "$1" | sed "s/'/'\\\\''/g"); B --es goal "'$g'" ${2:+--es lang $2}; }
log() { adb logcat -d -s SaathiLog:I; }
# wait_for <regex> <seconds>
wait_for() { local end=$((SECONDS + $2)); while [ $SECONDS -lt $end ]; do log | grep -qE "$1" && return 0; sleep 1; done; return 1; }
reset() { B --es cmd stop --es lang EN; adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent KEYCODE_HOME; sleep 1; adb logcat -c; }
PASS=0; FAIL=0; RESULTS=()
check() { # name, regex, seconds
  if wait_for "$2" "$3"; then PASS=$((PASS+1)); RESULTS+=("✅ $1"); else FAIL=$((FAIL+1)); RESULTS+=("❌ $1  (wanted: $2)"); fi
  adb shell screencap -p > "shots/regress-$(echo "$1" | tr ' /' '__' | cut -c1-30).png" 2>/dev/null
}
want() { [ ${#SEL[@]} -eq 0 ] && return 0; for s in "${SEL[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }
SEL=("$@"); mkdir -p shots

reset; goal "hello"; wait_for "\[answer\]" 20 >/dev/null   # warm the brain

if want 1; then reset; goal "अक्षर बड़े करो" HI;  check "R1 letters bigger (HI) → glow on font size" "\[show\] key=(display|font|slider)" 15; fi
if want 2; then reset; goal "will it rain today" TE; check "R2 rain (TE) → answer read from results" "\[lookup\] q=.* a=\"[^\"]{8,}" 40; fi
if want 3; then reset; goal "play guntur karam movie on my tv"
  check "R3a TV coach → looks it up" "\[coach\] step 1: Call\(tool=LOOKUP" 25
  check "R3b TV coach → asks about the subscription" "\[coach\] step [0-9]+: Call\(tool=ASK" 40
  goal "yes"; check "R3c TV coach → guides the TV (look/press)" "\[coach\] step [0-9]+: Call\(tool=(LOOK_TV|TV)" 30; fi
if want 4; then reset; goal "read this letter for me"; sleep 4; adb shell input tap 720 2688
  check "R4 read this → explained on device" "\[vision\] [0-9]+ ms|\[read\] text .*(NPU|Gemma)" 30; B --es cmd stop; adb shell input keyevent KEYCODE_BACK; fi
if want 5; then reset; B --es cmd scam_sms; check "R5 scam SMS → alert" "\[alert\] message scam" 8; fi
if want 6; then reset; goal "watch me: open my youtube subscriptions"; sleep 2
  adb shell monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; adb shell input tap 1008 2920; sleep 2
  goal "done teaching"; check "R6a teach → saved" "\[teach\] saved" 10
  adb shell input keyevent KEYCODE_HOME; adb shell am force-stop com.google.android.youtube; sleep 1; adb logcat -c
  goal "open my youtube subscriptions"; check "R6b replay → glow on the taught step" "\[show\] key=r0" 15; fi
if want 7; then reset; goal "remember my BP tablet is Telma 40"; sleep 2; goal "what's my BP tablet?"
  check "R7a memory recall" "\[finish\] \".*Telma" 20
  reset; goal "help!"; sleep 2
  if adb shell dumpsys window | grep mCurrentFocus | grep -q SosActivity; then PASS=$((PASS+1)); RESULTS+=("✅ R7b SOS screen"); else FAIL=$((FAIL+1)); RESULTS+=("❌ R7b SOS screen"); fi
  adb shell input keyevent KEYCODE_BACK; fi

reset
echo; printf '%s\n' "${RESULTS[@]}"
echo "── $PASS passed, $FAIL failed"
echo "── crashes:"; adb logcat -d -b crash | grep -A6 "$PKG" | head -20 || true
echo "── service bound: $(adb shell dumpsys accessibility | grep -c SaathiService)"
