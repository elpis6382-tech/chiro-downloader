#!/usr/bin/env bash
# Makes a tiny "website" for the emulator tests:
#   clip.mp4         a plain 10 s video file (direct link)
#   hls/index.m3u8   the same clip as an HLS stream in MPEG-TS pieces (what most sites serve)
#   page.html        a web page with that stream in a <video> tag, titled in Korean
set -euo pipefail
OUT=${1:-build/testmedia}
rm -rf "$OUT"
mkdir -p "$OUT/hls"

ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=10" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=10" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 30 -c:a aac -b:a 96k -shortest \
  -movflags +faststart "$OUT/clip.mp4"

ffmpeg -v error -y -i "$OUT/clip.mp4" -c copy -f hls -hls_time 2 -hls_playlist_type vod \
  -hls_segment_filename "$OUT/hls/seg%03d.ts" "$OUT/hls/index.m3u8"

cat > "$OUT/page.html" <<'HTML'
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>테스트 영상</title>
<meta property="og:title" content="테스트 영상">
</head>
<body>
<h1>테스트 영상</h1>
<video controls width="640">
  <source src="hls/index.m3u8" type="application/x-mpegURL">
</video>
</body>
</html>
HTML

ls -la "$OUT" "$OUT/hls"
