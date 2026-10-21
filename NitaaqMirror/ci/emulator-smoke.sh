#!/usr/bin/env bash
# Emulator test for NITAAQ Mirror (CI). Emulator results are NOT proof of behaviour on real phones,
# and emulator images have no Play Protect: this checks installation, launch, crashes and the main flow.
#
#   ci/emulator-smoke.sh <dir-with-controller.apk-and-full.apk> <output-dir>
#
# Writes <output-dir>/report.txt (PASS/FAIL/INFO lines), screenshots and logcat.
set -u
APKS="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=sa.nitaaq.mirror
REPORT="$OUT/report.txt"
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device   # google_apis images: lets the test open app screens directly
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
REL=$(adb shell getprop ro.build.version.release | tr -d '\r')
echo "Emulator: Android $REL (API $API), $(adb shell getprop ro.product.cpu.abi | tr -d '\r')" > "$REPORT"
CRIT=0

pass() { echo "PASS  $*" | tee -a "$REPORT"; }
fail() { echo "FAIL  $*" | tee -a "$REPORT"; }
info() { echo "INFO  $*" | tee -a "$REPORT"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }

ui_dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }

# Tap the first node whose text or content-desc contains any of the given strings. Returns 1 if not found.
tap_text() {
  ui_dump
  local xy
  xy=$(python3 - "$OUT/ui.xml" "$@" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
wanted = sys.argv[2:]
hits = [n for n in root.iter('node')
        if any(w in ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')) for w in wanted)]
hits.sort(key=lambda n: n.get('clickable') != 'true')  # prefer buttons over labels with the same text
if not hits: sys.exit(1)
x1, y1, x2, y2 = map(int, re.findall(r'\d+', hits[0].get('bounds')))
print((x1 + x2) // 2, (y1 + y2) // 2)
EOF
  ) || return 1
  adb shell input tap $xy
}

scroll_down() { adb shell input swipe 160 520 160 180 300; sleep 1; }

# Like tap_text, but scrolls down (up to 4 times) when the target is below the fold.
find_tap() {
  tap_text "$@" && return 0
  for _ in 1 2 3 4; do scroll_down; tap_text "$@" && return 0; done
  return 1
}

wait_tap() { # wait_tap <seconds> <text...>
  local t=$1; shift
  for _ in $(seq 1 "$t"); do tap_text "$@" && return 0; sleep 1; done
  return 1
}

crashed() { adb logcat -d -b crash 2>/dev/null | grep -q "$PKG"; }

for ED in controller full; do
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
  if adb install "$APKS/$ED.apk" > "$OUT/install-$ED.txt" 2>&1 && grep -q Success "$OUT/install-$ED.txt"; then
    pass "[$ED] install"
  else
    fail "[$ED] install: $(tail -1 "$OUT/install-$ED.txt")"; CRIT=1; continue
  fi
  adb shell dumpsys package $PKG | grep -E "versionCode|versionName|permission\." | sed 's/^ *//' > "$OUT/package-$ED.txt"
  adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1
  sleep 4
  if adb shell pidof $PKG > /dev/null && ! crashed; then pass "[$ED] launch (no crash)"; else fail "[$ED] launch"; CRIT=1; fi
  shot "$ED-main"

  # Open each screen once and look for crashes.
  tap_text "التحكم بجهاز آخر" "Control another device" && sleep 3 && shot "$ED-controller"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  tap_text "مشاركة هذا الجهاز" "Share this device" && sleep 3 && shot "$ED-host"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  tap_text "الإعدادات" "Settings" && sleep 2 && shot "$ED-settings"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  if crashed; then fail "[$ED] screens"; CRIT=1; adb logcat -d -b crash > "$OUT/crash-$ED.txt"; else pass "[$ED] open all main screens"; fi

  if [ "$ED" = controller ]; then
    if adb shell dumpsys package $PKG | grep -q BIND_ACCESSIBILITY_SERVICE; then fail "[controller] declares an accessibility service"; CRIT=1
    else pass "[controller] no accessibility service declared"; fi
  fi
done

# ---------------------------------------------------------------- full edition: end-to-end loopback
# Host and controller roles run in the same app on one emulator, connected through 127.0.0.1.
info "end-to-end loopback test (full edition)"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.host.ControlService
adb shell settings put secure accessibility_enabled 1
sleep 3
if adb shell dumpsys accessibility | grep -q "$PKG/.host.ControlService\|$PKG/$PKG.host.ControlService"; then pass "accessibility service binds"; else info "accessibility service not reported bound"; fi

adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 3
# Force-stop unbinds accessibility services; toggle the setting so the system binds it again.
adb shell settings put secure enabled_accessibility_services '""' ; sleep 1
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.host.ControlService; sleep 3
find_tap "مشاركة هذا الجهاز" "Share this device"; sleep 3
find_tap "بدء مشاركة الشاشة" "Start screen sharing"; sleep 3
shot e2e-1-consent
# System consent dialog (wording differs by version).
wait_tap 8 "Start now" "Start" "START NOW" "ابدأ" || info "consent button not found"
sleep 5
shot e2e-2-sharing
if adb shell dumpsys activity services $PKG | grep -q "HostService"; then pass "screen-capture foreground service running"; else fail "screen-capture service not running"; fi

CODE=""
for _ in 1 2 3 4; do
ui_dump
CODE=$(python3 - "$OUT/ui.xml" <<'EOF'
import sys, re
t = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'(\d{4}) (\d{4})', t)
print(m.group(1) + m.group(2) if m else '')
EOF
)
[ -n "$CODE" ] && break
scroll_down
done
shot e2e-2b-code
PORT=$(grep -o ' : [0-9]\{4,5\}' "$OUT/ui.xml" | head -1 | tr -dc 0-9)
[ -z "$PORT" ] && PORT=47820
if [ -n "$CODE" ]; then pass "pairing code shown on host"; else fail "pairing code not found on host screen"; fi

# Controller role: manual pairing to ourselves.
adb shell input keyevent KEYCODE_BACK; sleep 1
find_tap "التحكم بجهاز آخر" "Control another device"; sleep 2
find_tap "إدخال يدوي" "Enter manually"; sleep 2
shot e2e-2c-manual
ui_dump
python3 - "$OUT/ui.xml" > "$OUT/fields.txt" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    if n.get('class') == 'android.widget.EditText':
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
EOF
mapfile -t F < "$OUT/fields.txt"
if [ ${#F[@]} -ge 3 ]; then
  # Focus the first field once, then move with Tab: the keyboard shifts the dialog, so stale coordinates miss.
  adb shell input tap ${F[0]}; sleep 1; adb shell input text 127.0.0.1
  adb shell input keyevent KEYCODE_TAB; adb shell input keyevent KEYCODE_MOVE_END
  for _ in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DEL; done; adb shell input text "$PORT"
  adb shell input keyevent KEYCODE_TAB; adb shell input text "$CODE"
  sleep 1; shot e2e-2d-filled
  tap_text "اتصال" "Connect" || info "connect button not found"; sleep 4
  shot e2e-3-verify
  # Approve on the "host" side through the notification (Allow).
  adb shell cmd statusbar expand-notifications; sleep 2
  shot e2e-4-approval
  if wait_tap 6 "سماح" "Allow"; then pass "approval request accepted (notification)"
  else
    # Grouped/collapsed notification: answer in the app's own approval dialog instead.
    adb shell cmd statusbar collapse; sleep 1
    adb shell am start -n $PKG/.host.HostActivity > /dev/null 2>&1; sleep 2
    if wait_tap 6 "سماح" "Allow"; then pass "approval request accepted (in-app dialog)"; else fail "approval request not found"; fi
    sleep 2; adb shell am start -n $PKG/.ctrl.RemoteActivity > /dev/null 2>&1
  fi
  adb shell cmd statusbar collapse; sleep 6
  shot e2e-5-remote
  ui_dump
  if adb logcat -d | grep -q "nm-decoder-out\|MediaCodec" ; then info "decoder activity in logcat"; fi
  if adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q RemoteActivity; then pass "remote screen open"; else info "remote screen not on top"; fi
  # Execute a navigation action on the host through the controller toolbar: Home.
  # The live video never lets uiautomator see an idle screen, so tap the toolbar's Home button by geometry:
  # 9 buttons of 40dp (+2dp gaps, 8dp side padding) centred at the bottom, 10dp above the navigation bar.
  read W H <<<"$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' ')"
  D=$(adb shell wm density | tail -1 | grep -o '[0-9]*$'); DP=$(python3 -c "print($D/160)")
  NAV=$(python3 -c "print(int(48*$DP))")
  SW=$(python3 -c "print(int(min($W,$H)/$DP))")
  B=$(python3 -c "print(max(32, min(40, ($SW-16-16-16)//9)))")
  HX=$(python3 -c "w=9*$B+8*2+16; print(int(($W - w*$DP)/2 + (8+$B+2+$B/2)*$DP))")
  HY=$(python3 -c "print(int($H - $NAV - (10+4+$B/2)*$DP))")
  info "tapping toolbar Home at $HX,$HY (screen ${W}x${H}, density $D)"
  if adb shell input tap $HX $HY; then
    sleep 3
    if adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -qv "$PKG"; then pass "Home executed on host via accessibility"; else fail "Home did not leave the app"; fi
  else info "Home button not found"; fi
  shot e2e-6-after-home
else
  fail "manual entry fields not found"
fi
if crashed; then fail "crash during end-to-end"; adb logcat -d -b crash > "$OUT/crash-e2e.txt"; CRIT=1; else pass "no crash during end-to-end"; fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
echo "CRITICAL=$CRIT" >> "$REPORT"
echo "=== crash buffer ==="; adb logcat -d -b crash 2>/dev/null | grep -v "^---------" | head -120
echo "=== FATAL / AndroidRuntime ==="; grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -150
cat "$REPORT"
exit $CRIT
