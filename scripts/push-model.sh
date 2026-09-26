#!/usr/bin/env bash
# Push on-device models into the app's external files dir. MODEL=<file> pushes just one.
set -euo pipefail
source "$(dirname "$0")/env.sh"
DEST=/sdcard/Android/data/$PKG/files/models
SRC="${MODEL_DIR:-$HOME/dev/dl/models}"
adb shell mkdir -p "$DEST" /data/local/tmp/llm
push() { echo "-> $(basename "$1") ($(du -h "$1" | cut -f1))"; adb push "$1" "$DEST/" || adb push "$1" /data/local/tmp/llm/; }
if [ -n "${MODEL:-}" ]; then push "$MODEL"; exit 0; fi
# Text brain (GPU), vision (NPU), CPU safety net.
for f in "$SRC"/gemma-4-E2B*.litertlm "$SRC"/FastVLM*sm8850*.litertlm "$SRC"/qwen2.5-0.5b*.task; do
  [ -f "$f" ] && push "$f"
done
adb shell ls -la "$DEST"
