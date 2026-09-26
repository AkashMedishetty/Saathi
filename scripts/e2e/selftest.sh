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
run_case destroyed-then-reconnected 1 'service destroyed'
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
# Quick selection is eight and can be listed offline; no real or stub adb is invoked.
"$DIR/run.sh" --quick --list --output "$TMP/quick-list" > "$TMP/quick-names"
[ "$(wc -l < "$TMP/quick-names" | tr -d ' ')" -eq 8 ]
grep -q '^06-letters-bigger-hi$' "$TMP/quick-names"
grep -q '^08-reminder-minute$' "$TMP/quick-names"
COUNT=$((COUNT+1)); echo 'PASS quick-selects-only-eight-demos'
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
printf '%s total local checks passed, including real saved phone logs and local command plumbing.\n' "$COUNT"
