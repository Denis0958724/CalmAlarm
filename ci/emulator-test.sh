#!/usr/bin/env bash
# End-to-end check of CalmAlarm on an Android emulator.
# Never stops early: collects every screenshot and log it can into ci-out/.
set -x
OUT=ci-out
mkdir -p "$OUT"
PKG=ua.calmalarm.app
APK=app/build/outputs/apk/debug/app-debug.apk
IFS='|' read -r CALM_ID CALM_NAME < /tmp/calm.txt

log() { echo "[$(date +%T)] $*" | tee -a "$OUT/steps.txt"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; log "screenshot $1"; }
uidump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
tap() { # tap <text|content-desc> <value>
  local xy
  xy=$(uidump | python3 ci/find.py "$1" "$2")
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    log "tap $1='$2' at $xy"
    return 0
  fi
  log "NOT FOUND $1='$2'"
  return 1
}
swipe_up() { adb shell input swipe 540 1900 540 600 400; sleep 1; }
emu_now() { adb shell date +"%Y-%m-%d\ %H:%M:%S" | tr -d '\r'; }

adb wait-for-device
adb shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
adb shell cmd alarm set-timezone Europe/Kiev || adb shell setprop persist.sys.timezone Europe/Kiev
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell wm dismiss-keyguard
adb shell input keyevent KEYCODE_WAKEUP
sleep 2

adb install -r -g "$APK" || { log "INSTALL FAILED"; exit 1; }
adb logcat -c
emu_now > "$OUT/emulator_time.txt"
adb shell appops get $PKG USE_FULL_SCREEN_INTENT > "$OUT/fsi_appop.txt" 2>&1

# ---------- 1. First launch: region picker ----------
adb shell am start -n $PKG/.MainActivity
sleep 6
shot 01_first_launch_region
tap text "Київська область"
sleep 10
shot 02_districts
tap text "Броварський район"
sleep 8
shot 03_alarm_tab_with_status
uidump > "$OUT/03_alarm_tab.xml"
tap content-desc "Підтримати автора"
sleep 3
shot 03b_support_sheet
adb shell input keyevent KEYCODE_BACK
sleep 2

# ---------- 2. Chance tab ----------
tap text "Ймовірність"
sleep 25
shot 04_chance
uidump > "$OUT/04_chance.xml"
swipe_up
if tap text "Що пишуть монітори"; then sleep 2; swipe_up; fi
shot 05_chance_details

# ---------- 3. Region tab ----------
tap text "Регіон"
sleep 2
shot 06_region_summary
tap text "Змінити регіон"
sleep 2
tap text "Харківська область"
sleep 8
shot 06b_kharkiv_districts
tap content-desc "Назад"
sleep 1
tap content-desc "Назад"
sleep 1

# ---------- 4. Alarm editor ----------
tap text "Будильник"
sleep 2
tap content-desc "Додати будильник"
sleep 3
shot 07_editor_top
swipe_up
shot 08_editor_middle
swipe_up
shot 09_editor_bottom
tap text "Зберегти"
sleep 1
shot 10_alarm_saved
sleep 3
shot 10b_alarm_list

# ---------- 5. Alarm logic with seeded data ----------
adb shell am force-stop $PKG
NOW=$(emu_now)
python3 ci/prefs.py seed "$NOW" /tmp/times.txt > "$OUT/seed_prefs.xml"
read -r WAIT_A WAIT_B TIME_A TIME_B < /tmp/times.txt
log "now=$NOW alarm A=$TIME_A (in ${WAIT_A}s, waits for alert, region Luhansk) alarm B=$TIME_B (in ${WAIT_B}s, always rings)"
adb push "$OUT/seed_prefs.xml" /data/local/tmp/p.xml
adb shell "cat /data/local/tmp/p.xml | run-as $PKG sh -c 'cat > shared_prefs/calmalarm.xml'"
adb shell am start -n $PKG/.MainActivity
sleep 6
shot 11_seeded_alarms
adb shell dumpsys alarm | grep -B2 -A6 "$PKG" > "$OUT/11_alarms_scheduled.txt"
adb shell input keyevent KEYCODE_HOME
START=$(date +%s)

# Test A: Luhansk oblast has a permanent air alert -> alarm A must NOT ring but wait.
sleep $((WAIT_A + 25))
adb shell cmd statusbar expand-notifications
sleep 2
shot 12_waiting_notification
adb shell cmd statusbar collapse
adb shell dumpsys activity services $PKG > "$OUT/12_services.txt"
adb shell am start -n $PKG/.MainActivity
sleep 4
shot 13_waiting_card
adb shell run-as $PKG cat shared_prefs/calmalarm.xml > "$OUT/13_prefs_while_waiting.xml"

# Test A2: "alert ends" -> switch region to a calm oblast; next check must ring with the all-clear note.
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am kill $PKG
adb shell run-as $PKG cat shared_prefs/calmalarm.xml > /tmp/cur.xml
python3 ci/prefs.py region /tmp/cur.xml "$CALM_ID" "$CALM_NAME" > "$OUT/14_prefs_calm.xml"
adb push "$OUT/14_prefs_calm.xml" /data/local/tmp/p2.xml
adb shell "cat /data/local/tmp/p2.xml | run-as $PKG sh -c 'cat > shared_prefs/calmalarm.xml'"
log "region switched to $CALM_ID $CALM_NAME; waiting for next check"
sleep 80
shot 14_ring_after_alert_end
uidump > "$OUT/14_ring.xml"
adb shell dumpsys activity services $PKG > "$OUT/14_services.txt"
tap text "Вимкнути"
sleep 3
shot 15_after_stop
adb shell dumpsys activity services $PKG > "$OUT/15_services_after_stop.txt"

# Test B: locked screen, alarm that ignores alerts must ring and show full screen.
adb shell input keyevent KEYCODE_HOME
adb shell svc power stayon false
adb shell input keyevent KEYCODE_SLEEP
ELAPSED=$(( $(date +%s) - START ))
REMAIN=$(( WAIT_B - ELAPSED + 20 ))
[ $REMAIN -lt 5 ] && REMAIN=5
log "sleeping $REMAIN s until alarm B"
sleep $REMAIN
shot 16_ring_locked_screen
uidump > "$OUT/16_ring.xml"
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" > "$OUT/16_focus.txt"
adb shell dumpsys activity services $PKG > "$OUT/16_services.txt"
tap text "Вимкнути"
sleep 2
shot 17_after_stop_b

# ---------- 6. Dark theme ----------
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell cmd uimode night yes
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity
sleep 6
shot 18_dark_alarm_tab
tap text "Ймовірність"
sleep 20
shot 19_dark_chance

# ---------- Logs ----------
adb logcat -d > /tmp/logcat.txt
grep -E "CalmAlarm|AndroidRuntime|FATAL|calmalarm" /tmp/logcat.txt > "$OUT/logcat_app.txt"
tail -n 4000 /tmp/logcat.txt > "$OUT/logcat_tail.txt"
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" > "$OUT/notifications.txt"
log "done"
exit 0
