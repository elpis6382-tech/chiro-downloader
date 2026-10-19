package io.github.apostolichiro.videodl

import android.content.Context
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Errors
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

/** Looks up a link (title, picture, sizes per quality) without downloading, with a short memory. */
object Infos {
    private class Entry(val preview: Preview, val at: Long)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 20
    }
    private const val KEEP_MS = 10 * 60 * 1000L

    fun cached(url: String): Preview? = synchronized(cache) {
        cache[Links.normalize(url)]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.preview
    }

    /**
     * Runs yt-dlp's lookup. Logins from the in-app browser are used for sites that need them
     * (not YouTube, which works best without). Cancelling the coroutine stops yt-dlp.
     */
    suspend fun fetch(ctx: Context, url: String, referer: String? = null, userAgent: String? = null): InfoState {
        cached(url)?.let { return InfoState.Ready(it) }
        val pid = "info-" + System.nanoTime()
        val youtube = Links.isYouTube(url)
        return try {
            coroutineScope {
                val work = async(Dispatchers.IO) {
                    val cookieFile = File(ctx.cacheDir, "$pid.cookies")
                    try {
                        val cookies = if (!youtube && Cookies.has(url)) Cookies.export(url, cookieFile) else null
                        Engine.info(ctx, url, pid, cookies, referer, if (youtube) null else userAgent)
                    } finally {
                        cookieFile.delete()
                    }
                }
                try {
                    val p = work.await()
                    synchronized(cache) { cache[Links.normalize(url)] = Entry(p, System.currentTimeMillis()) }
                    InfoState.Ready(p)
                } catch (e: CancellationException) {
                    Engine.cancel(pid)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InfoState.Failed(Errors.korean(Errors.lastErrorLine(e.message) ?: e.message))
        }
    }
}
