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

    /** How one segment is encrypted (AES-128 with the key at [uri]), if it is. */
    data class Key(val method: String, val uri: String?, val iv: String?)

    data class Segment(val url: String, val sequence: Long, val key: Key?, val map: String?)

    /** A media playlist's pieces in order; [unsupported] says why it can't be downloaded piece by piece. */
    data class Media(val segments: List<Segment>, val unsupported: String?)

    private val attr = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        attr.findAll(line.substringAfter(':')).associate { m -> m.groupValues[1] to (m.groups[3]?.value ?: m.groupValues[2]) }

    fun media(text: String, base: String): Media {
        val segments = mutableListOf<Segment>()
        var sequence = 0L
        var key: Key? = null
        var map: String? = null
        var unsupported: String? = null
        var expectSegment = false
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> sequence = l.substringAfter(':').trim().toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY:", true) -> {
                    val a = attrs(l)
                    val method = a["METHOD"]?.uppercase() ?: "NONE"
                    key = when (method) {
                        "NONE" -> null
                        "AES-128" -> Key(method, a["URI"]?.let { resolve(base, it) }, a["IV"])
                        else -> {
                            unsupported = "encryption $method"
                            Key(method, null, null)
                        }
                    }
                }
                l.startsWith("#EXT-X-MAP:", true) -> map = attrs(l)["URI"]?.let { resolve(base, it) }
                l.startsWith("#EXT-X-BYTERANGE", true) -> unsupported = "byte ranges"
                l.startsWith("#EXTINF", true) -> expectSegment = true
                l.startsWith("#") -> Unit
                else -> {
                    // A line that isn't a tag is a piece (normally right after its #EXTINF).
                    segments += Segment(resolve(base, l), sequence + segments.size, key, map)
                    expectSegment = false
                }
            }
        }
        return Media(segments, unsupported)
    }

    /** The IV for AES-128: given in the playlist, or the segment's sequence number. */
    fun iv(key: Key, sequence: Long): ByteArray {
        val hex = key.iv?.removePrefix("0x")?.removePrefix("0X")
        if (hex != null && hex.length == 32) return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (sequence shr (8 * i)).toByte() }
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
