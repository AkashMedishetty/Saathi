#!/usr/bin/env bash
# Demo phone only: lets Saathi put back its OWN accessibility entry if the system drops it (see A11yGuard).
cd "$(dirname "$0")/.." && source scripts/env.sh
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS && echo granted
