#!/usr/bin/env bash
# macOS Bash 3.2 + POSIX awk/grep/sed. No eval, downloaded tools, or implicit device setup.
set -u
set -o pipefail
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$DIR/../.." && pwd)
DRY= OUTPUT= SCENARIOS="$DIR/scenarios" PATTERN='*' PHONE_CHOICE=
usage() {
  cat <<'HELP'
Usage: scripts/e2e/run.sh [pattern] [--dry-run LOGFILE] [--output DIR]
       [--scenarios DIR] [--phone-choice X,Y]
Pattern is a shell glob matched against scenario filenames/stems, not a regex.
--dry-run never invokes adb or sources device environment scripts.
Replay logs need timestamped E2E: enabled=0|1, focus=..., end annotations for those checks.
--phone-choice is an operator-calibrated centre of the SECOND choice button, from a current screenshot.
Only Claude/Akash run live mode. Recovery, if needed: scripts/enable-service.sh (not automatic).
HELP
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run|--output|--scenarios|--phone-choice)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      case "$1" in --dry-run) DRY=$2;; --output) OUTPUT=$2;; --scenarios) SCENARIOS=$2;; --phone-choice) PHONE_CHOICE=$2;; esac
      shift 2;;
    --help|-h) usage; exit 0;;
    --*) printf 'Unknown option: %s\n' "$1" >&2; exit 2;;
    *) PATTERN=$1; shift;;
  esac
done
[ -d "$SCENARIOS" ] || { echo 'Scenario directory is missing' >&2; exit 2; }
if [ -n "$PHONE_CHOICE" ] && ! printf '%s\n' "$PHONE_CHOICE" | grep -Eq '^[0-9]{1,5},[0-9]{1,5}$'; then
  echo 'Phone choice must be X,Y from a current screenshot' >&2; exit 2
fi
if [ -n "$DRY" ]; then
  [ -r "$DRY" ] || { echo 'Replay log is unreadable' >&2; exit 2; }
  DRY=$(CDPATH= cd -- "$(dirname -- "$DRY")" && pwd)/$(basename -- "$DRY")
fi
[ -n "$OUTPUT" ] || OUTPUT="$ROOT/shots/e2e/$(date '+%Y%m%d-%H%M%S')-$$"
# Never overwrite evidence from an earlier run.
[ ! -e "$OUTPUT" ] || { echo 'Output directory already exists; choose a new path' >&2; exit 2; }
mkdir -p "$OUTPUT" || exit 2
OUTPUT=$(CDPATH= cd -- "$OUTPUT" && pwd)
PLAN="$OUTPUT/plans"; mkdir -p "$PLAN"
SELECTED=()
allowed_app() {
  case "$1" in
    com.google.android.youtube|com.whatsapp|com.whatsapp.w4b|com.instagram.android|com.spotify.music|com.google.android.apps.docs.editors.docs|com.google.android.apps.maps|com.android.chrome|com.google.android.apps.photos|com.netflix.mediaclient|in.startv.hotstar|com.ubercab|com.google.android.deskclock) return 0;;
    *) return 1;;
  esac
}
# Parse and validate ALL selected scenarios before even resolving adb.
for file in "$SCENARIOS"/*.scn; do
  [ -f "$file" ] || continue
  base=$(basename -- "$file" .scn)
  case "$base" in *[!A-Za-z0-9_-]*) echo "Unsafe scenario filename: $base" >&2; exit 2;; esac
  case "$base" in $PATTERN) :;; *) case "$base.scn" in $PATTERN) :;; *) continue;; esac;; esac
  awk -f "$DIR/parse.awk" "$file" > "$PLAN/$base.tsv" || exit 2
  while IFS="$(printf '\t')" read -r ln op arg sec; do
    case "$op" in
      apps) for app in $arg; do allowed_app "$app" || { echo "Force-stop not allowed: $app" >&2; exit 2; }; done;;
      show|log|focus|not-log)
        printf '' | grep -E -e "$arg" >/dev/null 2>&1
        rc=$?; [ "$rc" -ne 2 ] || { echo "Invalid regex at $base:$ln" >&2; exit 2; };;
    esac
  done < "$PLAN/$base.tsv"
  SELECTED+=("$base")
done
[ "${#SELECTED[@]}" -gt 0 ] || { echo 'No matching scenarios' >&2; exit 2; }
if [ -n "$DRY" ]; then
  awk -f "$DIR/logs.awk" "$DRY" > "$OUTPUT/replay.tsv" || exit 2
else
  # The existing env file sets SDK paths and the serial. It is never read in replay mode.
  . "$ROOT/scripts/env.sh"
  command -v adb >/dev/null || { echo 'adb unavailable' >&2; exit 2; }
fi
DEVICE_PID= WATCH_PID=
cleanup() {
  [ -z "$DEVICE_PID" ] || kill "$DEVICE_PID" 2>/dev/null || :
  [ -z "$WATCH_PID" ] || kill "$WATCH_PID" 2>/dev/null || :
}
trap 'cleanup; exit 130' INT TERM
trap cleanup EXIT
# Every device command is bounded. stdout remains available to the caller; errors are retained.
device() {
  [ -z "$DRY" ] || { echo 'Internal error: device call in dry-run' >&2; return 99; }
  adb "$@" > "$OUTPUT/command.out" 2> "$OUTPUT/command.err" & DEVICE_PID=$!
  ( sleep 20; kill "$DEVICE_PID" 2>/dev/null ) & WATCH_PID=$!
  wait "$DEVICE_PID"; rc=$?
  kill "$WATCH_PID" 2>/dev/null || :; wait "$WATCH_PID" 2>/dev/null || :
  DEVICE_PID= WATCH_PID=
  cat "$OUTPUT/command.out"
  if [ "$rc" -ne 0 ]; then cat "$OUTPUT/command.err" >&2; fi
  return "$rc"
}
quote_goal() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
broadcast() { device shell am broadcast -a com.saathi.GOAL -p com.saathi.app "$@" >/dev/null; }
now() { if [ -n "$DRY" ]; then printf '%s\n' "$NOW"; else echo $(( ($(date +%s) - START) * 1000 )); fi; }
meta() { printf '%s.000 I E2E: %s\n' "$(date +%s)" "$1" >> "$CASE/meta.log"; }
refresh() {
  [ -z "$DRY" ] || return 0
  device logcat -d -v epoch -s SaathiLog:I '*:S' > "$CASE/logcat.log" || return 1
  # A fixed origin prevents relative times changing as new logcat lines arrive.
  { printf '%s.000 I E2E: end\n' "$START"; cat "$CASE/logcat.log"; } | awk -f "$DIR/logs.awk" | awk -F '\t' '$2=="log"' > "$CASE/logs.tsv"
}
health_enabled() {
  if [ -n "$DRY" ]; then
    value=$(awk -F '\t' -v t="$NOW" '$2=="enabled" && $1<=t {v=$3} END {print v}' "$CASE/events.tsv")
    [ "$value" = 1 ]
  else
    services=$(device shell settings get secure enabled_accessibility_services) || return 1
    if printf '%s\n' "$services" | tr ':\r' '\n\n' | grep -Eq '^com\.saathi\.app/(com\.saathi\.app\.service\.SaathiService|\.service\.SaathiService)$'; then meta 'enabled=1'; return 0; fi
    meta 'enabled=0'; return 1
  fi
}
mark_action() {
  refresh || { REASON='Could not read logcat'; return 1; }
  if [ -n "$DRY" ]; then
    CURSOR=$(awk -F '\t' -v t="$NOW" '$1<=t {n=NR} END {print n+0}' "$CASE/logs.tsv")
  else CURSOR=$(wc -l < "$CASE/logs.tsv" | tr -d ' '); fi
  LAST_SHOW=
}
advance() { # dry-run time cannot extend beyond recorded observation
  target=$1
  if [ -n "$DRY" ]; then
    [ "$target" -le "$END_MS" ] || { REASON='Replay ends before observation deadline'; return 1; }
    NOW=$target
  else sleep "$(( (target - $(now) + 999) / 1000 ))"; fi
}
check_log() {
  expr=$1; duration=$2; negative=$3; deadline=$(( $(now) + duration * 1000 ))
  while :; do
    refresh || { REASON='Could not read logcat'; return 1; }
    horizon=$(now); [ -z "$DRY" ] || horizon=$deadline
    [ "$horizon" -le "$deadline" ] || horizon=$deadline
    hit=$(awk -F '\t' -v c="$CURSOR" -v h="$horizon" 'NR>c && $1<=h {print NR "\t" $0}' "$CASE/logs.tsv" | grep -E -e "$expr" | head -1)
    if [ -n "$hit" ]; then
      line=$(printf '%s\n' "$hit" | cut -f1); when=$(printf '%s\n' "$hit" | cut -f2)
      if [ "$negative" = yes ]; then REASON="Forbidden log matched: $expr"; [ -z "$DRY" ] || NOW=$when; return 1; fi
      CURSOR=$line; [ -z "$DRY" ] || NOW=$when
      LAST_SHOW=$(printf '%s\n' "$hit" | cut -f4-)
      case "$LAST_SHOW" in '[show]'*) :;; *) LAST_SHOW=;; esac
      return 0
    fi
    if [ -n "$DRY" ]; then
      advance "$deadline" || return 1
      if [ "$negative" = yes ]; then return 0; fi
      REASON="Timed out waiting for: $expr"; return 1
    fi
    if [ "$(now)" -ge "$deadline" ]; then
      [ "$negative" = no ] || return 0
      REASON="Timed out waiting for: $expr"; return 1
    fi
    sleep 1
  done
}
check_focus() {
  expr=$1; deadline=$(( $(now) + $2 * 1000 ))
  if [ -n "$DRY" ]; then
    current=$(awk -F '\t' -v t="$NOW" '$2=="focus" && $1<=t {v=$3} END {print v}' "$CASE/events.tsv")
    if [ -n "$current" ] && printf '%s\n' "$current" | grep -Eq -e "$expr"; then return 0; fi
    hit=$(awk -F '\t' -v t="$NOW" -v d="$deadline" '$2=="focus" && $1>t && $1<=d' "$CASE/events.tsv" | grep -E -e "$expr" | head -1)
    if [ -n "$hit" ]; then NOW=$(printf '%s\n' "$hit" | cut -f1); return 0; fi
    REASON="No focus evidence matching: $expr"; return 1
  fi
  while :; do
    focus=$(device shell dumpsys window | grep 'mCurrentFocus' | head -1) || { REASON='Could not read focus'; return 1; }
    meta "focus=$focus"
    if [ "$(now)" -le "$deadline" ] && printf '%s\n' "$focus" | grep -Eq -e "$expr"; then return 0; fi
    [ "$(now)" -lt "$deadline" ] || { REASON="Focus timeout: $expr"; return 1; }
    sleep 1
  done
}
screenshot() {
  if [ -n "$DRY" ]; then printf 'No screenshot captured: saved-log replay only.\n' > "$CASE/$1.txt"; return 0; fi
  device shell screencap -p > "$CASE/$1.png"
}
fresh_target() {
  refresh || { REASON='Could not recheck target'; return 1; }
  if [ -n "$DRY" ]; then
    latest=$(awk -F '\t' -v t="$NOW" '$1<=t && $3 ~ /^\[show\]/ {v=$3} END {print v}' "$CASE/logs.tsv")
  else latest=$(awk -F '\t' '$3 ~ /^\[show\]/ {v=$3} END {print v}' "$CASE/logs.tsv"); fi
  [ "$latest" = "$LAST_SHOW" ] || { REASON='Show target changed; refusing stale tap'; return 1; }
}
manual_safe() {
  [ -n "$LAST_SHOW" ] || { REASON='No fresh show target; refusing stale tap'; return 1; }
  fresh_target || return 1
  if printf '%s\n' "$LAST_SHOW" | grep -Eiq 'key=(send|pay|call|video|install|uninstall|delete|buy|book|submit|grant|allow)( |$)|el="(send|pay|call|dial|install|uninstall|delete|buy|submit|allow|भेज|भुगतान|कॉल|इंस्टॉल|పంపు|కాల్|ఇన్‌స్టాల్)|noAct=true'; then
    REASON='Refusing a consequential or noAct target'; return 1
  fi
}
tap_glow() {
  manual_safe || return 1
  coords=$(printf '%s\n' "$LAST_SHOW" | sed -n 's/.*bounds=\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]\[\([0-9][0-9]*\),\([0-9][0-9]*\)\].*/\1 \2 \3 \4/p')
  [ -n "$coords" ] || { REASON='Missing or malformed glow bounds'; return 1; }
  set -- $coords
  [ "$3" -gt "$1" ] && [ "$4" -gt "$2" ] && [ "$3" -le 10000 ] && [ "$4" -le 10000 ] || { REASON='Invalid glow rectangle'; return 1; }
  x=$(( ($1+$3)/2 )); y=$(( ($2+$4)/2 ))
  printf 'tap-glow %s %s\n' "$x" "$y" >> "$CASE/actions.txt"
  mark_action || return 1
  [ -n "$DRY" ] || device shell input tap "$x" "$y" >/dev/null
}
run_step() {
  op=$1; arg=$2; sec=$3
  case "$op" in
    name|apps) return 0;;
    show) check_log "\[show\] key=($arg)( |$)" "$sec" no;;
    log) check_log "$arg" "$sec" no;;
    not-log) check_log "$arg" "$sec" yes;;
    focus) check_focus "$arg" "$sec";;
    enabled) health_enabled || { REASON='Saathi switched off (or replay has no enabled evidence)'; return 1; };;
    wait) advance "$(( $(now) + sec * 1000 ))";;
    screenshot) screenshot "$arg";;
    tap-glow) tap_glow;;
    doit|cmd)
      if [ "$op" = doit ] || [ "$arg" = doit ]; then manual_safe || return 1; arg=doit; fi
      mark_action || return 1
      [ -n "$DRY" ] || broadcast --es cmd "$arg";;
    say)
      language=${arg%% *}; text=${arg#* }; mark_action || return 1
      [ -n "$DRY" ] || broadcast --es lang "$language" --es goal "$(quote_goal "$text")";;
    eval) mark_action || return 1; [ -n "$DRY" ] || broadcast --es cmd eval --es goal "$(quote_goal "$arg")";;
    home|back) mark_action || return 1; [ -n "$DRY" ] || device shell input keyevent "KEYCODE_$(printf '%s' "$op" | tr 'a-z' 'A-Z')" >/dev/null;;
    start-activity) mark_action || return 1; [ -n "$DRY" ] || device shell am start -a "$arg" >/dev/null;;
    tap-choice)
      [ -n "$PHONE_CHOICE" ] || { REASON='Phone choice needs --phone-choice X,Y calibrated from screenshot'; return 1; }
      case "$LAST_SHOW" in '[show] key=choose_video '*) :;; *) REASON='No fresh choose_video card'; return 1;; esac
      fresh_target || return 1
      printf 'tap-choice phone %s\n' "$PHONE_CHOICE" >> "$CASE/actions.txt"
      screenshot phone-choice-before || return 1
      mark_action || return 1
      [ -n "$DRY" ] || device shell input tap "${PHONE_CHOICE%,*}" "${PHONE_CHOICE#*,}" >/dev/null;;
    *) REASON='Internal unknown command'; return 1;;
  esac
}
printf '# E2E %s\n\n| Result | Scenario | Seconds | Reason |\n| --- | --- | ---: | --- |\n' "${DRY:+saved-log replay}" > "$OUTPUT/summary.md"
PASS=0; FAIL=0
for base in "${SELECTED[@]}"; do
  CASE="$OUTPUT/$base"; mkdir -p "$CASE"; : > "$CASE/meta.log"; : > "$CASE/actions.txt"
  REASON= CURSOR=0 NOW=0 LAST_SHOW=; START=$(date +%s); failed=0; step=reset
  if [ -n "$DRY" ]; then
    cp "$OUTPUT/replay.tsv" "$CASE/events.tsv"
    awk -F '\t' '$2=="log"' "$CASE/events.tsv" > "$CASE/logs.tsv"
    END_MS=$(awk -F '\t' 'BEGIN {m=0} $1>m {m=$1} END {print m}' "$CASE/events.tsv")
  else
    health_enabled || { REASON='Saathi switched off before reset'; failed=1; }
    if [ "$failed" -eq 0 ]; then
      broadcast --es cmd stop && device shell input keyevent KEYCODE_HOME >/dev/null || { REASON='Reset failed'; failed=1; }
      apps=$(awk -F '\t' '$2=="apps" {print $3}' "$PLAN/$base.tsv")
      for app in $apps; do device shell am force-stop "$app" >/dev/null || { REASON='App reset failed'; failed=1; }; done
      device logcat -c || { REASON='Log reset failed'; failed=1; }
      START=$(date +%s); : > "$CASE/meta.log"; health_enabled || { REASON='Saathi switched off during reset'; failed=1; }
    fi
  fi
  if [ "$failed" -eq 0 ]; then
    while IFS="$(printf '\t')" read -r ln op arg sec; do
      step="$ln: $op $arg"
      if ! run_step "$op" "$arg" "${sec:-0}"; then
        [ -n "$REASON" ] || REASON="Command failed: $op"
        failed=1; break
      fi
    done < "$PLAN/$base.tsv"
  fi
  refresh || { REASON='Final log capture failed'; failed=1; }
  if grep -Eiq '\[(crash|fatal)\]|FATAL EXCEPTION' "$CASE/logs.tsv"; then REASON='Crash in scenario log'; failed=1; step='post-scenario log audit'; fi
  if grep -Eq '\[service\] destroyed' "$CASE/logs.tsv"; then REASON='Saathi switched off: service destroyed'; failed=1; step='post-scenario health'; fi
  if [ -n "$DRY" ]; then
    last_enabled=$(awk -F '\t' '$2=="enabled" {t=$1; v=$3} END {print t " " v}' "$CASE/events.tsv")
    set -- $last_enabled
    if [ "$#" -ne 2 ] || [ "$2" != 1 ] || [ "$1" -lt "$NOW" ]; then REASON='Saathi switched off (or missing final enabled evidence)'; failed=1; step='post-scenario health'; fi
  else
    health_enabled || { REASON='Saathi switched off'; failed=1; step='post-scenario health'; }
    screenshot final || { REASON='Screenshot capture failed'; failed=1; }
    meta end
    cat "$CASE/logcat.log" "$CASE/meta.log" | LC_ALL=C sort -s -n -k1,1 > "$CASE/replay.log"
  fi
  [ -z "$DRY" ] || screenshot final
  tail -15 "$CASE/logs.tsv" > "$CASE/last15.log"
  elapsed=$(( $(now) / 1000 ))
  if [ "$failed" -eq 0 ]; then status='✅'; PASS=$((PASS+1)); REASON=PASS; step=none
  else status='❌'; FAIL=$((FAIL+1)); fi
  printf 'status=%s\nfailing_step=%s\nreason=%s\n' "$status" "$step" "$REASON" > "$CASE/result.txt"
  reason_md=$(printf '%s' "$REASON" | sed 's/|/\\|/g')
  printf '| %s | %s | %s | %s |\n' "$status" "$base" "$elapsed" "$reason_md" >> "$OUTPUT/summary.md"
done
cat "$OUTPUT/summary.md"
printf '\n%s passed, %s failed. Evidence: %s\n' "$PASS" "$FAIL" "$OUTPUT"
[ "$FAIL" -eq 0 ]
