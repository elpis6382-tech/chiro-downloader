package io.github.apostolichiro.videodl.engine

import java.net.URLEncoder

/** Search engines for the address bar. */
enum class SearchEngine(val label: String, val prefix: String) {
    GOOGLE("구글", "https://www.google.com/search?q="),
    NAVER("네이버", "https://m.search.naver.com/search.naver?query="),
    DAUM("다음", "https://m.search.daum.net/search?q="),
    BING("빙", "https://www.bing.com/search?q=");

    companion object {
        fun of(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: GOOGLE
    }
}

/** A site on the home page. */
data class Shortcut(val name: String, val url: String)

/** Browser helpers that don't need Android, so they can be unit-tested. */
object Web {
    val defaultShortcuts = listOf(
        Shortcut("인스타그램", "https://www.instagram.com"),
        Shortcut("틱톡", "https://www.tiktok.com"),
        Shortcut("X", "https://x.com"),
        Shortcut("페이스북", "https://m.facebook.com"),
        Shortcut("네이버 TV", "https://tv.naver.com"),
        Shortcut("치지직", "https://chzzk.naver.com"),
        Shortcut("SOOP", "https://www.sooplive.co.kr"),
        Shortcut("비메오", "https://vimeo.com"),
        Shortcut("데일리모션", "https://www.dailymotion.com"),
        Shortcut("레딧", "https://www.reddit.com"),
    )

    private val address = Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?([/?#].*)?$""")
    private val localAddress = Regex("""^(localhost|\d{1,3}(\.\d{1,3}){3})(:\d+)?([/?#].*)?$""")

    /** A web address as typed ("youtube.com", "https://...") or anything else as a search. */
    fun target(input: String, engine: SearchEngine): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (!t.contains(' ') && (localAddress.matches(t))) return "http://$t"
        if (!t.contains(' ') && address.matches(t) && !t.endsWith('.')) return "https://$t"
        return engine.prefix + URLEncoder.encode(t, "UTF-8")
    }

    /** "https://www.youtube.com/watch?v=1" -> "youtube.com/watch?v=1" (for the address bar when not typing). */
    fun pretty(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.").trimEnd('/')

    /** Address of a page with one video on it, where looking it up ahead of time is worth it. */
    fun isVideoPage(url: String): Boolean {
        val m = Regex("""^https?://([^/?#]+)([^?#]*)(\?[^#]*)?""", RegexOption.IGNORE_CASE).find(url) ?: return false
        val host = m.groupValues[1].lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        val path = m.groupValues[2]
        val query = m.groupValues[3]
        fun on(vararg hosts: String) = hosts.any { host == it || host.endsWith(".$it") }
        fun p(re: String) = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(path)
        return when {
            host == "youtu.be" -> path.length > 1
            on("youtube.com") -> (path == "/watch" && Regex("""[?&]v=""").containsMatchIn(query)) || p("""^/(shorts|live)/[\w-]+""")
            on("instagram.com") -> p("""^/(p|reel|reels|tv)/[\w-]+""") || p("""^/stories/[^/]+/\d+""")
            on("tiktok.com") -> p("""/video/\d+""") || p("""^/v/\d+""") || host.startsWith("vt.") || host.startsWith("vm.")
            on("x.com", "twitter.com") -> p("""^/[^/]+/status/\d+""")
            host == "fb.watch" -> path.length > 1
            on("facebook.com") -> p("""^/(watch|reel|share/v|share/r)/?""") || p("""/videos/""")
            on("tv.naver.com") -> p("""^/(v|l|h)/\d+""")
            on("chzzk.naver.com") -> p("""^/(video|clips)/[\w-]+""")
            on("vimeo.com") -> p("""^/(\d+|video/\d+|channels/[^/]+/\d+)""")
            on("dailymotion.com") -> p("""^/video/[\w-]+""")
            host == "dai.ly" -> path.length > 1
            on("twitch.tv") -> p("""^/videos/\d+""") || p("""^/[^/]+/clip/[\w-]+""") || host.startsWith("clips.")
            on("reddit.com") -> p("""^/r/[^/]+/comments/\w+""")
            on("bilibili.com") -> p("""^/video/(BV|av)\w+""")
            on("sooplive.co.kr", "afreecatv.com") -> p("""^/player/\d+""")
            on("threads.net", "threads.com") -> p("""^/@[^/]+/post/[\w-]+""")
            on("pinterest.com", "pin.it") -> p("""^/pin/\d+""") || host == "pin.it"
            else -> false
        }
    }
}

/** Blocks requests to well-known ad and pop-up networks in the in-app browser. */
object AdBlock {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "google-analytics.com",
        "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com", "zedo.com", "adform.net",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "smartadserver.com",
        "amazon-adsystem.com", "advertising.com", "moatads.com", "scorecardresearch.com", "quantserve.com",
        "onclickads.net", "onclkds.com", "adcash.com", "clickadu.com", "hilltopads.net", "a-ads.com",
        "bidvertiser.com", "ad-maven.com", "realsrv.com", "tsyndicate.com", "adsco.re", "popmyads.com",
        "yllix.com", "admaven.com", "adspyglass.com", "pushnami.com", "mobon.net", "dable.io", "adop.cc",
        "realclick.co.kr", "tenping.kr", "ads.yahoo.com", "media.net", "yieldmo.com", "teads.tv",
        "inmobi.com", "applovin.com", "unityads.unity3d.com", "adcolony.com", "vungle.com",
    )

    fun blocks(host: String): Boolean {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
