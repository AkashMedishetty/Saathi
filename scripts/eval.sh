#!/usr/bin/env bash
# Broad understanding eval: every line of scripts/eval-set.tsv through Saathi's real decision path (nothing is acted on).
# Prints per-case result + accuracy. Needs the model loaded (it warms it first).
cd "$(dirname "$0")/.." && source scripts/env.sh
B() { adb shell am broadcast -a com.saathi.GOAL -p "$PKG" "$@" >/dev/null </dev/null; }
adb logcat -c </dev/null; B --es goal "'hello'"
for i in $(seq 1 60); do adb logcat -d -s SaathiLog:I </dev/null | grep -q "\[answer\]" && break; sleep 1; done
B --es cmd stop; adb logcat -c </dev/null
n=0; ok=0; fails=()
while IFS=$'\t' read -r utt lang want; do
  [[ -z "$utt" || "$utt" == \#* ]] && continue
  n=$((n+1)); g=$(printf '%s' "$utt" | sed "s/'/'\\\\''/g")
  B --es lang "$lang" --es cmd noop
  adb logcat -c </dev/null; B --es cmd eval --es goal "'$g'"
  got=""; for i in $(seq 1 60); do got=$(adb logcat -d -s SaathiEval:I </dev/null | sed 's/^.*SaathiEval: //' | tail -1); [ -n "$got" ] && break; sleep 0.5; done
  label=$(echo "$got" | cut -f2); ms=$(echo "$got" | cut -f3); brain=$(echo "$got" | cut -f4)
  if echo "|$want|" | grep -q "|$label|"; then ok=$((ok+1)); echo "✅ $utt → $label (${ms}ms ${brain})"; else fails+=("$utt → $label (want $want)"); echo "❌ $utt → $label  (want $want) [${brain}]"; fi
done < scripts/eval-set.tsv
B --es cmd noop --es lang EN
echo "── understanding accuracy: $ok / $n = $(( ok * 100 / n ))%"
