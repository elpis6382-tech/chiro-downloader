package io.github.apostolichiro.videodl.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads a video the browser saw a page play the way the browser itself fetched it: Android's own
 * connection, the page's cookies, the browser's User-Agent, Referer and Origin. Some sites refuse
 * yt-dlp's requests (403) but not these. HLS streams are fetched piece by piece (6 at a time, AES-128
 * decrypted when needed) and put together into an MP4 with ffmpeg.
 */
object Direct {
    private const val TAG = "VideoDl"

    /** The playlist uses something this downloader doesn't do (then yt-dlp gets its turn). */
    class Unsupported(message: String) : Exception(message)

    /** Paused or cancelled. */
    class Stopped : Exception("stopped")

    class Headers(val userAgent: String?, val referer: String?, val origin: String?, val cookie: (String) -> String?)

    /** [onProgress]: bytes so far, total bytes (estimated for streams), share done, bytes per second. */
    fun download(
        ctx: Context, url: String, h: Headers, dir: File, name: String,
        stopped: () -> Boolean, onProgress: (Long, Long?, Float?, Double?) -> Unit,
    ): File {
        dir.mkdirs()
        val safe = Engine.outputName(name).replace("%%", "%")
        val isStream = Sniff.kind(url) == 2 || url.substringBefore('?').endsWith(".m3u8", true)
        return if (isStream) hls(ctx, url, h, dir, safe, stopped, onProgress) else file(url, h, dir, safe, stopped, onProgress)
    }

    private fun open(url: String, h: Headers, range: Long? = null): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                h.userAgent?.let { setRequestProperty("User-Agent", it) }
                h.referer?.let { setRequestProperty("Referer", it) }
                // A player on the page asks for the video from the page's site, like any web request.
                if (h.origin != null && !current.startsWith(h.origin)) setRequestProperty("Origin", h.origin)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                h.cookie(current)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                range?.let { setRequestProperty("Range", "bytes=$it-") }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("HTTP $code")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP Error $code")
            }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun bytes(url: String, h: Headers, stopped: () -> Boolean, counted: AtomicLong? = null): ByteArray {
        var last: Exception? = null
        for (attempt in 0 until 4) {
            if (stopped()) throw Stopped()
            try {
                val c = open(url, h)
                try {
                    val out = java.io.ByteArrayOutputStream(maxOf(c.contentLength, 64 * 1024))
                    val buf = ByteArray(64 * 1024)
                    c.inputStream.use { s ->
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            counted?.addAndGet(n.toLong())
                            if (stopped()) throw Stopped()
                        }
                    }
                    return out.toByteArray()
                } finally {
                    c.disconnect()
                }
            } catch (e: Stopped) {
                throw e
            } catch (e: Exception) {
                last = e
                // 403/404 won't get better by asking again right away.
                if (e.message?.contains("HTTP Error 4") == true && attempt >= 1) break
                Thread.sleep(800L * (attempt + 1))
            }
        }
        throw last ?: IOException("download failed")
    }

    private fun text(url: String, h: Headers, stopped: () -> Boolean): String {
        val t = String(bytes(url, h, stopped), Charsets.UTF_8)
        if (!t.trimStart().startsWith("#EXTM3U")) throw IOException("not a stream playlist")
        return t
    }

    private fun hls(
        ctx: Context, url: String, h: Headers, dir: File, name: String,
        stopped: () -> Boolean, onProgress: (Long, Long?, Float?, Double?) -> Unit,
    ): File {
        var mediaUrl = url
        var playlist = text(url, h, stopped)
        val top = Streams.parse(playlist, url)
        if (top.variants.isNotEmpty()) {
            mediaUrl = Streams.best(top.variants)!!.url
            playlist = text(mediaUrl, h, stopped)
        }
        val media = Streams.media(playlist, mediaUrl)
        media.unsupported?.let { throw Unsupported(it) }
        val segments = media.segments
        if (segments.isEmpty()) throw IOException("empty stream")

        val parts = File(dir, "parts").apply { mkdirs() }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val done = AtomicInteger(segments.indices.count { File(parts, "%05d.ok".format(it)).exists() })
        val got = AtomicLong(0)
        val started = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(6)
        val futures = mutableListOf<Future<*>>()
        try {
            segments.forEachIndexed { i, seg ->
                if (File(parts, "%05d.ok".format(i)).exists()) return@forEachIndexed
                futures += pool.submit {
                    var data = bytes(seg.url, h, stopped, got)
                    seg.key?.let { k ->
                        val key = keys.getOrPut(k.uri ?: throw Unsupported("key without address")) { bytes(k.uri, h, stopped) }
                        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(Streams.iv(k, seg.sequence)))
                        data = c.doFinal(data)
                    }
                    File(parts, "%05d.bin".format(i)).writeBytes(data)
                    File(parts, "%05d.ok".format(i)).createNewFile()
                    done.incrementAndGet()
                }
            }
            // Report progress while the pieces come in.
            while (futures.any { !it.isDone }) {
                if (stopped()) throw Stopped()
                report(done.get(), segments.size, got.get(), started, onProgress)
                Thread.sleep(500)
            }
            futures.forEach { f ->
                try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
        report(done.get(), segments.size, got.get(), started, onProgress)

        // Put the pieces together (fMP4 streams start with their init piece), then make a real MP4 of it.
        val joined = File(dir, "joined.bin")
        joined.outputStream().buffered(1 shl 20).use { out ->
            segments.firstOrNull()?.map?.let { out.write(bytes(it, h, stopped)) }
            segments.indices.forEach { i -> File(parts, "%05d.bin".format(i)).inputStream().use { it.copyTo(out, 1 shl 20) } }
        }
        val result = File(dir, "$name.mp4")
        if (!Engine.remux(ctx, joined, result, forceTs = segments.first().map == null)) throw IOException("could not make an MP4 of the stream")
        joined.delete()
        parts.deleteRecursively()
        return result
    }

    private fun report(done: Int, total: Int, bytes: Long, started: Long, onProgress: (Long, Long?, Float?, Double?) -> Unit) {
        val secs = (System.currentTimeMillis() - started) / 1000.0
        val estimate = if (done > 0) bytes * total / done else null
        onProgress(bytes, estimate, done.toFloat() / total, if (secs > 0.5) bytes / secs else null)
    }

    private fun file(
        url: String, h: Headers, dir: File, name: String,
        stopped: () -> Boolean, onProgress: (Long, Long?, Float?, Double?) -> Unit,
    ): File {
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
        val part = File(dir, "$name.$ext.part")
        val have = if (part.exists()) part.length() else 0L
        val c = open(url, h, range = have.takeIf { it > 0 })
        try {
            val resumed = c.responseCode == 206
            val total = c.contentLengthLong.takeIf { it > 0 }?.let { if (resumed) it + have else it }
            var count = if (resumed) have else 0L
            val started = System.currentTimeMillis()
            var lastReport = 0L
            java.io.FileOutputStream(part, resumed).buffered(1 shl 20).use { out ->
                c.inputStream.use { s ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (stopped()) throw Stopped()
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        count += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 500) {
                            lastReport = now
                            val secs = (now - started) / 1000.0
                            onProgress(count, total, total?.let { count.toFloat() / it }, if (secs > 0.5) (count - (if (resumed) have else 0)) / secs else null)
                        }
                    }
                }
            }
            if (total != null && part.length() < total) throw IOException("download cut short")
        } finally {
            c.disconnect()
        }
        val result = File(dir, "$name.$ext")
        result.delete()
        if (!part.renameTo(result)) throw IOException("could not finish the file")
        Log.i(TAG, "direct download done: ${result.length()} bytes")
        return result
    }
}
