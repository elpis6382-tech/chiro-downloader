package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.Errors
import io.github.apostolichiro.videodl.engine.Event
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Lines
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.engine.fixableByUpdate
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
    fun parsesPostSteps() {
        assertEquals(Event.Post("Merger", "x"), Lines.parse("@@Q|started|Merger|x"))
        assertEquals("Merger", (Lines.parse("[Merger] Merging formats into \"a.mp4\"") as Event.Post).step)
        assertEquals("FixupM3u8", (Lines.parse("[FixupM3u8] Fixing MPEG-TS in MP4 container of \"a.mp4\"") as Event.Post).step)
        assertEquals(Event.Looking, Lines.parse("[youtube] abc123: Downloading webpage"))
        assertNull(Lines.parse("[download] Destination: a.mp4"))
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
    fun qualityOptionsAlwaysAimForMp4OrM4a() {
        val best = Quality.BEST.options()
        assertTrue(best.containsAll(listOf("--merge-output-format", "mp4")))
        assertTrue(Quality.P720.options().any { it.startsWith("res:720,") })
        val audio = Quality.AUDIO.options()
        assertTrue(audio.containsAll(listOf("-x", "--audio-format", "m4a")))
        assertEquals(Quality.BEST, Quality.of("nonsense"))
    }
}
