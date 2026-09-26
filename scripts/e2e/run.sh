#!/usr/bin/env bash
# macOS Bash 3.2 + POSIX awk/grep/sed. No eval, downloaded tools, or implicit device setup.
set -u
set -o pipefail
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$DIR/../.." && pwd)
DRY= OUTPUT= SCENARIOS="$DIR/scenarios" PATTERN='*' PHONE_CHOICE= QUICK=0 LIST=0
SUITE_START=$SECONDS
. "$DIR/command.sh"
usage() {
  cat <<'HELP'
Usage: scripts/e2e/run.sh [--quick] [pattern] [--dry-run LOGFILE] [--output DIR]
       [--scenarios DIR] [--phone-choice X,Y]
Pattern is a shell glob matched against scenario filenames/stems, not a regex.
--dry-run never invokes adb or sources device environment scripts.
--quick selects eight demos (two mutually exclusive Hotstar cases); execution stops at 210s, transport/evidence by 235s.
--list validates and prints selected names without device access.
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
    --list) LIST=1; shift;;
    --quick) QUICK=1; shift;;
    --help|-h) usage; exit 0;;
    --*) printf 'Unknown option: %s\n' "$1" >&2; exit 2;;
    *) PATTERN=$1; shift;;
  esac
done
[ "$QUICK" -eq 0 ] || SCENARIOS="$DIR/quick"
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
      requires-installed|requires-missing) allowed_app "$arg" || { echo "Precondition package not allowed: $arg" >&2; exit 2; };;
      apps) for app in $arg; do allowed_app "$app" || { echo "Force-stop not allowed: $app" >&2; exit 2; }; done;;
      show|log|focus|not-log)
        printf '' | grep -E -e "$arg" >/dev/null 2>&1
        rc=$?; [ "$rc" -ne 2 ] || { echo "Invalid regex at $base:$ln" >&2; exit 2; };;
    esac
  done < "$PLAN/$base.tsv"
  SELECTED+=("$base")
done
[ "${#SELECTED[@]}" -gt 0 ] || { echo 'No matching scenarios' >&2; exit 2; }
if [ "$LIST" -eq 1 ]; then printf '%s\n' "${SELECTED[@]}"; exit 0; fi
if [ -n "$DRY" ]; then
  awk -f "$DIR/logs.awk" "$DRY" > "$OUTPUT/replay.tsv" || exit 2
else
  # The existing env file sets SDK paths and the serial. It is never read in replay mode.
  . "$ROOT/scripts/env.sh"
  command -v adb >/dev/null || { echo 'adb unavailable' >&2; exit 2; }
fi
# Cleanup does not navigate or reset apps; navigation occurs only after each case is audited.
trap 'exit 130' INT TERM
# The bounded helper isolates wait status from watchdog status and closes stdin to adb.
device() {
  local limit status
  [ -z "$DRY" ] || { echo 'Internal error: device call in dry-run' >&2; return 99; }
  limit=20
  if [ "$QUICK" -eq 1 ]; then
    limit=$(quick_command_limit "$((SECONDS - SUITE_START))") || { echo 'Quick suite deadline reached' >&2; return 124; }
  fi
  if bounded_command "$limit" "$OUTPUT/command.out" "$OUTPUT/command.err" adb "$@"; then status=0; else status=$?; fi
  return "$status"
}
quick_expired() { [ "$QUICK" -eq 1 ] && [ -z "$DRY" ] && [ $((SECONDS - SUITE_START)) -ge 210 ]; }
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
  HEALTH_REASON=
  if [ -n "$DRY" ]; then
    value=$(awk -F '\t' -v t="$NOW" '$2=="enabled" && $1<=t {v=$3} END {print v}' "$CASE/events.tsv")
    [ "$value" = 1 ]
  else
    services=$(device shell settings get secure enabled_accessibility_services) || { HEALTH_REASON='Could not query Saathi enabled state'; return 2; }
    if printf '%s\n' "$services" | tr ':\r' '\n\n' | grep -Eq '^com\.saathi\.app/(com\.saathi\.app\.service\.SaathiService|\.service\.SaathiService)$'; then meta 'enabled=1'; return 0; fi
    meta 'enabled=0'; return 1
  fi
}
# A health sample can land inside the same short system/guard restart window.
ensure_enabled() {
  local rc deadline recovered
  if health_enabled; then return 0; else rc=$?; fi
  [ "$rc" -eq 1 ] || return "$rc"
  if [ -n "$DRY" ]; then
    [ "${value:-}" = 0 ] || return 1
    recovered=$(awk -F '\t' -v t="$NOW" '$2=="enabled" && $1>t && $1<=t+2000 && $3==1 {print $1; exit}' "$CASE/events.tsv")
    [ -n "$recovered" ] || return 1
    NOW=$recovered
    return 0
  fi
  deadline=$((SECONDS+2))
  while [ "$SECONDS" -lt "$deadline" ]; do
    sleep 1
    if health_enabled; then return 0; else rc=$?; fi
    [ "$rc" -eq 1 ] || return "$rc"
  done
  return 1
}
# Preconditions are read-only and run before scenario actions, health/reset, or cleanup.
check_package() {
  local pkg=$2 state listing
  if [ -n "$DRY" ]; then
    state=$(awk -F '\t' -v p="$pkg" -v t="$NOW" '$2=="package" && $1<=t {split($3,a," "); if(a[1]==p) v=a[2]} END {print v}' "$CASE/events.tsv")
    [ -n "$state" ] || { REASON="Missing package evidence: $pkg"; return 1; }
  else
    listing=$(device shell pm list packages "$pkg") || { REASON="Could not query package: $pkg"; return 1; }
    # pm's empty successful result means absent; errors must never become SKIP.
    if printf '%s\n' "$listing" | tr -d '\r' | grep -Ev '^(package:[A-Za-z0-9_.]+)?$' | grep -q .; then
      REASON="Invalid package query response: $pkg"; return 1
    fi
    if printf '%s\n' "$listing" | tr -d '\r' | grep -Fxq "package:$pkg"; then state=installed; else state=missing; fi
    meta "package=$pkg $state"
  fi
  if [ "$state" != "${1#requires-}" ]; then
    REASON="SKIP: requires ${1#requires-} $pkg (actual: $state)"; skipped=1
  fi
}
audit_service() {
  local result horizon
  while :; do
    horizon=$(now); [ -z "$DRY" ] || horizon=$END_MS
    result=$(awk -v horizon="$horizon" -f "$DIR/service.awk" "$CASE/logs.tsv")
    case "$result" in
      ok) return 0;;
      healed) NOTE=healed; return 0;;
      failed) REASON='Saathi switched off: service destroyed without reconnect within 2 s'; return 1;;
      pending)
        [ -z "$DRY" ] || { REASON='Replay ends before service reconnect deadline'; return 1; }
        quick_expired && { REASON='Quick budget ended before reconnect audit'; return 1; }
        sleep 1
        refresh || { REASON='Could not read reconnect evidence'; return 1; };;
    esac
  done
}
mark_action() {
  refresh || { REASON='Could not read logcat'; return 1; }
  if [ -n "$DRY" ]; then
    CURSOR=$(awk -F '\t' -v t="$NOW" '$1<=t {n=NR} END {print n+0}' "$CASE/logs.tsv")
  else CURSOR=$(wc -l < "$CASE/logs.tsv" | tr -d ' '); fi
  LAST_SHOW= LAST_CHOICE=
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
    quick_expired && { REASON='Quick suite execution budget exhausted'; return 1; }
    refresh || { REASON='Could not read logcat'; return 1; }
    horizon=$(now); [ -z "$DRY" ] || horizon=$deadline
    [ "$horizon" -le "$deadline" ] || horizon=$deadline
    hit=$(awk -F '\t' -v c="$CURSOR" -v h="$horizon" 'NR>c && $1<=h {print NR "\t" $0}' "$CASE/logs.tsv" | grep -E -e "$expr" | head -1)
    if [ -n "$hit" ]; then
      line=$(printf '%s\n' "$hit" | cut -f1); when=$(printf '%s\n' "$hit" | cut -f2)
      if [ "$negative" = yes ]; then REASON="Forbidden log matched: $expr"; [ -z "$DRY" ] || NOW=$when; return 1; fi
      CURSOR=$line; [ -z "$DRY" ] || NOW=$when
      LAST_CHOICE=$(printf '%s\n' "$hit" | cut -f4-)
      case "$LAST_CHOICE" in '[choice] choose_video:'*) :;; *) LAST_CHOICE=;; esac
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
    quick_expired && { REASON='Quick suite execution budget exhausted'; return 1; }
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
  if printf '%s\n' "$LAST_SHOW" | grep -Eiq 'key=map_wa_((video|voice)_call_[34]|message_4)( |$)|key=(.*_)?(send|pay|call|video|install|uninstall|delete|buy|book|submit|grant|allow)( |$)|el="(send|pay|call|dial|install|uninstall|delete|buy|submit|allow|भेज|भुगतान|कॉल|इंस्टॉल|పంపు|కాల్|ఇన్‌స్టాల్)'; then
    REASON='Refusing a consequential target'; return 1
  fi
  if printf '%s\n' "$LAST_SHOW" | grep -q 'noAct=true'; then
    if [ "${1:-}" != human ] || ! printf '%s\n' "$LAST_SHOW" | grep -Eq 'key=(.*open_search|.*search|.*result|map_settings_(ringtone|font)_[02]) .*pkg=com.android.settings'; then
      REASON='Refusing a noAct target'; return 1
    fi
  fi
}
tap_glow() {
  manual_safe human || return 1
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
    name|apps|requires-installed|requires-missing) return 0;;
    bounds-top)
      [ -n "$LAST_SHOW" ] || { REASON='No show target for bounds check'; return 1; }
      fresh_target || return 1
      top=$(printf '%s\n' "$LAST_SHOW" | sed -n 's/.*bounds=\[[0-9][0-9]*,\([0-9][0-9]*\)\]\[[0-9][0-9]*,[0-9][0-9]*\].*/\1/p')
      [ -n "$top" ] && [ "$top" -lt "$arg" ] || { REASON="Glow bounds top must be < $arg (actual: ${top:-missing})"; return 1; };;
    show) check_log "\[show\] key=($arg)( |$)" "$sec" no;;
    log) check_log "$arg" "$sec" no;;
    not-log) check_log "$arg" "$sec" yes;;
    focus) check_focus "$arg" "$sec";;
    enabled) ensure_enabled || { REASON='Saathi switched off (or replay has no enabled evidence)'; return 1; };;
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
    open-url) mark_action || return 1; [ -n "$DRY" ] || device shell am start -a android.intent.action.VIEW -d "$arg" -p com.android.chrome >/dev/null;;
    start-activity) mark_action || return 1; [ -n "$DRY" ] || device shell am start -a "$arg" >/dev/null;;
    tap-choice)
      [ -n "$PHONE_CHOICE" ] || { REASON='Phone choice needs --phone-choice X,Y calibrated from screenshot'; return 1; }
      if [ -n "$LAST_CHOICE" ]; then
        refresh || return 1
        latest_choice=$(awk -F '\t' -v t="${NOW:-0}" -v dry="$DRY" '(dry=="" || $1<=t) && $3 ~ /^\[(choice|show)\]/ {v=$3} END {print v}' "$CASE/logs.tsv")
        [ "$latest_choice" = "$LAST_CHOICE" ] || { REASON='Choice card changed'; return 1; }
      else
        case "$LAST_SHOW" in '[show] key=choose_video '*) :;; *) REASON='No fresh choose_video card'; return 1;; esac
        fresh_target || return 1
      fi
      printf 'tap-choice phone %s\n' "$PHONE_CHOICE" >> "$CASE/actions.txt"
      screenshot phone-choice-before || return 1
      mark_action || return 1
      [ -n "$DRY" ] || device shell input tap "${PHONE_CHOICE%,*}" "${PHONE_CHOICE#*,}" >/dev/null;;
    *) REASON='Internal unknown command'; return 1;;
  esac
}
printf '# E2E %s\n\n| Result | Scenario | Seconds | Reason |\n| --- | --- | ---: | --- |\n' "${DRY:+saved-log replay}" > "$OUTPUT/summary.md"
PASS=0; FAIL=0; SKIP=0
for base in "${SELECTED[@]}"; do
  if quick_expired; then
    mkdir -p "$OUTPUT/$base"
    printf 'status=❌\nfailing_step=not run\nreason=Quick suite execution budget exhausted\n' > "$OUTPUT/$base/result.txt"
    printf '| ❌ | %s | 0 | Not run: quick suite execution budget exhausted |\n' "$base" >> "$OUTPUT/summary.md"
    FAIL=$((FAIL+1)); continue
  fi
  CASE="$OUTPUT/$base"; mkdir -p "$CASE"; : > "$CASE/meta.log"; : > "$CASE/actions.txt"
  REASON= NOTE= CURSOR=0 NOW=0 LAST_SHOW= LAST_CHOICE=; skipped=0; START=$(date +%s); failed=0; step=reset
  if [ -n "$DRY" ]; then
    cp "$OUTPUT/replay.tsv" "$CASE/events.tsv"
    awk -F '\t' '$2=="log"' "$CASE/events.tsv" > "$CASE/logs.tsv"
    END_MS=$(awk -F '\t' 'BEGIN {m=0} $1>m {m=$1} END {print m}' "$CASE/events.tsv")
  fi
  while IFS="$(printf '\t')" read -r ln op arg sec; do
    case "$op" in requires-installed|requires-missing)
      if ! check_package "$op" "$arg"; then failed=1; fi
      [ "$failed" -eq 0 ] && [ "$skipped" -eq 0 ] || break;;
    esac
  done < "$PLAN/$base.tsv"
  if [ "$skipped" -eq 1 ] || [ "$failed" -eq 1 ]; then
    if [ "$skipped" -eq 1 ]; then status='⏭'; SKIP=$((SKIP+1)); else status='❌'; FAIL=$((FAIL+1)); fi
    if [ -z "$DRY" ]; then meta end; cp "$CASE/meta.log" "$CASE/replay.log"; fi
    printf 'status=%s\nfailing_step=precondition\nreason=%s\n' "$status" "$REASON" > "$CASE/result.txt"
    printf '| %s | %s | 0 | %s |\n' "$status" "$base" "$REASON" >> "$OUTPUT/summary.md"
    continue
  fi
  if [ -z "$DRY" ]; then
    ensure_enabled || { REASON='Saathi switched off before scenario'; failed=1; }
    if [ "$failed" -eq 0 ]; then
      device logcat -c || { REASON='Log reset failed'; failed=1; }
      START=$(date +%s)
      ensure_enabled || { REASON='Saathi switched off before scenario'; failed=1; }
    fi
  fi
  if [ "$failed" -eq 0 ]; then
    while IFS="$(printf '\t')" read -r ln op arg sec; do
      step="$ln: $op $arg"
      quick_expired && { REASON='Quick suite execution budget exhausted'; failed=1; break; }
      printf '%s\t%s\n' "$(now)" "$step" >> "$CASE/steps.log"
      if ! run_step "$op" "$arg" "${sec:-0}"; then
        [ -n "$REASON" ] || REASON="Command failed: $op"
        failed=1; break
      fi
      printf '%s\tPASS %s\n' "$(now)" "$step" >> "$CASE/steps.log"
    done < "$PLAN/$base.tsv"
  fi
  if [ -z "$DRY" ]; then
    ensure_enabled || { REASON=${HEALTH_REASON:-'Saathi switched off'}; failed=1; step='post-scenario health'; }
  fi
  refresh || { [ "$failed" -ne 0 ] || { REASON='Final log capture failed'; failed=1; step='final capture'; }; }
  audit_service || { failed=1; step='post-scenario health'; }
  if grep -Eiq '\[(crash|fatal)\]|FATAL EXCEPTION' "$CASE/logs.tsv"; then REASON='Crash in scenario log'; failed=1; step='post-scenario log audit'; fi
  if awk -F '\t' '$3 ~ /^\[wall\].*setup=true/ {wall=1} wall && $3 ~ /^\[plan\]/ {bad=1} END {exit !bad}' "$CASE/logs.tsv"; then
    REASON='Planner ran after a setup login wall'; failed=1; step='post-scenario wall audit'
  fi
  if [ -n "$DRY" ]; then
    last_enabled=$(awk -F '\t' '$2=="enabled" {t=$1; v=$3} END {print t " " v}' "$CASE/events.tsv")
    set -- $last_enabled
    if [ "$#" -ne 2 ] || [ "$2" != 1 ] || [ "$1" -lt "$NOW" ]; then REASON='Saathi switched off (or missing final enabled evidence)'; failed=1; step='post-scenario health'; fi
  else
    screenshot final || { REASON='Screenshot capture failed'; failed=1; }
    meta end
    cat "$CASE/logcat.log" "$CASE/meta.log" | LC_ALL=C sort -s -n -k1,1 > "$CASE/replay.log"
  fi
  [ -z "$DRY" ] || screenshot final
  # No HOME/force-stop occurs until checks + final evidence above have finished.
  if [ -z "$DRY" ] && ! quick_expired; then
    printf '%s\tcleanup after checks\n' "$(now)" >> "$CASE/steps.log"
    if ! broadcast --es cmd stop || ! device shell input keyevent KEYCODE_HOME >/dev/null; then
      [ "$failed" -ne 0 ] || { REASON='Post-scenario cleanup failed'; failed=1; step=cleanup; }
    fi
    apps=$(awk -F '\t' '$2=="apps" {print $3}' "$PLAN/$base.tsv")
    for app in $apps; do
      if ! device shell am force-stop "$app" >/dev/null; then
        [ "$failed" -ne 0 ] || { REASON='Post-scenario app cleanup failed'; failed=1; step=cleanup; }
      fi
    done
  fi
  tail -15 "$CASE/logs.tsv" > "$CASE/last15.log"
  elapsed=$(( $(now) / 1000 ))
  if [ "$failed" -eq 0 ]; then status='✅'; PASS=$((PASS+1)); REASON="PASS${NOTE:+ ($NOTE)}"; step=none
  else status='❌'; FAIL=$((FAIL+1)); fi
  printf 'status=%s\nfailing_step=%s\nreason=%s\n' "$status" "$step" "$REASON" > "$CASE/result.txt"
  reason_md=$(printf '%s' "$REASON" | sed 's/|/\\|/g')
  printf '| %s | %s | %s | %s |\n' "$status" "$base" "$elapsed" "$reason_md" >> "$OUTPUT/summary.md"
done
cat "$OUTPUT/summary.md"
printf '\n%s passed, %s failed, %s skipped. Evidence: %s\n' "$PASS" "$FAIL" "$SKIP" "$OUTPUT"
[ "$FAIL" -eq 0 ]
