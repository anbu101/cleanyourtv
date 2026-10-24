#!/usr/bin/env bash
# Rebuild and reinstall the launcher on the OnePlus Y1S Pro (192.168.1.6).
# Run from the repo root: examples/oneplus-y1s-pro/build-and-install.sh
# Prerequisites: Java 17, Android SDK with build-tools;34 and platforms;android-34.
set -euo pipefail
cd "$(dirname "$0")/../.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
TV="${TV_HOST:-192.168.1.6}"

[ -f launcher/local.properties ] || echo "sdk.dir=$ANDROID_HOME" > launcher/local.properties

cd launcher
./gradlew assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
echo "Built: $APK"

# Flush screen time before the install kills the process (no screenOff otherwise)
adb -s "$TV:5555" shell am broadcast -a com.gathin.tvhome.FLUSH_SCREEN_TIME >/dev/null 2>&1 || true

adb -s "$TV:5555" push "$APK" /data/local/tmp/tvhome.apk
adb -s "$TV:5555" shell pm install -r /data/local/tmp/tvhome.apk
adb -s "$TV:5555" shell rm /data/local/tmp/tvhome.apk

# Compile to native code for faster startup on the MediaTek CPU
adb -s "$TV:5555" shell cmd package compile -m speed -f com.gathin.tvhome

# Re-assert Home preference (reinstall clears it on some firmware)
adb -s "$TV:5555" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

echo "Done. Press Home on the remote."
