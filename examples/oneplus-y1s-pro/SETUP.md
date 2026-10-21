# OnePlus Y1S Pro — Full Setup Guide

**Model:** OnePlus Y1S Pro (C00)
**OS:** Android TV 11 / SDK 30
**Firmware:** RTM8.240401.135
**SoC:** MediaTek MT9652 (ARM Cortex-A55, 1.3 GHz)
**TV IP:** 192.168.1.6 (static or reserve in router DHCP)

This guide takes a stock OnePlus Y1S Pro from factory state to a fully
debloated, ad-free TV with a clean launcher and a phone remote that replaces
the physical remote entirely. Everything is reversible — nothing writes to
`/system`.

---

## What gets set up

| Step | What | Result |
|---|---|---|
| 1 | Enable ADB | Remote access over Wi-Fi |
| 2 | Debloat | ~30 OnePlus/Google bloat apps disabled |
| 3 | Launcher | CleanYourTV replaces the OnePlus home screen |
| 4 | SmartTube | Ad-free YouTube client for Android TV |
| 5 | Phone remote | iPhone/Android PWA remote on port 8765 |
| 6 | One-time remote setup | Accessibility service for navigation |

---

## Step 1 — Enable ADB over Wi-Fi

On the TV remote:

1. **Settings → Device Preferences → About → Build** — click 7 times to unlock Developer Options
2. **Settings → Device Preferences → Developer Options → USB Debugging** → ON
3. **Settings → Device Preferences → Developer Options → Network Debugging** → ON
4. Note the TV IP from **Settings → Network → Wi-Fi → (connected network)**

On your Mac/Linux:

```bash
adb connect 192.168.1.6:5555
adb devices   # must show "device", not "unauthorized"
```

If it shows "unauthorized", accept the prompt on the TV screen.

---

## Step 2 — Debloat

The package list is at `examples/oneplus-y1s-pro/debloat.txt`. Every package is
**disabled** (not uninstalled) — `adb shell pm enable <pkg>` restores any of them.

```bash
# From repo root
TV_HOST=192.168.1.6 tools/disable.sh examples/oneplus-y1s-pro/debloat.txt
```

To undo everything:

```bash
TV_HOST=192.168.1.6 tools/restore.sh examples/oneplus-y1s-pro/debloat.txt
```

**Packages kept on purpose:** `com.heytap.tv.livetv`, `com.heytap.tvapiservice`,
`com.heytap.rom.service`, `com.heytap.tv.support`, `com.oneplus.tv.remote`,
`com.oneplus.tv.screensaver` (used for clock screensaver), Play Store, Play
Services, Spotify, all `com.mediatek.*`.

**Packages uninstalled for user 0** (not re-installable without Play Store):
`com.heytap.mcs`, `com.google.android.youtube.tvmusic`, `com.heytap.tv.btspeaker`.

---

## Step 3 — Build and install the launcher

```bash
# From repo root
examples/oneplus-y1s-pro/build-and-install.sh
```

This builds a release APK, installs it, compiles it to native code (`cmd package
compile -m speed`) for fast startup on the MediaTek CPU, and asserts it as the
home activity.

After install, press **Home** on the physical remote to confirm the CleanYourTV
launcher appears.

**Prerequisites:** Java 17, Android SDK with `build-tools;34` and
`platforms;android-34`. The script auto-detects Homebrew paths on macOS.

---

## Step 4 — Install SmartTube (ad-free YouTube)

SmartTube is an open-source Android TV YouTube client with zero ad code. It
cannot show ads because it has no ad-serving code at all.

```bash
curl -fsSL -o /tmp/smarttube.apk \
  "https://github.com/yuliskov/SmartTube/releases/download/32.56s/SmartTube_stable_32.56_universal.apk"

# Verify checksum
echo "4a35b90940612e048e4e9cb9e10379f7db1e84f30375994129f10a571ee04ce5  /tmp/smarttube.apk" | shasum -a 256 -c

adb -s 192.168.1.6:5555 install -r /tmp/smarttube.apk
```

**First-time settings on the TV (one-time, with physical remote):**
- Left drawer (press LEFT) → Settings → General → Hide content → **Hide Shorts everywhere** ON
- Settings → Player → Video presets → **1080p 60fps vp9** (not av01 — no hardware AV1 decoder on MT9652)
- Settings → General → **SponsorBlock** → ON (skips in-video sponsor segments)

SmartTube appears in the launcher app list as **SmartTube**. The stock YouTube
app (`com.google.android.youtube.tv`) can be left installed — it is already
disabled by the debloat step.

---

## Step 5 — Phone remote (HTTP server on port 8765)

The launcher includes a foreground service (`RemoteService`) that runs a tiny
HTTP server on port 8765. Connecting to `http://192.168.1.6:8765` from any
phone browser gives a full remote-control PWA.

**The remote handles:**
- D-pad navigation (Up/Down/Left/Right/OK)
- Back, Home, Settings
- Power on (wake from standby) and power off (lock screen)
- Volume slider (live sync with TV volume)
- Media controls (Play/Pause, Prev/Next, Rewind/FF)
- App launch buttons (YouTube/SmartTube, Netflix, Prime, Hotstar, Live TV, Spotify)
- Text input (sends text to focused field on TV)

**Add to iPhone home screen:**
1. Open Safari → `http://192.168.1.6:8765`
2. Share → **Add to Home Screen**
3. The PWA opens in portrait-locked standalone mode with no browser chrome

The remote works on Android phones too; haptic feedback (vibration) works on
Android Chrome but not iOS Safari.

---

## Step 6 — One-time accessibility service setup (phone remote navigation)

The phone remote uses two mechanisms for key injection:

1. **tvapisvc binder (primary):** Calls `ModuleAppSwitch.disPatchCommand(cmd=0)`
   which runs `Instrumentation.sendKeyDownUpSync()` in the tvapisvc system
   process. Works in any foreground app including Netflix, Hotstar, etc.

2. **AccessibilityService (fallback):** Used for Power Off (`GLOBAL_ACTION_LOCK_SCREEN`)
   and as a fallback for navigation if tvapisvc is unavailable.

**Enable the accessibility service once on the TV:**

```
TV Settings → Device Preferences → Accessibility → TV Remote Navigation → ON
```

Or via ADB:

```bash
adb -s 192.168.1.6:5555 shell settings put secure enabled_accessibility_services \
  com.gathin.tvhome/.RemoteAccessibilityService
adb -s 192.168.1.6:5555 shell settings put secure accessibility_enabled 1
```

> **Important:** Do NOT use `adb shell am force-stop com.gathin.tvhome` — this
> marks the accessibility service as "Crashed" and requires a TV reboot to fix.
> Instead, install the APK and let the system restart the process naturally.

---

## Key injection — technical notes for future Claude sessions

This is the most non-obvious part of the setup. The OnePlus Y1S Pro runs Android
TV 11 (SDK 30), which means:

- `performGlobalAction(GLOBAL_ACTION_DPAD_*)` (API 33+) returns false — not supported
- `Runtime.exec("input keyevent X")` does NOT reach WindowManager from app process
- Standard accessibility focus navigation (`navigateFocus`) fails in Netflix/Hotstar
  (Gibbon custom renderer — no standard View accessibility tree)

**Solution: tvapisvc binder (OnePlus proprietary)**

The system service `tvapisvc` (descriptor: `com.oneplus.tvapi.ITvApi`, transaction
code 4 = `disPatchCommand(String module, int cmd, String[] params, boolean async)`)
hosts multiple modules. `ModuleAppSwitch` command 0 calls
`Instrumentation.sendKeyDownUpSync(keyCode)` in the tvapisvc system process,
which has unrestricted input injection privileges.

```java
// In RemoteService.java — injectKeyViaTvApiService(int keyCode)
Parcel data = Parcel.obtain();
data.writeInterfaceToken("com.oneplus.tvapi.ITvApi");
data.writeString("ModuleAppSwitch");
data.writeInt(0);                          // command 0 = sendKeyDownUpSync
data.writeInt(1);                          // String[] length = 1
data.writeString(String.valueOf(keyCode)); // strArr[0] = keyCode as string
data.writeInt(0);                          // boolean false (use ThreadPool)
binder.transact(4, data, reply, 0);
```

**Power ON (wake from standby):** `ModulePower` command 6 = `setScreenOn()`

```java
data.writeString("ModulePower");
data.writeInt(6);   // setScreenOn
data.writeInt(0);   // empty String[] — MUST be 0, not -1: server does strArr.length unconditionally
data.writeInt(0);   // boolean false
```

**tvapisvc modules discovered:** ModuleAppSwitch, ModuleAudio, ModuleDevice,
ModuleDiagnoses, ModuleFake, ModulePackageManager, ModulePicture, ModulePower,
ModuleScreenCapRecord. No ModuleInput exists.

---

## PWA layout — iOS-specific notes

The phone remote is served as a PWA with these iOS quirks handled:

- **Dynamic Island (iPhone 14 Pro+):** Use `max(calc(env(safe-area-inset-top)+10px), 72px)`
  for body padding-top. With `apple-mobile-web-app-status-bar-style: black` and
  `viewport-fit=cover`, `env(safe-area-inset-top)` returns 0, so the `max()` fallback
  of 72px guarantees content starts below the Dynamic Island (~59px tall).
- **Side padding:** `max(calc(env(safe-area-inset-right)+20px), 20px)` — 20px minimum
  clears iPhone rounded screen corners.
- **Portrait lock:** `screen.orientation.lock('portrait')` not supported on iOS Safari.
  Use CSS `@media (orientation: landscape)` overlay instead.
- **Haptic feedback:** `navigator.vibrate()` works on Android Chrome only. iOS Safari
  does not support the Web Vibration API — no fix available.
- **Status bar style:** `black` (not `black-translucent`) so the status bar takes its
  own space and the header row is not hidden behind it.
- **Re-add after every APK update:** iOS caches the PWA manifest at install time.
  After updating the launcher APK, delete and re-add the home screen icon.

---

## File map

```
launcher/
  app/src/main/java/com/gathin/tvhome/
    RemoteService.java              # HTTP server + key injection logic
    RemoteAccessibilityService.java # Accessibility service (fallback nav + power off)
    WakeActivity.java               # Zero-UI activity to turn screen on
    BootReceiver.java               # Restarts RemoteService after TV boot
  app/src/main/res/xml/
    remote_accessibility_service.xml  # Service config (canRetrieveWindowContent=true)
  app/src/main/assets/remote/
    index.html                      # Phone remote PWA (single file, no build step)
    manifest.json                   # PWA manifest

examples/oneplus-y1s-pro/
  SETUP.md                          # This file
  debloat.txt                       # Package disable list
  build-and-install.sh              # Build + install script
  restore.sh                        # Re-enable all disabled packages
```

---

## Quick reference — common ADB commands

```bash
# Connect
adb connect 192.168.1.6:5555

# Install / update launcher
examples/oneplus-y1s-pro/build-and-install.sh

# Watch remote server logs
adb -s 192.168.1.6:5555 logcat -s TvHome.Remote:I TvHome.RemoteA11y:I

# Check accessibility service state
adb -s 192.168.1.6:5555 shell settings get secure enabled_accessibility_services

# Enable accessibility service via ADB
adb -s 192.168.1.6:5555 shell settings put secure enabled_accessibility_services \
  com.gathin.tvhome/.RemoteAccessibilityService
adb -s 192.168.1.6:5555 shell settings put secure accessibility_enabled 1

# Check SmartTube version
adb -s 192.168.1.6:5555 shell dumpsys package org.smarttube.stable | grep versionName

# Debloat / restore
TV_HOST=192.168.1.6 tools/disable.sh examples/oneplus-y1s-pro/debloat.txt
TV_HOST=192.168.1.6 tools/restore.sh examples/oneplus-y1s-pro/debloat.txt
```
