package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.AdBlock
import io.github.apostolichiro.videodl.engine.SearchEngine
import io.github.apostolichiro.videodl.engine.Web
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTest {
    @Test
    fun addressBarTurnsTextIntoAnAddressOrASearch() {
        assertEquals("https://youtube.com", Web.target("youtube.com", SearchEngine.GOOGLE))
        assertEquals("https://m.site.co.kr/v?id=1", Web.target(" https://m.site.co.kr/v?id=1 ", SearchEngine.GOOGLE))
        assertEquals("https://tv.naver.com/v/123", Web.target("tv.naver.com/v/123", SearchEngine.GOOGLE))
        assertEquals("http://10.0.2.2:8000/js.html", Web.target("10.0.2.2:8000/js.html", SearchEngine.GOOGLE))
        assertEquals("https://www.google.com/search?q=%EA%B3%A0%EC%96%91%EC%9D%B4+%EC%98%81%EC%83%81", Web.target("고양이 영상", SearchEngine.GOOGLE))
        assertEquals("https://m.search.naver.com/search.naver?query=cats", Web.target("cats", SearchEngine.NAVER))
        assertEquals(null, Web.target("   ", SearchEngine.GOOGLE))
        assertEquals("youtube.com/watch?v=1", Web.pretty("https://www.youtube.com/watch?v=1"))
    }

    @Test
    fun knowsPagesWithOneVideo() {
        assertTrue(Web.isVideoPage("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(Web.isVideoPage("https://www.youtube.com/shorts/abc_DEF-1"))
        assertTrue(Web.isVideoPage("https://youtu.be/abc"))
        assertFalse(Web.isVideoPage("https://m.youtube.com/"))
        assertFalse(Web.isVideoPage("https://m.youtube.com/results?search_query=cat"))
        assertTrue(Web.isVideoPage("https://www.instagram.com/reel/C0ffee/"))
        assertFalse(Web.isVideoPage("https://www.instagram.com/someone/"))
        assertTrue(Web.isVideoPage("https://www.tiktok.com/@a.b/video/7300000000000000000"))
        assertTrue(Web.isVideoPage("https://x.com/user/status/1790000000000000000"))
        assertTrue(Web.isVideoPage("https://twitter.com/user/status/1"))
        assertTrue(Web.isVideoPage("https://tv.naver.com/v/12345"))
        assertTrue(Web.isVideoPage("https://chzzk.naver.com/video/123"))
        assertTrue(Web.isVideoPage("https://vimeo.com/76979871"))
        assertFalse(Web.isVideoPage("https://vimeo.com/watch"))
        assertFalse(Web.isVideoPage("https://example.com/video/1"))
    }

    @Test
    fun blocksAdNetworksButNotOrdinarySites() {
        assertTrue(AdBlock.blocks("pagead2.googlesyndication.com"))
        assertTrue(AdBlock.blocks("securepubads.g.doubleclick.net"))
        assertTrue(AdBlock.blocks("cdn.taboola.com"))
        assertFalse(AdBlock.blocks("www.youtube.com"))
        assertFalse(AdBlock.blocks("rr3---sn-abc.googlevideo.com"))
        assertFalse(AdBlock.blocks("notdoubleclick.net"))
        assertFalse(AdBlock.blocks("localhost"))
    }
}
