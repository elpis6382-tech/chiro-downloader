package io.github.apostolichiro.videodl

import android.content.Context
import android.text.Html
import com.yausername.youtubedl_android.YoutubeDL
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Errors
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up a link (title, picture, sizes per quality) without downloading.
 * A lookup that is already running is shared (the browser may have started it before the sheet opened),
 * results are kept for a while, and what yt-dlp found is saved so the download can start from it.
 */
object Infos {
    private class Entry(val preview: Preview, val at: Long, val json: File?)
    private class Flight(val result: CompletableDeferred<InfoState>, var job: Job? = null, val pid: String, var waiters: Int = 0)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 30
    }
    private val flights = HashMap<String, Flight>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val KEEP_MS = 15 * 60 * 1000L
    /** Video addresses in the saved lookup stay usable for a few hours; well inside that is safe. */
    private const val JSON_MS = 30 * 60 * 1000L

    fun cached(url: String): Preview? = synchronized(cache) {
        cache[Links.normalize(url)]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.preview
    }

    /** yt-dlp's saved lookup of [url], if it is recent and for a single video. */
    fun infoJsonFor(url: String): File? = synchronized(cache) {
        cache[Links.normalize(url)]?.takeIf { System.currentTimeMillis() - it.at < JSON_MS }?.json?.takeIf { it.isFile }
    }

    /**
     * The lookup for [url]. Logins from the in-app browser are used for sites that need them
     * (not YouTube, which works best without). When nobody waits for a running lookup any more, yt-dlp is stopped.
     */
    suspend fun fetch(ctx: Context, url: String, referer: String? = null, userAgent: String? = null): InfoState {
        cached(url)?.let { return InfoState.Ready(it) }
        val key = Links.normalize(url)
        val app = ctx.applicationContext
        val flight = synchronized(flights) {
            val f = flights[key] ?: Flight(CompletableDeferred(), pid = "info-" + System.nanoTime()).also { f ->
                flights[key] = f
                f.job = scope.launch {
                    val r = lookup(app, url, key, f.pid, referer, userAgent)
                    synchronized(flights) { if (flights[key] === f) flights.remove(key) }
                    f.result.complete(r)
                }
            }
            f.waiters++
            f
        }
        try {
            return flight.result.await()
        } finally {
            synchronized(flights) {
                flight.waiters--
                if (flight.waiters == 0 && !flight.result.isCompleted) {
                    flights.remove(key)
                    flight.job?.cancel()
                    Engine.cancel(flight.pid)
                }
            }
        }
    }

    private fun lookup(ctx: Context, url: String, key: String, pid: String, referer: String?, userAgent: String?): InfoState {
        val youtube = Links.isYouTube(url)
        val dir = File(ctx.cacheDir, "info").apply { mkdirs() }
        // Old lookups are useless after a while.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 2 * JSON_MS }?.forEach { it.delete() }
        val cookieFile = File(dir, "$pid.cookies")
        val json = File(dir, "$pid.json")
        return try {
            val cookies = if (!youtube && Cookies.has(url)) Cookies.export(url, cookieFile) else null
            val ua = if (youtube) null else userAgent
            val p = try {
                Engine.info(ctx, url, pid, cookies, referer, ua, quickYouTube = youtube, saveTo = json)
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                // Some videos (made for kids, age limits) need the full way; it is slower but works.
                if (!youtube) throw e
                Engine.info(ctx, url, pid, cookies, referer, ua, quickYouTube = false, saveTo = json)
            }
            synchronized(cache) { cache[key] = Entry(p, System.currentTimeMillis(), json.takeIf { !p.isPlaylist }) }
            InfoState.Ready(p)
        } catch (e: YoutubeDL.CanceledException) {
            InfoState.Failed("취소했어요")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InfoState.Failed(Errors.korean(Errors.lastErrorLine(e.message) ?: e.message))
        } finally {
            cookieFile.delete()
        }
    }
}

/**
 * A title and a picture in a fraction of a second (YouTube's oEmbed, or the page's own og: tags),
 * shown while yt-dlp is still looking the video up.
 */
object QuickMeta {
    data class Meta(val title: String?, val image: String?)

    private val cache = ConcurrentHashMap<String, Meta>()
    private val youtubeId = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/)([\w-]{11})""")

    fun fetch(url: String): Meta? {
        cache[url]?.let { return it }
        // A link straight to a video file has no page to read.
        if (io.github.apostolichiro.videodl.engine.Sniff.kind(url) != null) return null
        val m = try {
            if (Links.isYouTube(url)) youtube(url) else page(url)
        } catch (_: Exception) {
            null
        }
        if (m != null && (m.title != null || m.image != null)) cache[url] = m
        return m
    }

    private fun youtube(url: String): Meta {
        val id = youtubeId.find(url)?.groupValues?.get(1)
        val picture = id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        val title = try {
            val o = JSONObject(get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), 64 * 1024))
            o.optString("title").ifBlank { null }
        } catch (_: Exception) {
            null
        }
        return Meta(title, picture)
    }

    private fun page(url: String): Meta? {
        val html = get(url, 400 * 1024)
        fun meta(name: String): String? {
            val a = Regex("""<meta[^>]+(?:property|name)=["']$name["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            val b = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["']$name["']""", RegexOption.IGNORE_CASE).find(html)
            return (a ?: b)?.groupValues?.get(1)?.let { Html.fromHtml(it, 0).toString().trim() }?.ifBlank { null }
        }
        val title = meta("og:title") ?: meta("twitter:title")
            ?: Regex("""<title[^>]*>([^<]{1,300})</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?.let { Html.fromHtml(it, 0).toString().trim() }?.ifBlank { null }
        val image = (meta("og:image") ?: meta("twitter:image"))?.let { runCatching { URL(URL(url), it).toString() }.getOrNull() }
        return Meta(title, image)
    }

    private fun get(url: String, limit: Int): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
        }
        try {
            val bytes = c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < limit) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }
}
