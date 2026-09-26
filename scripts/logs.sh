#!/usr/bin/env bash
source "$(dirname "$0")/env.sh"
adb logcat -c; adb logcat -v time Saathi:V SaathiLLM:V AndroidRuntime:E litert:V '*:S'
