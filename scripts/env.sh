# Sourced by the other scripts.
[ -f "$HOME/dev/android-env.sh" ] && source "$HOME/dev/android-env.sh"
PKG=com.saathi.app
SVC="$PKG/$PKG.service.SaathiService"
APK=app/build/outputs/apk/debug/app-debug.apk
export ANDROID_SERIAL="${ANDROID_SERIAL:-10BFAU1534000XR}"  # the loaner iQOO 15
