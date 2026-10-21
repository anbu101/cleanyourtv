#!/usr/bin/env bash
# Restore: re-enables everything in debloat.txt and puts Projectivy back as Home.
# Run from the repo root: examples/oneplus-y1s-pro/restore.sh
set -euo pipefail
TV_HOST="${TV_HOST:-192.168.1.6}"

adb -s "$TV_HOST:5555" shell pm enable --user 0 net.oneplus.weather
adb -s "$TV_HOST:5555" shell pm enable --user 0 android.autoinstalls.config.OnePlus
adb -s "$TV_HOST:5555" shell pm enable --user 0 oneplus.smarthome
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.heytap.tv.camera
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.heytap.tv.remotediagnosing
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.heytap.tv.assistant.oneplus
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.heytap.olink
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.heytap.optvfactory
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.marvin.talkback
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.android.printspooler
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.feedback
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.syncadapters.calendar
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.play.games
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.coloros.relax.tv
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.videos
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.katniss
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.tv.assistant
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.tts
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.tvlauncher
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.google.android.tvrecommendations
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.oneplus.tv.launcher
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.oneplus.tv.userguide
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.oneplus.tv.android.account
# Restore Projectivy as Home (will show chooser; pick it on the TV)
adb -s "$TV_HOST:5555" shell pm enable --user 0 com.spocky.projengmenu
echo "Done. Open Home on the TV and select Projectivy from the chooser."
