package io.github.apostolichiro.videodl.engine

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instagram posts with photos (single photos and albums), which yt-dlp doesn't download: the post is read
 * from Instagram's own media API with the in-app browser's login, and every photo/video in it is listed
 * at its largest size. Without a login only a single photo can be found (from the post's embed page).
 */
object Instagram {
    data class Item(val url: String, val isVideo: Boolean)

    /** [type]: 1 photo, 2 video, 8 album. */
    data class Post(val code: String, val owner: String?, val caption: String?, val type: Int, val items: List<Item>) {
        val photos get() = items.count { !it.isVideo }
        val videos get() = items.count { it.isVideo }
        /** Posts yt-dlp can't do on its own: anything with a photo in it. */
        val hasPhotos get() = photos > 0
        /** A name for it: the caption's first line, or whose post it is. */
        val title: String? get() = caption?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(60)
            ?: owner?.let { "@$it 게시물" }
        /** Its first photo (for the picture in lists). */
        val cover: String? get() = items.firstOrNull { !it.isVideo }?.url
        /** "사진 3장 · 영상 1개". */
        val summary: String get() = listOfNotNull(photos.takeIf { it > 0 }?.let { "사진 ${it}장" }, videos.takeIf { it > 0 }?.let { "영상 ${it}개" }).joinToString(" · ")
    }

    private val postPath = Regex("""instagram\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private const val CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val APP_ID = "936619743392459"

    fun shortcode(url: String): String? = postPath.find(url)?.groupValues?.get(1)

    private val photoPath = Regex("""instagram\.com/(?:[^/?#]+/)?p/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)

    /** Posts (/p/) can hold photos; reels never do. */
    fun mayHavePhotos(url: String): Boolean = photoPath.containsMatchIn(url)

    /** The post's number from its code (the first 11 characters, base 64). */
    fun mediaId(code: String): String {
        var n = BigInteger.ZERO
        val b64 = BigInteger.valueOf(64)
        for (c in code.take(11)) {
            val i = CODE_CHARS.indexOf(c)
            if (i < 0) break
            n = n.multiply(b64).add(BigInteger.valueOf(i.toLong()))
        }
        return n.toString()
    }

    private fun JSONObject.biggest(): String? {
        val list = optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return null
        var best: JSONObject? = null
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            if (best == null || c.optInt("width") * c.optInt("height") > best.optInt("width") * best.optInt("height")) best = c
        }
        return best?.optString("url")?.ifBlank { null }
    }

    private fun JSONObject.bestVideo(): String? {
        val list = optJSONArray("video_versions") ?: return null
        var best: JSONObject? = null
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            if (best == null || c.optInt("width") * c.optInt("height") > best.optInt("width") * best.optInt("height")) best = c
        }
        return best?.optString("url")?.ifBlank { null }
    }

    private fun item(o: JSONObject): Item? =
        if (o.optInt("media_type") == 2) o.bestVideo()?.let { Item(it, true) } else o.biggest()?.let { Item(it, false) }

    /** Reads the media API's answer. */
    fun parse(json: String): Post? {
        val root = JSONObject(json)
        val m = root.optJSONArray("items")?.optJSONObject(0) ?: return null
        val type = m.optInt("media_type")
        val items = if (type == 8) {
            val children = m.optJSONArray("carousel_media") ?: JSONArray()
            (0 until children.length()).mapNotNull { children.optJSONObject(it)?.let(::item) }
        } else listOfNotNull(item(m))
        if (items.isEmpty()) return null
        return Post(
            code = m.optString("code"),
            owner = m.optJSONObject("user")?.optString("username")?.ifBlank { null },
            caption = m.optJSONObject("caption")?.optString("text")?.ifBlank { null },
            type = type,
            items = items,
        )
    }

    /** The single photo an embed page shows (no login needed). */
    fun parseEmbed(html: String): String? {
        val tag = Regex("""<img[^>]+class="[^"]*EmbeddedMediaImage[^"]*"[^>]*>""").find(html)?.value ?: return null
        val srcset = Regex("""srcset="([^"]+)"""").find(tag)?.groupValues?.get(1)
        val fromSet = srcset?.split(",")?.map { it.trim() }?.mapNotNull { part ->
            val bits = part.split(" ")
            val w = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
            bits.firstOrNull()?.let { it to w }
        }?.maxByOrNull { it.second }?.first
        val src = fromSet ?: Regex("""src="([^"]+)"""").find(tag)?.groupValues?.get(1)
        return src?.replace("&amp;", "&")
    }

    private fun get(url: String, ua: String?, cookie: String?, api: Boolean): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", ua ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.instagram.com/")
            setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            if (api) {
                setRequestProperty("X-IG-App-ID", APP_ID)
                setRequestProperty("X-ASBD-ID", "198387")
                setRequestProperty("X-IG-WWW-Claim", "0")
                setRequestProperty("Origin", "https://www.instagram.com")
                setRequestProperty("Accept", "*/*")
            }
            cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** The post at [url], with the in-app browser's Instagram login ([cookie]); null when it can't be read. */
    fun fetch(url: String, ua: String?, cookie: String?): Post? {
        val code = shortcode(url) ?: return null
        val text = get("https://www.instagram.com/api/v1/media/${mediaId(code)}/info/", ua, cookie, api = true) ?: return null
        return try {
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Without a login: the photo of a single-photo post, from its embed page. Null for videos (the embed
     * page only has their still picture).
     */
    fun embedPhoto(url: String, ua: String?): Post? {
        val code = shortcode(url) ?: return null
        val html = get("https://www.instagram.com/p/$code/embed/captioned/", ua, null, api = false) ?: return null
        if (html.contains("EmbeddedMediaVideo") || html.contains("\"is_video\":true") || html.contains("\\\"is_video\\\":true")) return null
        val photo = parseEmbed(html) ?: return null
        return Post(code, null, null, 1, listOf(Item(photo, false)))
    }
}
