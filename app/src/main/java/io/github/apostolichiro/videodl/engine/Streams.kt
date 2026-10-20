package io.github.apostolichiro.videodl.engine

import java.net.HttpURLConnection
import java.net.URL

/**
 * Choosing which of the video streams a page played is "the" video: pages also load ads, previews of other
 * videos and trailers. The real one is nearly always the longest, so each stream's length is read from its
 * playlist (a few small downloads, well under a second).
 */
object Streams {
    data class Variant(val url: String, val bandwidth: Long, val height: Int?)

    /** What a playlist says: the variants of a master playlist, or the length of a media playlist. */
    data class Playlist(val variants: List<Variant>, val seconds: Double?)

    data class Pick(val url: String, val seconds: Double?, val height: Int?)

    private val shortClip = Regex("""(preview|trailer|teaser|thumb|sprite|promo|advert|[/_.-]ads?[/_.-]|vast|preroll)""", RegexOption.IGNORE_CASE)

    /** Streams whose address says they're previews or ads. */
    fun looksLikeExtra(url: String): Boolean = shortClip.containsMatchIn(url.substringBefore('?'))

    fun parse(text: String, base: String): Playlist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = mutableListOf<Variant>()
        var seconds = 0.0
        var segments = 0
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    val bw = Regex("""(?<![-A-Z])BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull()
                    val next = lines.getOrNull(i + 1)
                    if (next != null && !next.startsWith("#")) {
                        variants += Variant(resolve(base, next), bw, h)
                        i++
                    }
                }
                l.startsWith("#EXTINF:", ignoreCase = true) -> {
                    l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()?.let { seconds += it; segments++ }
                }
            }
            i++
        }
        return Playlist(variants, if (segments > 0) seconds else null)
    }

    fun resolve(base: String, ref: String): String = try {
        URL(URL(base), ref).toString()
    } catch (_: Exception) {
        ref
    }

    /** The best variant of a master playlist (highest resolution, then bandwidth). */
    fun best(variants: List<Variant>): Variant? = variants.maxWithOrNull(compareBy<Variant>({ it.height ?: 0 }, { it.bandwidth }))

    /**
     * The stream that is most likely the page's own video: the longest one that doesn't look like an ad or
     * preview. [get] fetches a playlist's text (null when it can't be read). Falls back to the first stream.
     */
    fun pick(streams: List<String>, get: (String) -> String?): Pick? {
        if (streams.isEmpty()) return null
        val measured = streams.take(6).map { s ->
            if (Sniff.kind(s) != 2) return@map Pick(s, null, null)
            val text = get(s) ?: return@map Pick(s, null, null)
            val p = parse(text, s)
            if (p.variants.isEmpty()) return@map Pick(s, p.seconds, null)
            val v = best(p.variants)!!
            val inner = get(v.url)?.let { parse(it, v.url) }
            Pick(s, inner?.seconds, v.height)
        }
        val real = measured.filterNot { looksLikeExtra(it.url) }.ifEmpty { measured }
        return real.filter { it.seconds != null }.maxByOrNull { it.seconds!! } ?: real.first()
    }

    /** Plain HTTP fetch of a playlist with the page's headers (and cookies, when given). */
    fun fetchText(url: String, referer: String?, userAgent: String?, cookie: String?): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            userAgent?.let { setRequestProperty("User-Agent", it) }
            referer?.let { setRequestProperty("Referer", it) }
            cookie?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { s ->
                val b = s.readNBytesCompat(2 shl 20)
                String(b, Charsets.UTF_8).takeIf { it.trimStart().startsWith("#EXTM3U") }
            }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
