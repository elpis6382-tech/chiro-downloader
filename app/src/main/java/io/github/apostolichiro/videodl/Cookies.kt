package io.github.apostolichiro.videodl

import android.util.Log
import android.webkit.CookieManager
import io.github.apostolichiro.videodl.engine.Links
import java.io.File

/**
 * Hands the in-app browser's logins to yt-dlp: the cookies the browser holds for a site are
 * written to a Netscape cookie file, which yt-dlp reads with --cookies.
 */
object Cookies {
    private fun manager(): CookieManager? = try {
        CookieManager.getInstance()
    } catch (e: Exception) {
        Log.w("VideoDl", "no WebView cookie store", e)
        null
    }

    private fun collect(url: String): Pair<String, Map<String, String>>? {
        val host = Links.host(url).takeIf { it.isNotBlank() && !it.contains(' ') } ?: return null
        val base = Links.baseDomain(host)
        val cm = manager() ?: return null
        val pairs = LinkedHashMap<String, String>()
        for (u in listOf("https://$base/", "https://www.$base/", "https://$host/", url)) {
            val raw = try { cm.getCookie(u) } catch (_: Exception) { null } ?: continue
            raw.split(';').forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) pairs[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
        }
        return if (pairs.isEmpty()) null else base to pairs
    }

    fun has(url: String): Boolean = collect(url) != null

    /** Writes the cookies for [url]'s site to [to]. Returns the file, or null when there are none. */
    fun export(url: String, to: File): File? {
        val (base, pairs) = collect(url) ?: return null
        val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        to.parentFile?.mkdirs()
        to.writeText(buildString {
            append("# Netscape HTTP Cookie File\n")
            pairs.forEach { (k, v) -> append(".$base\tTRUE\t/\tFALSE\t$expires\t$k\t$v\n") }
        })
        return to
    }

    /** Logs out of every site in the in-app browser. */
    fun clearAll() {
        manager()?.removeAllCookies(null)
        manager()?.flush()
    }
}
