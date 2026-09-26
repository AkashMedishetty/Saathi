#!/usr/bin/env bash
# Give Saathi a goal without touching the phone (debug builds only).
#   scripts/say.sh "make the text bigger" [EN|HI|TE]
#   scripts/say.sh --doit | --stop | --aura on|off | --monitor on|off
cd "$(dirname "$0")/.." && source scripts/env.sh
A=(shell am broadcast -a com.saathi.GOAL -p "$PKG")
case "$1" in
  --doit) adb "${A[@]}" --es cmd doit >/dev/null ;;
  --stop) adb "${A[@]}" --es cmd stop >/dev/null ;;
  --dump) adb logcat -c; adb "${A[@]}" --es cmd dump >/dev/null; sleep 1; adb logcat -d -s SaathiDump:I | sed "s/^.*SaathiDump: //"; exit ;;
  --ask) adb "${A[@]}" --es cmd ask --ez listen "$([ "$2" = listen ] && echo true || echo false)" >/dev/null ;;
  --log-from) ms=$(date -j -f "%Y-%m-%d %H:%M" "$(date +%Y-%m-%d) ${2:-14:00}" +%s)000; adb "${A[@]}" --es cmd log_from --el ms "$ms" >/dev/null ;;
  --aura) adb "${A[@]}" --es cmd aura --ez on "$([ "$2" = off ] && echo false || echo true)" >/dev/null ;;
  --monitor) adb "${A[@]}" --es cmd monitor --ez on "$([ "$2" = off ] && echo false || echo true)" >/dev/null ;;
  *)
    # adb shell re-splits the string: quote it for the device shell.
    goal=$(printf '%s' "$1" | sed "s/'/'\\\\''/g")
    extra=(); [ -n "$2" ] && extra=(--es lang "$2")
    adb "${A[@]}" --es goal "'$goal'" "${extra[@]}" >/dev/null ;;
esac
echo "sent: ${1} ${2:-}"
