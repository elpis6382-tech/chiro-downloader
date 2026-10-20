package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=900000,BANDWIDTH=1000000,RESOLUTION=854x480
        480/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
        https://cdn.example.com/v/1080/index.m3u8
    """.trimIndent()

    private fun media(vararg seconds: Double) = "#EXTM3U\n" + seconds.joinToString("\n") { "#EXTINF:$it,\nseg.ts" } + "\n#EXT-X-ENDLIST"

    @Test
    fun readsPlaylists() {
        val p = Streams.parse(master, "https://cdn.example.com/v/master.m3u8")
        assertEquals(2, p.variants.size)
        assertEquals("https://cdn.example.com/v/480/index.m3u8", p.variants[0].url)
        assertEquals(1000000L, p.variants[0].bandwidth)
        assertEquals(1080, Streams.best(p.variants)!!.height)
        assertEquals(30.5, Streams.parse(media(10.0, 10.0, 10.5), "https://x/a.m3u8").seconds!!, 0.01)
    }

    @Test
    fun picksTheLongestRealVideo() {
        val pages = mapOf(
            "https://ads.example.com/ad/vast/1.m3u8" to media(30.0, 30.0, 30.0),
            "https://cdn.example.com/preview/2.m3u8" to media(5.0, 5.0),
            "https://cdn.example.com/v/master.m3u8" to master,
            "https://cdn.example.com/v/1080/index.m3u8" to media(600.0, 600.0),
            "https://cdn.example.com/other/index.m3u8" to media(20.0),
        )
        val streams = listOf(
            "https://cdn.example.com/other/index.m3u8",
            "https://ads.example.com/ad/vast/1.m3u8",
            "https://cdn.example.com/preview/2.m3u8",
            "https://cdn.example.com/v/master.m3u8",
        )
        val pick = Streams.pick(streams) { pages[it] }!!
        assertEquals("https://cdn.example.com/v/master.m3u8", pick.url)
        assertEquals(1200.0, pick.seconds!!, 0.01)
        assertEquals(1080, pick.height)
        assertTrue(Streams.looksLikeExtra("https://cdn.example.com/preview/2.m3u8"))
        // Nothing readable: the newest one.
        assertEquals(streams[0], Streams.pick(streams) { null }!!.url)
    }
}
