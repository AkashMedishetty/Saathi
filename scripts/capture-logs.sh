#!/usr/bin/env bash
# Laptop-side logcat capture starting at HH:MM (default 14:00), reconnecting if the USB cable drops.
cd "$(dirname "$0")/.." && source scripts/env.sh
at="${1:-14:00}"; mkdir -p logs
while [ "$(date +%H:%M)" \< "$at" ]; do sleep 20; done
while true; do
  adb wait-for-device
  adb logcat -v time Saathi:V SaathiLLM:V SaathiLog:V SaathiDump:V AndroidRuntime:E DEBUG:F '*:S' >> "logs/logcat-$(date +%Y%m%d).txt"
  sleep 3
done
