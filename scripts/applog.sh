#!/usr/bin/env bash
# Saathi's own log file on the phone (this phone's logcat sometimes drops the main process). scripts/applog.sh [N]
cd "$(dirname "$0")/.." && source scripts/env.sh
adb shell run-as "$PKG" tail -${1:-30} "files/logs/saathi-$(date +%Y%m%d).log"
