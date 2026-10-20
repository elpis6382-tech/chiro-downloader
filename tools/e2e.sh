#!/usr/bin/env bash
# Drives the real app on the emulator the way a person would and saves screenshots to shots/.
# Never fails the job by itself; results go to shots/result.txt.
set -u
PKG=io.github.apostolichiro.videodl
APK=app/build/outputs/apk/debug/app-x86_64-debug.apk
MEDIA=build/testmedia
mkdir -p shots test-output
DIR="/sdcard/Download/Chiro video"
log() { echo "$*" | tee -a shots/result.txt; }
shot() { adb exec-out screencap -p > "shots/$1.png"; }
ui() { python3 tools/ui.py "$@" 2>&1 | tee -a shots/result.txt; return "${PIPESTATUS[0]}"; }
count() { adb shell "ls '$DIR/'" 2>/dev/null | grep -cF "$1"; }
# Waits until there are more than $3 (default 0) files whose name contains $1 in the app's download folder.
waitfile() {
  local end=$((SECONDS + ${2:-120}))
  while [ $SECONDS -lt $end ]; do
    [ "$(count "$1")" -gt "${3:-0}" ] && return 0
    sleep 3
  done
  return 1
}
# What a share sheet sends: a line of text with a link in it. Sharing doesn't open the app.
share() {
  adb shell "am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$1' -n $PKG/.ShareActivity" >/dev/null
  sleep 2
}
# Types an address into the browser's address bar and opens it (the old address gets selected on tap).
browse() {
  ui tap "주소창" >/dev/null || ui tapclass android.widget.EditText >/dev/null
  sleep 1
  adb shell input text "$1"
  adb shell input keyevent KEYCODE_ENTER
}
# Closes the download sheet if one is still open (it covers everything else).
nosheet() {
  if python3 tools/ui.py wait "다운로드||그래도 다운로드" 1 --exact >/dev/null 2>&1; then
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
}
crashed() { adb shell pidof $PKG >/dev/null 2>&1 || { log "APP NOT RUNNING (crashed?) at: $1"; adb logcat -d -b crash | tail -40 >> shots/result.txt; adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3; }; }

adb install -r "$APK" >/dev/null && log "install: ok" || log "install: FAILED"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

adb shell am start -W -n $PKG/.MainActivity >/dev/null
ui wait "검색하거나 주소 입력" 30 >/dev/null && log "home page: ok" || log "home page: FAILED"
sleep 4
shot 01_home

# 1) Share a web page that has an HLS video on it: downloads without opening the app.
before=$(count "테스트 영상")
share "look at this http://10.0.2.2:8000/page.html"
adb shell am start -W -n $PKG/.MainActivity >/dev/null
ui tap "받는 중" --exact >/dev/null
sleep 3
shot 02_progress
adb shell cmd statusbar expand-notifications
sleep 2
shot 03_notification
adb shell cmd statusbar collapse
waitfile "테스트 영상" 240 "$before" && log "share page: ok" || log "share page: FAILED"
crashed "share"

# 2) Browser: open a direct video link, press the blue button, pick 소리만 in the sheet.
ui tap "홈" --exact >/dev/null
sleep 1
browse "10.0.2.2:8000/clip.mp4"
sleep 6
if adb shell dumpsys input_method | grep -q "mInputShown=true"; then
  log "keyboard after opening page: STILL UP"
  adb shell input keyevent KEYCODE_BACK
  sleep 1
else
  log "keyboard after opening page: hidden (ok)"
fi
shot 04_browser_clip
ui tap "이 페이지 영상 받기" >/dev/null && log "download button: tapped" || log "download button: FAILED"
ui wait "clip" 40 --exact >/dev/null && log "sheet preview: ok" || log "sheet preview: FAILED"
sleep 1
shot 05_sheet
ui tap "소리만" --exact >/dev/null
sleep 1
shot 05b_sheet_audio
ui tap "다운로드" --exact >/dev/null
waitfile "clip.m4a" 120 && log "sheet audio download: ok" || log "sheet audio download: FAILED"
crashed "sheet"

# 2b) The same video again: the sheet says it's already downloaded.
ui tap "이 페이지 영상 받기" >/dev/null
ui tap "소리만" --exact >/dev/null
ui wait "이미 받은 영상이에요" 20 >/dev/null && log "already-downloaded note: ok" || log "already-downloaded note: FAILED"
shot 06_sheet_dup
adb shell input keyevent KEYCODE_BACK
sleep 1

nosheet
# 3) A page whose player builds the stream address in JavaScript: the button lights up once it plays.
browse "10.0.2.2:8000/js.html"
sleep 8
shot 07_js_page
ui wait "js.html" 5 >/dev/null && log "address replaced on typing: ok" || log "address replaced on typing: FAILED"
ui tap "이 페이지 영상 받기" >/dev/null
ui wait "스트리밍 영상 1||영상 파일 1" 40 >/dev/null && log "page streams listed: ok" || log "page streams listed: FAILED"
sleep 2
shot 08_js_sheet
ui tap "받기" --exact >/dev/null && log "stream button: tapped" || log "stream button: FAILED"
waitfile "자바스크립트 영상" 150 && log "stream download (page title): ok" || log "stream download (page title): FAILED"
crashed "stream"

nosheet
# 4) The site's own download buttons: a video (goes through the engine) and a document (Android's downloader).
ui tap "자료실로 가기" >/dev/null && log "site link: tapped" || log "site link: FAILED"
sleep 5
shot 09_site
ui tap "영상 다운로드" >/dev/null && log "site video button: tapped" || log "site video button: FAILED"
# WebView doesn't pass on the link's suggested name, so the file keeps its own name (clip.mp4).
waitfile "clip.mp4" 120 && log "site video download: ok" || log "site video download: FAILED"
ui tap "문서 다운로드" >/dev/null
waitfile "notes" 60 && log "site file download: ok" || log "site file download: FAILED"

nosheet
# 5) Tabs: open a second one.
ui tap "탭 1개" >/dev/null && sleep 2 && shot 10_tabs
ui tap "새 탭" --exact >/dev/null
sleep 2
ui wait "탭 2개" 10 >/dev/null && log "tabs: ok" || log "tabs: FAILED"
shot 11_new_tab
crashed "tabs"

nosheet
# 6) Pause and go on: a slow download is paused half-way, then finished; the file must be exact.
share "http://10.0.2.2:8000/slow/big.mp4"
adb shell am start -W -n $PKG/.MainActivity >/dev/null
ui tap "받는 중" --exact >/dev/null
ui wait "MB /" 90 >/dev/null
sleep 3
shot 12_before_pause
ui tap "일시정지" --exact >/dev/null && log "pause button: tapped" || log "pause button: FAILED"
ui wait "이어받기" 20 --exact >/dev/null && log "paused: ok" || log "paused: FAILED"
sleep 2
shot 13_paused
ui tap "이어받기" --exact >/dev/null
if waitfile "big" 240; then
  sleep 3
  want=$(md5sum "$MEDIA/big.mp4" | cut -d' ' -f1)
  got=$(adb shell "md5sum '$DIR/big.mp4'" | cut -d' ' -f1)
  [ "$want" = "$got" ] && log "resume: ok (file identical)" || log "resume: FAILED (file differs: $want vs $got)"
else
  log "resume: FAILED (never finished)"
fi
shot 14_after_resume
crashed "pause"

nosheet
# 7) Files: the list, then the built-in player.
ui tap "파일" --exact >/dev/null
sleep 2
shot 15_files
ui wait "테스트 영상" 10 >/dev/null && log "files list: ok" || log "files list: FAILED"
ui tap "테스트 영상" >/dev/null
ui wait "재생 위치" 15 >/dev/null && log "player: ok" || log "player: FAILED"
sleep 3
shot 16_player
adb shell input tap 300 600
sleep 1
shot 17_player_controls
crashed "player"
adb shell input keyevent KEYCODE_BACK
sleep 2

nosheet
# 8) Settings, and the home page with recent downloads.
ui tap "설정" --exact >/dev/null
ui wait "광고·팝업 차단" 10 >/dev/null && log "settings: ok" || log "settings: FAILED"
shot 18_settings
ui tap "홈" --exact >/dev/null
sleep 2
shot 19_home_recent
ui wait "최근 받은 영상" 10 >/dev/null && log "home recent: ok" || log "home recent: FAILED"
crashed "end"

adb logcat -d -b crash > shots/crash.log 2>/dev/null || true
adb logcat -d -s VideoDl:* AndroidRuntime:* > shots/app.log 2>/dev/null || true
adb shell "ls -la '$DIR/'" >> shots/result.txt 2>&1
adb pull "$DIR/vdl-test-output.mp4" test-output/engine-test.mp4 >/dev/null 2>&1 || true
i=0
adb shell "ls '$DIR/'" | tr -d '\r' | while read -r f; do
  i=$((i + 1))
  case "$f" in vdl-test-output.mp4|*.txt) continue ;; esac
  adb pull "$DIR/$f" "test-output/app-$i.${f##*.}" >/dev/null 2>&1 || true
done
log "done"
exit 0
