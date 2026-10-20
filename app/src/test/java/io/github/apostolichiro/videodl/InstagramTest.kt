package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.Instagram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramTest {
    @Test
    fun findsPostCodes() {
        assertEquals("C0ffee_Xyz1", Instagram.shortcode("https://www.instagram.com/p/C0ffee_Xyz1/?igsh=abc"))
        assertEquals("DAbc-12", Instagram.shortcode("https://instagram.com/someone/reel/DAbc-12/"))
        assertEquals(null, Instagram.shortcode("https://www.instagram.com/someone/"))
        assertEquals("3251455890286390517", Instagram.mediaId("C0ffee_Xyz1abc"))
        assertTrue(Instagram.mayHavePhotos("https://www.instagram.com/p/C0ffee_Xyz1/"))
        assertTrue(!Instagram.mayHavePhotos("https://www.instagram.com/reel/C0ffee_Xyz1/"))
        assertTrue(!Instagram.mayHavePhotos("https://www.instagram.com/reels/C0ffee_Xyz1/"))
    }

    @Test
    fun namesPosts() {
        val p = Instagram.Post("C0ffee", "chiro", "\n  첫 줄\n둘째 줄", 8, listOf(Instagram.Item("a.jpg", false), Instagram.Item("b.mp4", true), Instagram.Item("c.jpg", false)))
        assertEquals("첫 줄", p.title)
        assertEquals("a.jpg", p.cover)
        assertEquals("사진 2장 · 영상 1개", p.summary)
        assertEquals("@chiro 게시물", p.copy(caption = null).title)
    }

    @Test
    fun readsAlbumsAtTheirLargestSize() {
        val json = """
            {"items":[{"media_type":8,"code":"C0ffee","user":{"username":"chiro"},"caption":{"text":"hi"},
              "carousel_media":[
                {"media_type":1,"image_versions2":{"candidates":[
                  {"width":320,"height":400,"url":"https://cdn/small.jpg"},
                  {"width":1080,"height":1350,"url":"https://cdn/big.jpg"}]}},
                {"media_type":2,"image_versions2":{"candidates":[{"width":1080,"height":1920,"url":"https://cdn/poster.jpg"}]},
                 "video_versions":[{"width":480,"height":854,"url":"https://cdn/v480.mp4"},{"width":720,"height":1280,"url":"https://cdn/v720.mp4"}]}
              ]}]}
        """.trimIndent()
        val p = Instagram.parse(json)!!
        assertEquals(8, p.type)
        assertEquals("chiro", p.owner)
        assertEquals(listOf(Instagram.Item("https://cdn/big.jpg", false), Instagram.Item("https://cdn/v720.mp4", true)), p.items)
        assertEquals(1, p.photos)
        assertTrue(p.hasPhotos)
        val single = Instagram.parse("""{"items":[{"media_type":1,"code":"x","image_versions2":{"candidates":[{"width":1,"height":1,"url":"https://cdn/a.jpg"}]}}]}""")!!
        assertEquals(1, single.items.size)
    }

    @Test
    fun readsTheEmbedPhoto() {
        val html = """<div><img class="EmbeddedMediaImage" alt="x" src="https://cdn/s.jpg?a=1&amp;b=2" srcset="https://cdn/640.jpg?a=1&amp;b=2 640w,https://cdn/1080.jpg?a=1&amp;b=2 1080w" /></div>"""
        assertEquals("https://cdn/1080.jpg?a=1&b=2", Instagram.parseEmbed(html))
    }
}
