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
    ) : Event {
        val fraction: Float?
            get() = if (done != null && total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
    }

    /** A finishing step: merging video+audio, fixing a stream into MP4, pulling out audio, moving the file. */
    data class Post(val step: String, val title: String?) : Event

    /** yt-dlp is still looking at the page (before any bytes arrive). */
    data object Looking : Event
}

/** Machine-readable progress lines, and the parser for them. Pure Kotlin so it can be unit-tested. */
object Lines {
    private const val DL = "@@P"
    private const val PP = "@@Q"

    // Title goes last because it may contain the separator.
    val downloadTemplate = "download:$DL|%(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
        "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.vcodec)s|%(info.acodec)s|" +
        "%(info.playlist_index)s|%(info.n_entries)s|%(info.title)s"

    val postTemplate = "postprocess:$PP|%(progress.status)s|%(progress.postprocessor)s|%(info.title)s"

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
        if (l.startsWith("$PP|")) {
            val f = l.split('|', limit = 4)
            if (f.size < 4) return null
            return Event.Post(step = f[2], title = f[3].na())
        }
        bracketStep.find(l)?.let { return Event.Post(step = it.groupValues[1], title = null) }
        if (looking.containsMatchIn(l) && !l.startsWith("[download]") && !l.startsWith("[debug]")) return Event.Looking
        return null
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

    fun host(link: String): String =
        Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)?.removePrefix("www.") ?: link
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
            else -> "못 받았어요: " + e.take(200)
        }
    }
}

/** Errors where trying again with the newest yt-dlp could help (a site changed), as opposed to private videos, no network... */
fun Errors.fixableByUpdate(raw: String?): Boolean {
    val l = raw?.lowercase() ?: return true
    if (Regex("""\bage[- ]restricted|confirm your age|\bage[- ]gated""").containsMatchIn(l)) return false
    val hopeless = listOf(
        "drm", "private", "login", "log in", "sign in", "cookies", "not available in your country", "video unavailable",
        "has been removed", "no longer available", "no space left", "urlopen error", "timed out", "network is unreachable",
        "getaddrinfo", "failed to resolve", "name or service not known", "live event", "premieres in", "connection",
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
