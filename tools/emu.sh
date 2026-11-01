#!/usr/bin/env bash
# Drives the headless test emulator. Usage:
#   tools/emu.sh install [debug|release]   install the APK, clear data, launch, wait for the reader
#   tools/emu.sh shot NAME                 save a screenshot to out/shots/NAME.png
#   tools/emu.sh tap X Y                   tap at screen pixels (1080x2400)
#   tools/emu.sh swipe X1 Y1 X2 Y2         swipe
#   tools/emu.sh text "words"              type text (spaces allowed)
#   tools/emu.sh key CODE                  send a key event (4 = Back, 66 = Enter)
#   tools/emu.sh crash                     print any crash in the log
set -u
ADB=/d/android/sdk/platform-tools/adb.exe
PKG=io.github.ljr1981.sbphone
ROOT=/d/prod/sb_phone
case "$1" in
  install)
    kind=${2:-debug}
    $ADB uninstall $PKG >/dev/null 2>&1
    $ADB install -r "$ROOT/app/build/outputs/apk/$kind/app-$kind.apk" | tail -1
    $ADB logcat -c
    $ADB shell am start -n $PKG/sbphone.app.MainActivity >/dev/null
    for i in $(seq 1 90); do
      sleep 1
      if $ADB shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" 2>/dev/null | grep -q 'text="Read"'; then echo "reader up after ${i}s"; exit 0; fi
    done
    echo "reader did not appear"; $ADB logcat -d -s AndroidRuntime:E | tail -30 ;;
  shot) mkdir -p "$ROOT/out/shots"; sleep 1; $ADB exec-out screencap -p > "$ROOT/out/shots/$2.png"; echo "out/shots/$2.png" ;;
  tap) $ADB shell input tap "$2" "$3" ;;
  swipe) $ADB shell input swipe "$2" "$3" "$4" "$5" 300 ;;
  text) $ADB shell input text "$(echo "$2" | sed 's/ /%s/g')" ;;
  key) $ADB shell input keyevent "$2" ;;
  crash) $ADB logcat -d -s AndroidRuntime:E | tail -40 ;;
esac
