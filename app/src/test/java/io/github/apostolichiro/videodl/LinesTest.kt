package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.Errors
import io.github.apostolichiro.videodl.engine.Estimate
import io.github.apostolichiro.videodl.engine.Event
import io.github.apostolichiro.videodl.engine.FormatInfo
import io.github.apostolichiro.videodl.engine.Preview
import io.github.apostolichiro.videodl.engine.Versions
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Lines
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.engine.Sniff
import io.github.apostolichiro.videodl.engine.fixableByUpdate
import io.github.apostolichiro.videodl.engine.needsLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinesTest {
    @Test
    fun parsesVideoPartProgress() {
        val e = Lines.parse("@@P|downloading|1048576|4194304|NA|524288.5|6|avc1.64001F|none|NA|NA|고양이 | 웃긴 영상") as Event.Download
        assertEquals("downloading", e.status)
        assertEquals(1048576L, e.done)
        assertEquals(4194304L, e.total)
        assertEquals(0.25f, e.fraction!!, 0.001f)
        assertEquals(6L, e.etaSec)
        assertEquals(false, e.audioOnly)
        assertEquals(true, e.videoOnly)
        assertNull(e.index)
        assertEquals("고양이 | 웃긴 영상", e.title)
    }

    @Test
    fun usesEstimateForFragmentedStreamsAndSpotsAudio() {
        val e = Lines.parse("@@P|downloading|300|NA|1200.0|NA|NA|none|mp4a.40.2|2|5|song") as Event.Download
        assertEquals(1200L, e.total)
        assertTrue(e.audioOnly)
        assertEquals(false, e.videoOnly)
        assertEquals(2, e.index)
        assertEquals(5, e.count)
        assertNull(e.speed)
    }

    @Test
    fun parsesAria2cSummaryLines() {
        val e = Lines.parse("[#0eada7 352KiB/57MiB(0%) CN:7 DL:356KiB ETA:2m43s]") as Event.Download
        assertEquals(352L * 1024, e.done)
        assertEquals(57L * 1024 * 1024, e.total)
        assertEquals(356.0 * 1024, e.speed!!, 0.5)
        assertEquals(163L, e.etaSec)
        assertEquals(false, e.partKnown)
        val unknown = Lines.parse("[#ab12cd 1.5MiB CN:1 DL:0B]") as Event.Download
        assertNull(unknown.total)
        assertEquals((1.5 * 1024 * 1024).toLong(), unknown.done)
        val hours = Lines.parse("[#ab12cd 10MiB/2.0GiB(0%) CN:16 DL:1.2MiB ETA:1h2m3s]") as Event.Download
        assertEquals(3723L, hours.etaSec)
    }

    @Test
    fun parsesItemsAndParts() {
        assertEquals(Event.Item("제목 | 부제", 2, 7, "https://i.ytimg.com/vi/x/hq.jpg"), Lines.parse("@@T|2|7|https://i.ytimg.com/vi/x/hq.jpg|제목 | 부제"))
        assertEquals(Event.Item("영상", null, null), Lines.parse("@@T|NA|NA|NA|영상"))
        assertEquals(Event.Part(audio = false, videoOnly = true), Lines.parse("[download] Destination: /x/고양이.f137.mp4"))
        assertEquals(Event.Part(audio = true, videoOnly = false), Lines.parse("[download] Destination: /x/고양이.f140.m4a"))
        assertEquals(Event.Part(audio = false, videoOnly = false), Lines.parse("[download] Destination: /x/clip.mp4"))
    }

    @Test
    fun parsesPostSteps() {
        assertEquals(Event.Post("Merger", "x"), Lines.parse("@@Q|started|Merger|x"))
        assertEquals("Merger", (Lines.parse("[Merger] Merging formats into \"a.mp4\"") as Event.Post).step)
        assertEquals("FixupM3u8", (Lines.parse("[FixupM3u8] Fixing MPEG-TS in MP4 container of \"a.mp4\"") as Event.Post).step)
        assertEquals(Event.Looking, Lines.parse("[youtube] abc123: Downloading webpage"))
        assertNull(Lines.parse("[download]  45.0% of 10.00MiB at 1.00MiB/s ETA 00:05"))
        assertNull(Lines.parse("[debug] Python 3.11"))
    }

    @Test
    fun findsLinksInSharedText() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", Links.extract("이거 봐 https://youtu.be/dQw4w9WgXcQ?si=abc 대박"))
        assertEquals("https://www.instagram.com/reel/C0ffee/", Links.extract("https://www.instagram.com/reel/C0ffee/."))
        assertEquals("https://vt.tiktok.com/ZSabc/", Links.extract("Check this out! https://vt.tiktok.com/ZSabc/"))
        assertEquals("https://youtu.be/abc", Links.extract("youtu.be/abc"))
        assertNull(Links.extract("그냥 글자"))
        assertEquals("youtube.com", Links.host("https://www.youtube.com/watch?v=1"))
    }

    @Test
    fun explainsErrorsInKorean() {
        val stderr = "WARNING: something\nERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies\n"
        val last = Errors.lastErrorLine(stderr)
        assertEquals("[youtube] abc: Sign in to confirm you’re not a bot. Use --cookies", last)
        assertTrue(Errors.korean(last).contains("로봇"))
        assertTrue(Errors.korean("ERROR: Unsupported URL: https://example.com").contains("찾지 못했어요"))
        assertTrue(Errors.korean("Sign in to confirm your age. This video may be inappropriate for some users.").contains("나이"))
        assertTrue(Errors.korean("Unable to download webpage: HTTP Error 403: Forbidden").contains("403"))
        assertTrue(Errors.korean("Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>").contains("인터넷"))
        assertTrue(Errors.korean("[Instagram] x: Requested content is not available, rate-limit reached or login required").contains("로그인"))
        assertTrue(Errors.korean("Postprocessing: WARNING: unable to obtain file audio codec with ffprobe").contains("소리가 없어서"))
    }

    @Test
    fun decidesWhenANewerEngineMightHelp() {
        assertTrue(Errors.fixableByUpdate("[youtube] x: HTTP Error 403: Forbidden"))
        assertTrue(Errors.fixableByUpdate("Unsupported URL: https://newsite.example/v/1"))
        assertEquals(false, Errors.fixableByUpdate("[youtube] x: Private video. Sign in if you've been granted access"))
        assertEquals(false, Errors.fixableByUpdate("Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>"))
        assertEquals(false, Errors.fixableByUpdate("Sign in to confirm your age"))
    }

    @Test
    fun knowsDomainsAndLoginErrors() {
        assertTrue(Links.isYouTube("https://m.youtube.com/watch?v=1"))
        assertTrue(Links.isYouTube("https://youtu.be/abc"))
        assertEquals(false, Links.isYouTube("https://www.instagram.com/reel/x"))
        assertEquals("instagram.com", Links.baseDomain("www.instagram.com"))
        assertEquals("example.co.kr", Links.baseDomain("m.example.co.kr"))
        assertEquals("naver.com", Links.baseDomain("tv.naver.com"))
        assertTrue(Errors.needsLogin("[Instagram] x: Requested content is not available, rate-limit reached or login required"))
        assertEquals(false, Errors.needsLogin("Unsupported URL: https://x"))
    }

    @Test
    fun sniffsVideoStreamsFromPageRequests() {
        assertEquals(2, Sniff.kind("https://cdn.site.com/v/master.m3u8?token=1"))
        assertEquals(1, Sniff.kind("https://cdn.site.com/v/clip.mp4"))
        assertNull(Sniff.kind("https://cdn.site.com/v/seg001.ts"))
        assertNull(Sniff.kind("https://rr1---sn.googlevideo.com/videoplayback?mime=video/mp4"))
        assertNull(Sniff.kind("https://site.com/app.js"))
        assertEquals("https://ig.cdn/v.mp4?efg=1", Sniff.clean("https://ig.cdn/v.mp4?efg=1&bytestart=0&byteend=9999"))
        assertEquals("https://x.cdn/v.mp4?a=1", Sniff.clean("https://x.cdn/v.mp4?bytestart=0&a=1"))
        var seen = emptyList<String>()
        seen = Sniff.add(seen, "https://a/1.mp4")
        seen = Sniff.add(seen, "https://a/s.m3u8")
        seen = Sniff.add(seen, "https://a/2.mp4")
        seen = Sniff.add(seen, "https://a/1.mp4")
        assertEquals(listOf("https://a/s.m3u8", "https://a/1.mp4", "https://a/2.mp4"), seen)
    }

    @Test
    fun sameVideoWrittenDifferentlyIsOneAddress() {
        val yt = "youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(yt, Links.normalize("https://youtu.be/dQw4w9WgXcQ?si=abc123"))
        assertEquals(yt, Links.normalize("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share&t=42"))
        assertEquals(yt, Links.normalize("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("instagram.com/reel/C0ffee", Links.normalize("https://www.instagram.com/reel/C0ffee/?igsh=MWx1"))
        assertEquals("x.com/a/status/1", Links.normalize("https://twitter.com/a/status/1?s=20"))
        assertEquals("site.com/v?id=7", Links.normalize("https://site.com/v?utm_source=x&id=7"))
    }

    @Test
    fun estimatesSizesPerQualityButton() {
        // A YouTube-like video: H.264 and VP9 up to 1080p, AV1 4K, AAC and Opus audio. 100 seconds long.
        val f = listOf(
            FormatInfo("avc1.640028", "none", 1920, 1080, 30.0, 4000.0, null, "mp4"),
            FormatInfo("vp09.00.40.08", "none", 1920, 1080, 30.0, 2500.0, null, "webm"),
            FormatInfo("avc1.4d401f", "none", 1280, 720, 30.0, 2000.0, 25_000_000, "mp4"),
            FormatInfo("av01.0.12M.08", "none", 3840, 2160, 30.0, 12000.0, null, "mp4"),
            FormatInfo("avc1.4d401e", "none", 854, 480, 30.0, 1000.0, null, "mp4"),
            FormatInfo("none", "mp4a.40.2", null, null, null, 128.0, 1_600_000, "m4a"),
            FormatInfo("none", "opus", null, null, null, 160.0, null, "webm"),
        )
        val c = Estimate.choices(f, 100.0)
        assertEquals(2160, c[Quality.BEST]!!.res)
        assertEquals(1080, c[Quality.P1080]!!.res)
        assertEquals(4000.0 * 1000 / 8 * 100 + 1_600_000, c[Quality.P1080]!!.bytes!!.toDouble(), 1.0)
        assertEquals(25_000_000L + 1_600_000, c[Quality.P720]!!.bytes)
        assertEquals(1_600_000L, c[Quality.AUDIO]!!.bytes)
        assertEquals(2160, Estimate.maxRes(f))
        val p = Preview("u", "t", null, 100.0, null, null, c, 720)
        assertEquals(false, p.shows(Quality.P1080))
        assertEquals(false, p.shows(Quality.P720))
        assertTrue(p.shows(Quality.P480))
        assertTrue(p.shows(Quality.BEST))
        // A vertical 1080x1920 short counts as 1080p.
        assertEquals(1080, FormatInfo("avc1", "mp4a", 1080, 1920, 30.0, null, null, "mp4").res)
        // A plain file from an ordinary site: no codec info, still a video with sound.
        val plain = Estimate.choices(listOf(FormatInfo(null, null, null, null, null, null, 5_000_000, "mp4")), null)
        assertEquals(5_000_000L, plain[Quality.BEST]!!.bytes)
        assertEquals("4K", Estimate.resLabel(2160))
        assertEquals(12, Versions.parse("영상 다운로더 1.0.12"))
        assertEquals(105, Versions.parse("새 버전이에요.\nversionCode: 105"))
        assertNull(Versions.parse("something else"))
    }

    @Test
    fun qualityOptionsAlwaysAimForMp4OrM4a() {
        val best = Quality.BEST.options()
        assertTrue(best.containsAll(listOf("--merge-output-format", "mp4")))
        assertTrue(Quality.P720.options().any { it.startsWith("res:720,") })
        val audio = Quality.AUDIO.options()
        assertTrue(audio.containsAll(listOf("-x", "--audio-format", "m4a")))
        assertEquals(Quality.BEST, Quality.of("nonsense"))
    }
}
