#!/usr/bin/env bash
source "$(dirname "$0")/env.sh"
adb devices -l
for p in ro.product.model ro.build.version.release ro.soc.model ro.board.platform; do
  echo "$p = $(adb shell getprop $p | tr -d '\r')"
done
echo "consumerir = $(adb shell pm has-feature android.hardware.consumerir | tr -d '\r')"
