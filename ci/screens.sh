#!/usr/bin/env bash
# Знімки екрана нового дизайну на емуляторі (світла і темна тема).
set -x
OUT=ci-out
mkdir -p "$OUT"
PKG=ua.calmalarm.app
APK=app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
uidump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
tap() { local xy; xy=$(uidump | python3 ci/find.py "$1" "$2"); [ -n "$xy" ] && adb shell input tap $xy; }
swipe_up() { adb shell input swipe 540 1700 540 700 500; sleep 2; }

adb wait-for-device
adb shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
adb shell cmd alarm set-timezone Europe/Kiev || true
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell wm dismiss-keyguard
adb install -r -g "$APK" || exit 1
adb shell dumpsys deviceidle whitelist +$PKG || true
adb shell cmd appops set $PKG USE_FULL_SCREEN_INTENT allow || true
adb shell am start -n $PKG/.MainActivity
sleep 4
adb shell am force-stop $PKG
cat /tmp/region.json
python3 - <<'PY' > /tmp/p.xml
import json, html
region = json.load(open("/tmp/region.json"))
alarms = [
    {"id": 1, "h": 7, "m": 0, "days": [1, 2, 3, 4, 5], "on": True, "tone": 0, "red": True, "yellow": True, "wait": 120, "snooze": 5},
    {"id": 2, "h": 9, "m": 30, "days": [6, 7], "on": True, "tone": 1, "red": True, "yellow": False, "wait": 90, "snooze": 10},
    {"id": 3, "h": 6, "m": 15, "days": [], "on": False, "tone": 3, "red": True, "yellow": True, "wait": 60, "snooze": 5},
]
s = lambda n, v: f'    <string name="{n}">{html.escape(v)}</string>'
print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>")
print(s("alarms", json.dumps(alarms, ensure_ascii=False)))
print(s("region", json.dumps(region, ensure_ascii=False)))
print('    <int name="next_id" value="10" />\n    <boolean name="asked_notif" value="true" />\n    <boolean name="asked_notif_2" value="true" />\n    <boolean name="demo_threats" value="true" />\n</map>')
PY
adb push /tmp/p.xml /data/local/tmp/p.xml
adb shell "cat /data/local/tmp/p.xml | run-as $PKG sh -c 'cat > shared_prefs/calmalarm.xml'"

for mode in light dark; do
  if [ "$mode" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.MainActivity
  sleep 8
  shot "${mode}_1_alarms"
  tap text "Ймовірність"
  sleep 35
  shot "${mode}_2_chance"
  swipe_up
  shot "${mode}_3_map"
  swipe_up
  shot "${mode}_4_reasons"
  tap text "Регіон"
  sleep 4
  shot "${mode}_5_region"
  tap text "Будильник"
  sleep 2
  if [ "$mode" = light ]; then
    tap content-desc "Додати будильник"
    sleep 3
    shot "${mode}_6_editor"
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
done
uidump > "$OUT/last_ui.xml"
