#!/usr/bin/env bash
# Makes a tiny "website" for the emulator tests:
#   clip.mp4         a plain 10 s video file (direct link)
#   hls/index.m3u8   the same clip as an HLS stream in MPEG-TS pieces (what most sites serve)
#   page.html        a web page with that stream in a <video> tag, titled in Korean
#   big.mp4          a ~20 MB file, to see a download split over many connections
#   js.html          a page whose player builds the stream address in JavaScript (yt-dlp can't see it,
#                    the in-app browser can)
#   site.html        a page with the site's own download buttons
set -euo pipefail
OUT=${1:-build/testmedia}
rm -rf "$OUT"
mkdir -p "$OUT/hls"

ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=10" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=10" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 30 -c:a aac -b:a 96k -shortest \
  -movflags +faststart "$OUT/clip.mp4"

# A second plain file for the browser test (clip.mp4 is used by the site's download button).
cp "$OUT/clip.mp4" "$OUT/clip2.mp4"
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

ffmpeg -v error -y -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=30" \
  -c:v libx264 -preset ultrafast -b:v 5M -maxrate 5M -bufsize 2M -pix_fmt yuv420p "$OUT/big.mp4"

cat > "$OUT/js.html" <<'HTML'
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>자바스크립트 영상</title>
</head>
<body>
<h1>자바스크립트 영상</h1>
<video id="v" controls muted playsinline width="320"></video>
<p style="font-size:24px"><a href="site.html">자료실로 가기</a></p>
<script>
  // Like a site's own player: the address is put together in code, then fetched.
  var u = ['h', 'l', 's'].join('') + '/' + ['index', 'm3u8'].join('.');
  fetch(u).then(function (r) { return r.text(); }).catch(function () {});
  document.getElementById('v').src = u;
</script>
</body>
</html>
HTML

cat > "$OUT/site.html" <<'HTML'
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>자료실</title>
</head>
<body style="font-size:24px">
<h1>자료실</h1>
<p><a id="video" href="clip.mp4" download="사이트 영상.mp4">영상 다운로드</a></p>
<p><a id="doc" href="notes.txt" download>문서 다운로드</a></p>
</body>
</html>
HTML
echo "테스트 문서" > "$OUT/notes.txt"

ls -la "$OUT" "$OUT/hls"
