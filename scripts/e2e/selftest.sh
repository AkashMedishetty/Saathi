#!/usr/bin/env bash
set -eu
set -o pipefail
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
  name=$1; expected=$2; wanted=$3
  COUNT=$((COUNT+1))
  set +e
  "$DIR/run.sh" --scenarios "$TMP/scenarios" --dry-run "$TMP/input.log" --output "$TMP/out-$COUNT" > "$TMP/report" 2>&1
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
for scn in "$DIR/scenarios"/*.scn; do
  [ -f "$scn" ] || continue
  awk -f "$DIR/parse.awk" "$scn" > /dev/null
  COUNT=$((COUNT+1))
done
printf '%s local checks passed. No device command was invoked.\n' "$COUNT"
