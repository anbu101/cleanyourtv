#!/usr/bin/env bash
# Push a theme to the OnePlus Y1S Pro and restart Home.
# Usage: examples/oneplus-y1s-pro/theme.sh themes/aurora
#        examples/oneplus-y1s-pro/theme.sh --reset
# Run from the repo root.
set -euo pipefail
TV=192.168.1.6:5555
PKG=com.gathin.tvhome
DIR=/sdcard/Android/data/$PKG/files

ADB="adb -s $TV"

# Save and restore accessibility services across the force-stop.
A11Y=$($ADB shell settings get secure enabled_accessibility_services 2>/dev/null || true)

restart() {
    # Flush screen time before force-stop (no screenOff otherwise)
    $ADB shell am broadcast -a com.gathin.tvhome.FLUSH_SCREEN_TIME >/dev/null 2>&1 || true
    $ADB shell am force-stop "$PKG"
    sleep 1
    case "$A11Y" in *$PKG*)
        $ADB shell settings put secure enabled_accessibility_services "\"$A11Y\""
        $ADB shell settings put secure accessibility_enabled 1
        ;;
    esac
    $ADB shell am start -n "$PKG/.MainActivity" >/dev/null
}

if [ "${1:-}" = "--reset" ]; then
    $ADB shell "rm -f $DIR/config.json $DIR/wallpaper.jpg"
    restart
    echo "Theme reset to built-in config.xml."
    exit 0
fi

THEME="${1:?usage: theme.sh <theme folder> | --reset}"
[ -f "$THEME/config.json" ] || { echo "no config.json in $THEME" >&2; exit 1; }

$ADB shell "mkdir -p $DIR"
$ADB push "$THEME/config.json" "$DIR/config.json"
if [ -f "$THEME/wallpaper.jpg" ]; then
    $ADB push "$THEME/wallpaper.jpg" "$DIR/wallpaper.jpg"
else
    $ADB shell "rm -f $DIR/wallpaper.jpg"
fi

restart
echo "Theme $(basename "$THEME") applied. Undo with: $0 --reset"
