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
    $ADB shell "rm -f $DIR/config.json $DIR/wallpaper.jpg && rm -rf $DIR/wallpapers"
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
# Push wallpapers to per-theme folder for rotation support
TNAME=$(basename "$THEME")
if [ -d "$THEME/wallpapers" ]; then
    $ADB shell "mkdir -p $DIR/theme-wallpapers/$TNAME"
    $ADB push "$THEME/wallpapers/." "$DIR/theme-wallpapers/$TNAME/"
    # Copy the first image as wallpaper.jpg so Home shows one immediately
    if [ ! -f "$THEME/wallpaper.jpg" ]; then
        first=$(find "$THEME/wallpapers" -maxdepth 1 -type f \( -name '*.jpg' -o -name '*.png' -o -name '*.webp' \) | sort | head -1)
        [ -n "${first:-}" ] && $ADB push "$first" "$DIR/wallpaper.jpg"
    fi
elif [ -f "$THEME/wallpaper.jpg" ]; then
    $ADB shell "mkdir -p $DIR/theme-wallpapers/$TNAME"
    $ADB push "$THEME/wallpaper.jpg" "$DIR/theme-wallpapers/$TNAME/wallpaper.jpg"
fi

restart
echo "Theme $(basename "$THEME") applied. Undo with: $0 --reset"
