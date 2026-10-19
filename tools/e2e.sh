#!/usr/bin/env bash
# Drives the real app on the emulator the way a person would and saves screenshots to shots/.
# Never fails the job by itself; results go to shots/result.txt.
set -u
PKG=io.github.apostolichiro.videodl
APK=app/build/outputs/apk/debug/app-x86_64-debug.apk
mkdir -p shots test-output
log() { echo "$*" | tee -a shots/result.txt; }
shot() { adb exec-out screencap -p > "shots/$1.png"; }
ui() { python3 tools/ui.py "$@" 2>&1 | tee -a shots/result.txt; return "${PIPESTATUS[0]}"; }
# What a share sheet sends: a line of text with a link in it.
share() { adb shell "am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$1' -n $PKG/.MainActivity" >/dev/null; }

adb install -r "$APK" >/dev/null && log "install: ok" || log "install: FAILED"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

adb shell am start -W -n $PKG/.MainActivity >/dev/null
ui wait "영상 다운로더" 30 >/dev/null
sleep 4
shot 1_home

# 1) Share a web page that has an HLS video on it.
share "look at this http://10.0.2.2:8000/page.html"
sleep 5
shot 2_downloading
adb shell cmd statusbar expand-notifications
sleep 2
shot 3_notification
adb shell cmd statusbar collapse
ui wait "완료 ·||못 받았어요" 240 >/dev/null
sleep 2
shot 4_page_done
ui wait "완료 ·" 1 >/dev/null && log "share page: ok" || log "share page: FAILED"

# 2) Link box: pick 소리만 (audio only), type a direct link, press Enter (= 받기).
ui tap "소리만" --exact >/dev/null
ui tap "영상 링크를 붙여넣으세요" >/dev/null || ui tapclass android.widget.EditText >/dev/null
sleep 1
adb shell input text "http://10.0.2.2:8000/clip.mp4"
sleep 1
shot 5_typed
adb shell input keyevent KEYCODE_ENTER
sleep 3
ui wait "M4A||못 받았어요" 120 >/dev/null
sleep 2
shot 6_audio_done
ui wait "M4A" 1 >/dev/null && log "audio only: ok" || log "audio only: FAILED"

# 3) A real site, for the record (data-center IPs are often blocked by big sites).
ui tap "최고 화질" --exact >/dev/null
share "https://www.youtube.com/watch?v=jNQXAC9IVRw"
sleep 3
ui gone "취소" 240 >/dev/null
sleep 2
shot 7_youtube
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
log "screen after youtube: $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\+"' | head -14 | tr '\n' ' ')"

# 4) Open the finished video from the list.
ui tap "보기" --exact >/dev/null && sleep 4 && shot 8_open && adb shell input keyevent KEYCODE_BACK

adb shell ls -la "/sdcard/Download/영상다운로더/" >> shots/result.txt 2>&1
adb pull "/sdcard/Download/영상다운로더/vdl-test-output.mp4" test-output/engine-test.mp4 >/dev/null 2>&1 || true
i=0
adb shell "ls /sdcard/Download/영상다운로더/" | tr -d '\r' | while read -r f; do
  i=$((i + 1))
  case "$f" in vdl-test-output.mp4) continue ;; esac
  adb pull "/sdcard/Download/영상다운로더/$f" "test-output/app-$i.${f##*.}" >/dev/null 2>&1 || true
done
log "done"
exit 0
