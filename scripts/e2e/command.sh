# Source-only Bash 3.2 helper. Tests use local shell commands, never a device.
# bounded_command seconds stdout-file stderr-file command [args ...]
bounded_command() (
  # A subshell isolates status/PIDs from scenario variables and command substitutions.
  limit=$1; out=$2; err=$3; shift 3
  child= timer=
  finish_command() {
    [ -z "$timer" ] || kill "$timer" 2>/dev/null || :
    [ -z "$timer" ] || wait "$timer" 2>/dev/null || :
    [ -z "$child" ] || kill -KILL "$child" 2>/dev/null || :
  }
  trap finish_command EXIT
  trap 'exit 130' INT TERM
  timeout_flag="${err}.timeout"
  # Caller provides fresh/per-command files, but an earlier timeout must not carry forward.
  : > "$timeout_flag"
  "$@" </dev/null >"$out" 2>"$err" & child=$!
  (
    sleeper=
    trap '[ -z "$sleeper" ] || kill "$sleeper" 2>/dev/null; exit 0' TERM INT
    sleep "$limit" & sleeper=$!; wait "$sleeper" || exit 0
    printf 'timeout\n' > "$timeout_flag"
    kill -TERM "$child" 2>/dev/null || exit 0
    sleep 1 & sleeper=$!; wait "$sleeper" || exit 0
    kill -KILL "$child" 2>/dev/null || :
  ) & timer=$!
  if wait "$child"; then status=0; else status=$?; fi
  child=
  kill "$timer" 2>/dev/null || :
  wait "$timer" 2>/dev/null || :
  timer=
  if [ -s "$timeout_flag" ]; then
    printf 'Command timed out after %s seconds\n' "$limit" >> "$err"
    status=124
  fi
  cat "$out"
  [ "$status" -eq 0 ] || cat "$err" >&2
  exit "$status"
)

# Return a bounded transport allowance; the outer quick run has a five-second margin under six minutes.
quick_command_limit() {
  local remaining
  remaining=$((355 - $1))
  [ "$remaining" -gt 0 ] || return 124
  if [ "$remaining" -lt 8 ]; then printf '%s\n' "$remaining"; else printf '8\n'; fi
}
