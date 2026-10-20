package io.github.apostolichiro.videodl.engine

/** Something yt-dlp reported while working. */
sealed interface Event {
    /** Bytes are coming in for one file (the video part, the audio part, or a single combined file). */
    data class Download(
        val status: String,
        val done: Long?,
        val total: Long?,
        val speed: Double?,
        val etaSec: Long?,
        val audioOnly: Boolean,
        val videoOnly: Boolean,
        val index: Int?,
        val count: Int?,
        val title: String?,
        /** False for aria2c readouts, which don't say whether this is the video or the audio part. */
        val partKnown: Boolean = true,
    ) : Event {
        val fraction: Float?
            get() = if (done != null && total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
    }

    /** A video is about to be downloaded (printed once per video, also for each playlist item). */
    data class Item(val title: String?, val index: Int?, val count: Int?, val thumbnail: String? = null) : Event

    /** A file starts downloading: the single file, or the video/audio part of a video that gets merged. */
    data class Part(val audio: Boolean, val videoOnly: Boolean) : Event

    /** A finishing step: merging video+audio, fixing a stream into MP4, pulling out audio, moving the file. */
    data class Post(val step: String, val title: String?) : Event

    /** yt-dlp is still looking at the page (before any bytes arrive). */
    data object Looking : Event
}

/** Machine-readable progress lines, and the parser for them. Pure Kotlin so it can be unit-tested. */
object Lines {
    private const val DL = "@@P"
    private const val PP = "@@Q"
    private const val IT = "@@T"

    // Title goes last because it may contain the separator.
    val downloadTemplate = "download:$DL|%(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
        "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.vcodec)s|%(info.acodec)s|" +
        "%(info.playlist_index)s|%(info.n_entries)s|%(info.title)s"

    val postTemplate = "postprocess:$PP|%(progress.status)s|%(progress.postprocessor)s|%(info.title)s"

    /** For --print (with --no-quiet): the title as soon as the video is known, even when aria2c does the downloading. */
    val itemTemplate = "before_dl:$IT|%(playlist_index)s|%(n_entries)s|%(thumbnail)s|%(title)s"

    // aria2c's once-a-second summary: [#0eada7 352KiB/57MiB(0%) CN:7 DL:356KiB ETA:2m43s]
    private val aria = Regex("""^\[#[0-9a-fA-F]+ ([\d.]+)([KMGT]?i?B)(?:/([\d.]+)([KMGT]?i?B)\((\d+)%\))?(?: CN:\d+)?(?: SD:\d+)?(?: DL:([\d.]+)([KMGT]?i?B))?(?: UL:\S+)?(?: ETA:([\dhms]+))?]""")
    private val destination = Regex("""^\[download] Destination: (.+)$""")
    private val formatPart = Regex("""\.f[\w-]+\.\w+$""")
    private val audioExt = setOf("m4a", "mp3", "aac", "opus", "ogg", "oga", "weba", "mka", "wav", "flac")

    private val bracketStep = Regex("""^\[(Merger|FixupM3u8|FixupM4a|FixupStretched|FixupDuplicateMoov|FixupTimestamp|VideoRemuxer|VideoConvertor|ExtractAudio|MoveFiles)]""")
    private val looking = Regex("""^\[[A-Za-z0-9:_]+] """)

    fun parse(line: String): Event? {
        val l = line.trim()
        if (l.startsWith("$DL|")) {
            val f = l.split('|', limit = 12)
            if (f.size < 12) return null
            val vcodec = f[7].na()
            val acodec = f[8].na()
            return Event.Download(
                status = f[1],
                done = f[2].num()?.toLong(),
                total = (f[3].num() ?: f[4].num())?.toLong(),
                speed = f[5].num(),
                etaSec = f[6].num()?.toLong(),
                audioOnly = vcodec == "none" && acodec != null && acodec != "none",
                videoOnly = acodec == "none" && vcodec != null && vcodec != "none",
                index = f[9].num()?.toInt(),
                count = f[10].num()?.toInt(),
                title = f[11].na(),
            )
        }
        if (l.startsWith("$IT|")) {
            val f = l.split('|', limit = 5)
            if (f.size < 5) return null
            return Event.Item(
                title = f[4].na(), index = f[1].num()?.toInt(), count = f[2].num()?.toInt(),
                thumbnail = f[3].na()?.takeIf { it.startsWith("http") },
            )
        }
        aria.find(l)?.let { m ->
            val g = m.groupValues
            val done = bytes(g[1], g[2])
            val total = if (g[3].isNotEmpty()) bytes(g[3], g[4]) else null
            return Event.Download(
                status = "downloading", done = done, total = total?.takeIf { it > 0 },
                speed = if (g[6].isNotEmpty()) bytes(g[6], g[7])?.toDouble() else null,
                etaSec = if (g[8].isNotEmpty()) eta(g[8]) else null,
                audioOnly = false, videoOnly = false, index = null, count = null, title = null, partKnown = false,
            )
        }
        destination.find(l)?.let { m ->
            val path = m.groupValues[1].trim().removeSurrounding("\"")
            val ext = path.substringAfterLast('.', "").lowercase()
            val audio = ext in audioExt
            return Event.Part(audio = audio, videoOnly = !audio && formatPart.containsMatchIn(path))
        }
        if (l.startsWith("$PP|")) {
            val f = l.split('|', limit = 4)
            if (f.size < 4) return null
            return Event.Post(step = f[2], title = f[3].na())
        }
        bracketStep.find(l)?.let { return Event.Post(step = it.groupValues[1], title = null) }
        if (looking.containsMatchIn(l) && !l.startsWith("[download]") && !l.startsWith("[debug]")) return Event.Looking
        return null
    }

    private fun bytes(n: String, unit: String): Long? {
        val v = n.toDoubleOrNull() ?: return null
        val mul = when (unit.uppercase().firstOrNull()) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            'T' -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (v * mul).toLong()
    }

    private fun eta(s: String): Long? {
        val m = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(s) ?: return null
        val (h, mi, se) = m.destructured
        return (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (se.toLongOrNull() ?: 0)
    }

    private fun String.na(): String? = takeUnless { it.isBlank() || it == "NA" || it == "None" }
    private fun String.num(): Double? = na()?.toDoubleOrNull()
}

/** Finding the link inside whatever text another app shares ("Watch this! https://youtu.be/...") */
object Links {
    private val url = Regex("""https?://[^\s<>"'　]+""", RegexOption.IGNORE_CASE)

    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = url.find(text) ?: run {
            // "youtu.be/abc" or "www.instagram.com/reel/..." without the scheme.
            val bare = Regex("""(?:www\.|m\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+/[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)
                ?: return null
            return "https://" + bare.value.trimEnd('.', ',', ')', ']', '!', '?')
        }
        return m.value.trimEnd('.', ',', ')', ']', '!', '?', '>', '」', '』')
    }

    fun looksLikeLink(text: String): Boolean = extract(text) != null

    private val trackingParams = setOf(
        "si", "feature", "igsh", "igshid", "fbclid", "gclid", "ref", "ref_src", "ref_url", "t", "s",
        "is_from_webapp", "sender_device", "_r", "_t", "share_id", "pp", "embeds_referring_euri", "mibextid",
    )

    /**
     * One address per video, so a video shared again ("youtu.be/x", "m.youtube.com/watch?v=x&si=...")
     * is recognised as already downloaded.
     */
    fun normalize(link: String): String {
        val m = Regex("""^(https?)://([^/?#]+)([^?#]*)(\?[^#]*)?""", RegexOption.IGNORE_CASE).find(link.trim()) ?: return link.trim()
        var host = m.groupValues[2].lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        val path = m.groupValues[3].trimEnd('/')
        val params = m.groupValues[4].removePrefix("?").split('&').filter { it.isNotBlank() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
        if (host == "twitter.com") host = "x.com"
        if (host == "youtu.be") return "youtube.com/watch?v=" + path.trim('/').substringBefore('/')
        if (host.endsWith("youtube.com")) {
            Regex("""^/(shorts|live|embed)/([\w-]+)""").find(path)?.let { return "youtube.com/watch?v=" + it.groupValues[2] }
            if (path == "/watch") params.firstOrNull { it.first == "v" }?.let { return "youtube.com/watch?v=" + it.second }
            host = "youtube.com"
        }
        val kept = params.filter { (k, _) -> k.lowercase() !in trackingParams && !k.lowercase().startsWith("utm_") }.sortedBy { it.first }
        return host + path + if (kept.isEmpty()) "" else "?" + kept.joinToString("&") { "${it.first}=${it.second}" }
    }

    fun isYouTube(link: String): Boolean {
        val h = host(link).lowercase()
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com") || h.endsWith("youtube-nocookie.com")
    }

    /** "dc.example.co.kr" -> "example.co.kr", "www.instagram.com" -> "instagram.com" */
    fun baseDomain(host: String): String {
        val parts = host.lowercase().trim('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val sld = parts[parts.size - 2]
        val secondLevel = setOf("co", "com", "net", "org", "or", "go", "ac", "ne", "re", "gov", "edu", "pe", "kg", "es", "hs", "ms")
        val n = if (parts.last().length == 2 && sld in secondLevel) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    fun host(link: String): String =
        Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)?.removePrefix("www.") ?: link
}

/** Picks out the video streams a web page loads, so they can be downloaded when the page itself can't be read. */
object Sniff {
    private val manifest = Regex("""\.(m3u8|mpd)$""", RegexOption.IGNORE_CASE)
    private val file = Regex("""\.(mp4|m4v|webm|mov|mkv|flv|3gp)$""", RegexOption.IGNORE_CASE)
    // Pieces of a stream, not something to download on its own.
    private val pieceParams = Regex("""[?&](bytestart|byteend|range)=[^&]*""", RegexOption.IGNORE_CASE)
    private val skipHosts = listOf("googlevideo.com", "doubleclick.net", "googlesyndication.com", "googleadservices.com")

    /** 2 for a stream playlist (best), 1 for a plain video file, null when it isn't a video. */
    fun kind(url: String): Int? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val host = Links.host(url).lowercase()
        if (skipHosts.any { host == it || host.endsWith(".$it") }) return null
        val path = url.substringBefore('#').substringBefore('?')
        return when {
            manifest.containsMatchIn(path) -> 2
            file.containsMatchIn(path) -> 1
            url.contains(".m3u8?", ignoreCase = true) -> 2
            else -> null
        }
    }

    /** Drops byte-range parameters so the whole file is fetched. */
    fun clean(url: String): String {
        val cleaned = url.replace(pieceParams, "")
        return if (!cleaned.contains('?') && cleaned.contains('&')) cleaned.replaceFirst('&', '?') else cleaned
    }

    /** Adds [url] to [seen] (newest first, streams before files), keeping at most [max]. */
    fun add(seen: List<String>, url: String, max: Int = 12): List<String> {
        val k = kind(url) ?: return seen
        val u = clean(url)
        val rest = seen.filterNot { it == u }
        val streams = rest.filter { kind(it) == 2 }
        val files = rest.filter { kind(it) != 2 }
        return (if (k == 2) listOf(u) + streams + files else streams + listOf(u) + files).take(max)
    }
}

/** yt-dlp's English errors turned into something a person can act on. */
object Errors {
    fun lastErrorLine(stderr: String?): String? =
        stderr?.lineSequence()?.map { it.trim() }?.filter { it.startsWith("ERROR:") }?.lastOrNull()?.removePrefix("ERROR:")?.trim()
            ?: stderr?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.lastOrNull()

    fun korean(raw: String?): String {
        val e = raw ?: return "알 수 없는 이유로 못 받았어요."
        val l = e.lowercase()
        return when {
            "unsupported url" in l -> "이 링크에서는 영상을 찾지 못했어요. 영상이 있는 페이지 링크인지 확인해 주세요."
            "drm" in l -> "DRM으로 보호된 영상이라 받을 수 없어요."
            "confirm you" in l && "bot" in l -> "유튜브가 로봇인지 확인하겠다고 막았어요. 잠시 뒤에 다시 해보거나 와이파이/데이터를 바꿔서 해보세요."
            Regex("""\bage[- ]restricted|confirm your age|\bage[- ]gated|inappropriate for some users""").containsMatchIn(l) ->
                "나이 제한이 걸린 영상이라 로그인 없이 받을 수 없어요."
            "private" in l -> "비공개 영상이라 받을 수 없어요."
            "login" in l || "log in" in l || "sign in" in l || "cookies" in l || "authentication" in l ->
                "로그인해야 볼 수 있는 영상이라 지금은 받을 수 없어요."
            "not available in your country" in l || "geo" in l && "restrict" in l -> "이 나라에서는 볼 수 없게 막힌 영상이에요."
            "video unavailable" in l || "has been removed" in l || "no longer available" in l || "404" in l ->
                "영상이 지워졌거나 볼 수 없는 상태예요."
            "live event will begin" in l || "premieres in" in l -> "아직 시작하지 않은 방송/최초 공개 영상이에요."
            "is live" in l || "live stream" in l && "not" in l -> "생방송 중인 영상은 끝난 뒤에 받아 주세요."
            "no space left" in l -> "저장공간이 부족해요. 공간을 비우고 다시 해보세요."
            "403" in l || "forbidden" in l -> "사이트가 다운로드를 막았어요(403). 엔진을 업데이트하고 다시 해보세요."
            "unable to download webpage" in l || "failed to resolve" in l || "network is unreachable" in l ||
                "timed out" in l || "connection" in l || "getaddrinfo" in l || "name or service not known" in l ->
                "인터넷 연결이 불안정해서 못 받았어요. 연결을 확인하고 다시 해보세요."
            "requested format is not available" in l -> "고른 화질이 없어요. 다른 화질로 해보세요."
            "unable to obtain file audio codec" in l || "does not contain any stream" in l && "audio" in l ->
                "이 영상에는 소리가 없어서 소리만 받을 수는 없어요. 다른 화질로 받아 주세요."
            else -> "못 받았어요: " + e.take(200)
        }
    }
}

/** Errors that a logged-in browser session (cookies) might get past. */
fun Errors.needsLogin(raw: String?): Boolean {
    val l = raw?.lowercase() ?: return false
    return listOf(
        "login", "log in", "sign in", "cookies", "authentication", "private", "not a bot", "rate-limit",
        "registered users", "members-only", "subscriber", "confirm your age", "age-restricted", "age restricted",
    ).any { it in l }
}

/** Errors where trying again with the newest yt-dlp could help (a site changed), as opposed to private videos, no network... */
fun Errors.fixableByUpdate(raw: String?): Boolean {
    val l = raw?.lowercase() ?: return true
    if (Regex("""\bage[- ]restricted|confirm your age|\bage[- ]gated""").containsMatchIn(l)) return false
    val hopeless = listOf(
        "drm", "private", "login", "log in", "sign in", "cookies", "not available in your country", "video unavailable",
        "has been removed", "no longer available", "no space left", "urlopen error", "timed out", "network is unreachable",
        "getaddrinfo", "failed to resolve", "name or service not known", "live event", "premieres in", "connection",
        "unable to obtain file audio codec",
    )
    return hopeless.none { it in l }
}

object Fmt {
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1fMB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%dKB".format(bytes / (1L shl 10))
        else -> "${bytes}B"
    }

    fun speed(bytesPerSec: Double): String = size(bytesPerSec.toLong()) + "/s"

    fun eta(sec: Long): String = when {
        sec >= 3600 -> "${sec / 3600}시간 ${(sec % 3600) / 60}분 남음"
        sec >= 60 -> "${sec / 60}분 ${sec % 60}초 남음"
        else -> "${sec}초 남음"
    }
}
