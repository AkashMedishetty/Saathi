#!/usr/bin/env bash
set -eu
set -o pipefail
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != --dry-run ]; }; then echo 'Usage: selftest.sh [--dry-run]' >&2; exit 2; fi
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d "${TMPDIR:-/tmp}/saathi-e2e-selftest.XXXXXX")
# Only this test's private temporary directory is removed.
trap 'rm -r -- "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/scenarios"
cat > "$TMP/bin/adb" <<'STUB'
#!/usr/bin/env bash
printf 'unexpected adb invocation\n' >> "$E2E_TEST_SENTINEL"
exit 99
STUB
chmod +x "$TMP/bin/adb"
export PATH="$TMP/bin:$PATH" E2E_TEST_SENTINEL="$TMP/device-called"
COUNT=0
run_case() {
  name=$1; expected=$2; wanted=$3; shift 3
  COUNT=$((COUNT+1))
  set +e
  "$DIR/run.sh" --scenarios "$TMP/scenarios" --dry-run "$TMP/input.log" --output "$TMP/out-$COUNT" "$@" > "$TMP/report" 2>&1
  rc=$?
  set -e
  if [ "$rc" -ne "$expected" ] || ! grep -Eq -e "$wanted" "$TMP/report"; then
    printf 'FAIL selftest %s: exit %s (wanted %s)\n' "$name" "$rc" "$expected" >&2
    cat "$TMP/report" >&2; exit 1
  fi
  [ ! -e "$TMP/device-called" ] || { echo 'Dry-run invoked adb' >&2; exit 1; }
  printf 'PASS %s\n' "$name"
}
cp "$DIR/testdata/happy.scn" "$TMP/scenarios/test.scn"
cp "$DIR/testdata/happy.log" "$TMP/input.log"
run_case happy 0 '1 passed, 0 failed'
grep -q 'tap-glow 200 300' "$TMP/out-1/test/actions.txt"
test -f "$TMP/out-1/test/final.txt"
test ! -f "$TMP/out-1/test/final.png"
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: deadline
say EN hello
expect log "\[finish\]" within 1
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
1.000 I SaathiLog: [finish] "Hello"
3.000 I E2E: enabled=1
3.001 I E2E: end
LOG
run_case exact-deadline 0 '1 passed'
sed 's/^1.000/1.001/' "$TMP/input.log" > "$TMP/late"; mv "$TMP/late" "$TMP/input.log"
run_case late 1 'Timed out'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: negative
say EN hello
expect not log "\[act\]" for 2
SCN
run_case absent-full-window 0 '1 passed'
sed 's/\[finish\]/[act]/' "$TMP/input.log" > "$TMP/act"; mv "$TMP/act" "$TMP/input.log"
run_case forbidden 1 'Forbidden log'
sed 's/for 2/for 5/' "$TMP/scenarios/test.scn" > "$TMP/long"; mv "$TMP/long" "$TMP/scenarios/test.scn"
sed 's/\[act\]/[finish]/' "$TMP/input.log" > "$TMP/log"; mv "$TMP/log" "$TMP/input.log"
run_case truncated-observation 1 'Replay ends before'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: no stale match
say EN hello
expect log "\[finish\]" within 2
expect log "\[finish\]" within 1
SCN
run_case consumed-line 1 'Timed out'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: enabled
expect enabled
SCN
run_case enabled 0 '1 passed'
sed 's/3.000 I E2E: enabled=1/3.000 I E2E: enabled=0/' "$TMP/input.log" > "$TMP/off"; mv "$TMP/off" "$TMP/input.log"
run_case disabled-at-end 1 'Saathi switched off'
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.500 I SaathiLog: [service] destroyed
0.900 I SaathiLog: [service] connected
3.000 I E2E: enabled=1
3.001 I E2E: end
LOG
run_case destroyed-then-reconnected 0 'PASS \(healed\)'
sed 's/0.900/2.501/' "$TMP/input.log" > "$TMP/late-reconnect"; mv "$TMP/late-reconnect" "$TMP/input.log"
run_case late-reconnect 1 'without reconnect within 2 s'
sed '/service.*connected/d' "$TMP/input.log" > "$TMP/no-reconnect"; mv "$TMP/no-reconnect" "$TMP/input.log"
run_case no-reconnect 1 'without reconnect within 2 s'
printf '0.500 I SaathiLog: [finish] ready\n' > "$TMP/input.log"
run_case missing-enabled-evidence 1 'missing final enabled evidence'
cp "$DIR/testdata/happy.log" "$TMP/input.log"
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: bounds
say EN search
expect show key=search within 1
tap-glow
SCN
sed 's/\[100,200\]\[300,400\]/null/' "$TMP/input.log" > "$TMP/null"; mv "$TMP/null" "$TMP/input.log"
run_case null-bounds 1 'Missing or malformed'
cp "$DIR/testdata/happy.log" "$TMP/input.log"
sed 's/key=search el="Search"/key=search el="Send"/' "$TMP/input.log" > "$TMP/risky"; mv "$TMP/risky" "$TMP/input.log"
run_case refuse-send 1 'Refusing a consequential'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: stale tap
tap-glow
SCN
run_case refuse-stale-tap 1 'No fresh show'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: choice
say EN video call my son
expect show key=choose_video within 1
tap-choice phone
SCN
sed 's/key=search/key=choose_video/' "$DIR/testdata/happy.log" > "$TMP/input.log"
run_case choice-needs-calibration 1 'needs --phone-choice'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: bad regex
expect log "[" within 1
SCN
run_case invalid-regex 2 'Invalid regex'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: forbidden reset
apps: com.saathi.app
home
SCN
run_case cannot-force-stop-saathi 2 'Force-stop not allowed'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: no shell
cmd shell rm something
SCN
run_case no-arbitrary-command 2 'unknown or malformed'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: no traversal
screenshot ../elsewhere
SCN
run_case no-screenshot-traversal 2 'unknown or malformed'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: unicode and literal shell text
say HI नमस्ते 'quoted' $(touch NEVER) ; echo harmless
say TE నమస్తే
expect enabled
SCN
cp "$DIR/testdata/happy.log" "$TMP/input.log"
run_case literal-text 0 '1 passed'
cat > "$TMP/input.log" <<'LOG'
09-26 23:59:59.000 100 100 I E2E: enabled=1
09-27 00:00:00.000 100 100 I SaathiLog: [finish] ready
09-27 00:00:01.000 100 100 I E2E: enabled=1
LOG
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: midnight
say EN hello
expect log "\[finish\]" within 1
SCN
run_case midnight-rollover 0 '1 passed'
printf 'I SaathiLog: [finish] no time\n' > "$TMP/input.log"
run_case no-invented-time 2 'missing timestamp'
printf '' > "$TMP/input.log"
run_case empty-log 2 'no timestamped'
# Every distributed scenario is syntactically parsed without touching a device.
for scn in "$DIR/scenarios"/*.scn "$DIR/quick"/*.scn; do
  [ -f "$scn" ] || continue
  awk -f "$DIR/parse.awk" "$scn" > /dev/null
  COUNT=$((COUNT+1))
done
:
# Actual Sat 22:38 trace: verifies parser + goal/route delivery, not the missing later UI steps.
cp "$DIR/testdata/phone-223803/route.scn" "$TMP/scenarios/test.scn"
cp "$DIR/testdata/phone-223803/replay.log" "$TMP/input.log"
run_case phone-223803-real-replay 0 '1 passed'
cp "$DIR/testdata/phone-223803/logcat.log" "$TMP/input.log"
run_case phone-223803-raw-needs-health-evidence 1 'missing final enabled evidence'
grep -q 'PASS 3: log' "$TMP/out-$COUNT/test/steps.log"
# Exercise the exact command wait/watchdog helper using LOCAL executables. No adb is called.
. "$DIR/command.sh"
start=$SECONDS
bounded_command 5 "$TMP/cmd.out" "$TMP/cmd.err" bash -c 'printf "Broadcast completed: result=0\n"; exit 0' > "$TMP/captured"
grep -q 'Broadcast completed: result=0' "$TMP/captured"
[ $((SECONDS-start)) -lt 4 ] || { echo 'Successful command waited for watchdog' >&2; exit 1; }
COUNT=$((COUNT+1)); echo 'PASS command-success-fast-return'
set +e
bounded_command 5 "$TMP/cmd.out" "$TMP/cmd.err" bash -c 'echo failure >&2; exit 7' > "$TMP/captured" 2> "$TMP/error"
status=$?
set -e
[ "$status" -eq 7 ]; grep -q failure "$TMP/error"
COUNT=$((COUNT+1)); echo 'PASS command-preserves-exit-status'
bounded_command 5 "$TMP/cmd.out" "$TMP/cmd.err" bash -c 'if read -r x; then exit 9; fi' <<'INPUT'
this must not be consumed by the command
INPUT
COUNT=$((COUNT+1)); echo 'PASS command-stdin-is-closed'
set +e
bounded_command 1 "$TMP/cmd.out" "$TMP/cmd.err" sleep 3 > "$TMP/captured" 2> "$TMP/error"
status=$?
set -e
[ "$status" -eq 124 ]; grep -q 'timed out' "$TMP/error"
COUNT=$((COUNT+1)); echo 'PASS command-timeout'
[ ! -e "$TMP/device-called" ]
# Quick selection is nine cases (eight demos, Hotstar alternatives) and can be listed offline; no real or stub adb is invoked.
"$DIR/run.sh" --quick --list --output "$TMP/quick-list" > "$TMP/quick-names"
[ "$(wc -l < "$TMP/quick-names" | tr -d ' ')" -eq 9 ]
grep -q '^06-letters-bigger-hi$' "$TMP/quick-names"
grep -q '^08-reminder-minute$' "$TMP/quick-names"
COUNT=$((COUNT+1)); echo 'PASS quick-selects-eight-demos-with-hotstar-alternatives'
[ "$(quick_command_limit 0)" = 8 ]
[ "$(quick_command_limit 233)" = 2 ]
if quick_command_limit 235; then echo 'Quick budget did not expire' >&2; exit 1; fi
COUNT=$((COUNT+1)); echo 'PASS quick-command-budget-boundaries'
# Ordinary spoken replies need no hidden command syntax.
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: ordinary spoken replies
say EN watch my serial on Hotstar
expect log "\[missing\]" within 1
say EN yes
expect focus "com.android.vending" within 1
say EN video call my son
expect show key=choose_video within 1
say EN WhatsApp
expect focus "com.whatsapp" within 1
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [missing] app=in.startv.hotstar
0.200 I E2E: focus=com.android.vending
0.300 I SaathiLog: [show] key=choose_video el="null" bounds=null
0.400 I E2E: focus=com.whatsapp
0.500 I E2E: enabled=1
0.600 I E2E: end
LOG
run_case spoken-replies-use-normal-goals 0 '1 passed'
grep -q 'say EN yes' "$TMP/out-$COUNT/test/steps.log"
grep -q 'say EN WhatsApp' "$TMP/out-$COUNT/test/steps.log"
[ ! -e "$TMP/device-called" ]
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: live choice logging contract
say EN video call my son
expect log "\[choice\] choose_video" within 1
tap-choice phone
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [choice] choose_video: WhatsApp | Phone
0.500 I E2E: enabled=1
0.600 I E2E: end
LOG
run_case calibrated-choice-from-choice-tag 0 '1 passed' --phone-choice 100,200
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: Settings person tap only
say EN change my ringtone
expect show key=map_settings_ringtone_0 within 1
tap-glow
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [show] key=map_settings_ringtone_0 el="Search" role=button bounds=[10,20][90,60] noAct=true pkg=com.android.settings
0.500 I E2E: enabled=1
0.600 I E2E: end
LOG
run_case settings-person-search-tap 0 '1 passed'
sed 's/tap-glow/doit/' "$TMP/scenarios/test.scn" > "$TMP/noact"; mv "$TMP/noact" "$TMP/scenarios/test.scn"
run_case settings-doit-stays-blocked 1 'Refusing a noAct'
# Real 02:42 Settings restart, preserved exactly, plus final health annotations from the operator.
cp "$DIR/testdata/phone-024209/replay.log" "$TMP/input.log"
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: real Settings self heal
expect log "\[service\] destroyed" within 1
expect show key=map_settings_font_2 within 2
SCN
run_case real-settings-75ms-heal 0 'PASS \(healed\)'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: service audit
expect enabled
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [service] destroyed
2.100 I SaathiLog: [service] connected
3.000 I E2E: enabled=1
3.100 I E2E: end
LOG
run_case reconnect-at-two-seconds 0 'healed'
sed 's/2.100/2.101/' "$TMP/input.log" > "$TMP/late"; mv "$TMP/late" "$TMP/input.log"
run_case reconnect-one-ms-late 1 'without reconnect'
cat >> "$TMP/input.log" <<'LOG'
4.000 I SaathiLog: [service] destroyed
4.075 I SaathiLog: [service] connected
5.000 I E2E: enabled=1
LOG
run_case later-heal-cannot-mask-earlier-outage 1 'without reconnect'
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [service] destroyed
0.900 I E2E: enabled=1
1.000 I E2E: end
LOG
run_case truncated-reconnect-window 1 'Replay ends before service reconnect'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: installed precondition
requires-installed in.startv.hotstar
expect enabled
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: package=in.startv.hotstar missing
0.000 I E2E: enabled=1
3.000 I E2E: enabled=1
3.100 I E2E: end
LOG
run_case absent-package-skips-installed-case 0 '0 passed, 0 failed, 1 skipped'
grep -q '⏭.*requires installed in.startv.hotstar' "$TMP/report"
[ ! -e "$TMP/out-$COUNT/test/steps.log" ]
[ ! -s "$TMP/out-$COUNT/test/actions.txt" ]
sed 's/hotstar missing/hotstar installed/' "$TMP/input.log" > "$TMP/state"; mv "$TMP/state" "$TMP/input.log"
run_case installed-precondition-passes 0 '1 passed, 0 failed, 0 skipped'
sed 's/requires-installed/requires-missing/' "$TMP/scenarios/test.scn" > "$TMP/scn"; mv "$TMP/scn" "$TMP/scenarios/test.scn"
run_case installed-package-skips-missing-case 0 '0 passed, 0 failed, 1 skipped'
sed 's/hotstar installed/hotstar missing/' "$TMP/input.log" > "$TMP/state"; mv "$TMP/state" "$TMP/input.log"
run_case missing-precondition-passes 0 '1 passed, 0 failed, 0 skipped'
sed '/package=/d' "$TMP/input.log" > "$TMP/no-package"; mv "$TMP/no-package" "$TMP/input.log"
run_case unknown-package-evidence-fails 1 'Missing package evidence'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: misplaced precondition
say EN hello
requires-missing in.startv.hotstar
SCN
run_case preconditions-before-actions-only 2 'preconditions must precede'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: unsafe package
requires-installed in.startv.hotstar;echo
SCN
run_case rejects-package-shell-text 2 'unknown or malformed'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: top bar bounds
say EN WhatsApp
expect show key=map_wa_video_call_3 within 1
expect bounds top < 500
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [show] key=map_wa_video_call_3 el="Video call" bounds=[1000,160][1140,300] noAct=false
3.000 I E2E: enabled=1
3.100 I E2E: end
LOG
run_case top-bar-bounds-pass 0 '1 passed'
sed 's/1000,160/1000,500/' "$TMP/input.log" > "$TMP/low"; mv "$TMP/low" "$TMP/input.log"
run_case top-at-500-fails 1 'Glow bounds top must be < 500'
sed 's/\[1000,500\]\[1140,300\]/null/' "$TMP/input.log" > "$TMP/null"; mv "$TMP/null" "$TMP/input.log"
run_case missing-bounds-fails 1 'actual: missing'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: setup wall no planner
say EN watch anupama on hotstar
expect log "\[wall\].*setup=true" within 1
expect not log "\[plan\]" for 2
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.100 I SaathiLog: [wall] in.startv.hotstar setup=true button=Log in
3.000 I E2E: enabled=1
3.100 I E2E: end
LOG
run_case login-wall-stops-planner 0 '1 passed'
# A late planner call outside the local 2s observation still violates the whole-case audit.
sed '/3.000/i\
2.900 I SaathiLog: [plan] goal="watch anupama" pkg=in.startv.hotstar
' "$TMP/input.log" > "$TMP/plan"; mv "$TMP/plan" "$TMP/input.log"
run_case late-planner-after-wall-fails 1 'Planner ran after a setup login wall'
[ ! -e "$TMP/device-called" ]
# Each new scenario is also executed on explicit synthetic evidence, not only parsed.
cp "$DIR/scenarios/50-hotstar-watch.scn" "$TMP/scenarios/test.scn"
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: package=in.startv.hotstar installed
0.000 I E2E: enabled=1
0.100 I SaathiLog: [show] key=map_hs_watch_0 el="Search" bounds=[90,2786][510,2966] noAct=false
0.200 I SaathiLog: [show] key=map_hs_watch_1 el="" bounds=[241,369][1214,549] noAct=false
0.300 I SaathiLog: [show] key=map_hs_watch_2 el="Latest Episode" bounds=[45,1419][889,1599] noAct=false
0.400 I SaathiLog: [wall] in.startv.hotstar setup=true button=Log in
5.000 I E2E: enabled=1
5.100 I E2E: end
LOG
run_case hotstar-watch-to-wall 0 '1 passed'
[ "$(wc -l < "$TMP/out-$COUNT/test/actions.txt" | tr -d ' ')" -eq 2 ]
cp "$DIR/scenarios/51-youtube-playlist.scn" "$TMP/scenarios/test.scn"
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: package=com.google.android.youtube installed
0.000 I E2E: enabled=1
0.100 I SaathiLog: [show] key=map_yt_search_0 el="Search" bounds=[100,100][200,200] noAct=false
0.200 I SaathiLog: [show] key=map_yt_search_2 el="Search YouTube" bounds=[100,100][900,200] noAct=false
0.300 I SaathiLog: [show] key=map_yt_search_3 el="old telugu melody songs playlist" bounds=[100,300][900,400] noAct=false
0.400 I SaathiLog: [show] key=map_yt_search_5 el="Playlist - Old songs" bounds=[100,500][900,1000] noAct=false
0.500 I SaathiLog: [show] key=map_yt_search_4 el="Play all" bounds=[100,700][900,900] noAct=false
5.000 I E2E: enabled=1
5.100 I E2E: end
LOG
run_case playlist-opens-play-all 0 '1 passed'
sed 's/0.500 I SaathiLog:.*/0.500 I SaathiLog: [finish] playing/' "$TMP/input.log" > "$TMP/video"; mv "$TMP/video" "$TMP/input.log"
run_case playlist-query-direct-video-alternative 0 '1 passed'
cp "$DIR/scenarios/52-media-controls.scn" "$TMP/scenarios/test.scn"
sed '/5.000/i\
0.600 I SaathiLog: [media] "pause"\
0.700 I SaathiLog: [media] "louder"\
0.800 I SaathiLog: [media] "close this app"\
0.900 I E2E: focus=mCurrentFocus com.bbk.launcher/.Launcher
' "$TMP/input.log" > "$TMP/media"; mv "$TMP/media" "$TMP/input.log"
run_case media-controls-return-to-launcher 0 '1 passed'
sed 's@com.bbk.launcher/.Launcher@com.google.android.youtube/.WatchActivity@' "$TMP/input.log" > "$TMP/focus"; mv "$TMP/focus" "$TMP/input.log"
run_case media-close-wrong-focus-fails 1 'No focus evidence matching'
cp "$DIR/scenarios/53-aside-step-explanation.scn" "$TMP/scenarios/test.scn"
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: package=com.google.android.youtube installed
0.000 I E2E: enabled=1
0.100 I SaathiLog: [show] key=map_yt_search_0 el="Search" bounds=[100,100][200,200]
0.200 I SaathiLog: [answer] q="what does the magnifying glass mean?" a="The magnifying glass means search." (step's own explanation)
0.300 I SaathiLog: [show] key=map_yt_search_0 el="Search" bounds=[100,100][200,200]
5.000 I E2E: enabled=1
5.100 I E2E: end
LOG
run_case aside-checked-explanation-and-resume 0 '1 passed'
sed "s/(step's own explanation)/(model answer)/" "$TMP/input.log" > "$TMP/answer"; mv "$TMP/answer" "$TMP/input.log"
run_case aside-ungrounded-answer-fails 1 'Replay ends before observation deadline'
cp "$DIR/scenarios/47-telugu-message-content.scn" "$TMP/scenarios/test.scn"
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: package=com.whatsapp installed
0.000 I E2E: enabled=1
0.100 I SaathiLog: [show] key=map_wa_message_3 el="Message" bounds=[100,2900][700,3040] noAct=false
0.200 I SaathiLog: [show] key=map_wa_message_4 el="Send" bounds=[1260,2900][1400,3040] noAct=true
5.000 I E2E: enabled=1
5.100 I E2E: end
LOG
run_case telugu-message-body-then-send-glow 0 '1 passed'
[ ! -s "$TMP/out-$COUNT/test/actions.txt" ]
printf 'tap-glow\n' >> "$TMP/scenarios/test.scn"
run_case telugu-send-tap-is-refused 1 'Refusing a consequential target'
cp "$DIR/scenarios/42-video-spoken-whatsapp.scn" "$TMP/scenarios/test.scn"
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: package=com.whatsapp installed
0.000 I E2E: enabled=1
0.100 I SaathiLog: [choice] choose_video: WhatsApp | Phone
0.200 I SaathiLog: [show] key=map_wa_video_call_3 el="వీడియో కాల్" bounds=[1000,160][1140,300] noAct=false
5.000 I E2E: enabled=1
5.100 I E2E: end
LOG
run_case spoken-whatsapp-top-bar 0 '1 passed'
printf 'tap-glow\n' >> "$TMP/scenarios/test.scn"
run_case numeric-video-call-target-refused 1 'Refusing a consequential target'
cat > "$TMP/scenarios/test.scn" <<'SCN'
name: health sample during heal
wait 1
expect enabled
SCN
cat > "$TMP/input.log" <<'LOG'
0.000 I E2E: enabled=1
0.990 I SaathiLog: [service] destroyed
1.000 I E2E: enabled=0
1.065 I SaathiLog: [service] connected
1.100 I E2E: enabled=1
3.000 I E2E: enabled=1
3.100 I E2E: end
LOG
run_case health-sample-during-75ms-restart 0 'healed'
[ ! -e "$TMP/device-called" ]
printf '%s total local checks passed, including real saved phone logs and local command plumbing.\n' "$COUNT"
