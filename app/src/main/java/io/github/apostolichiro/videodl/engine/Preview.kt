package io.github.apostolichiro.videodl.engine

/** One format yt-dlp offers for a video (only what the size estimate needs). */
data class FormatInfo(
    val vcodec: String?,
    val acodec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val tbr: Double?,
    val size: Long?,
    val ext: String?,
) {
    private fun real(c: String?) = c != null && c != "none"
    /** Unknown codecs (plain files from ordinary sites) count as video with sound. */
    val hasVideo get() = real(vcodec) || (vcodec == null && acodec == null && ext !in audioExts)
    val hasAudio get() = real(acodec) || (vcodec == null && acodec == null)
    val res: Int? get() = if (width != null && height != null) minOf(width, height) else height
    val h264 get() = vcodec?.let { it.startsWith("avc") || it.startsWith("h264") } == true
    val aac get() = acodec?.startsWith("mp4a") == true || ext == "m4a"

    fun bytes(duration: Double?): Long? = size ?: if (tbr != null && duration != null) (tbr * 1000 / 8 * duration).toLong() else null

    companion object {
        private val audioExts = setOf("m4a", "mp3", "aac", "opus", "ogg", "weba", "wav", "flac")
    }
}

/** What one quality button would download: its resolution and roughly how big the file is. */
data class Choice(val res: Int?, val bytes: Long?)

/** What the link box shows before downloading. */
data class Preview(
    val url: String,
    val title: String?,
    val uploader: String?,
    val duration: Double?,
    val thumbnail: String?,
    /** For a playlist/channel link: how many videos it holds. */
    val playlistCount: Int?,
    val choices: Map<Quality, Choice>,
    val maxRes: Int?,
) {
    val isPlaylist get() = playlistCount != null

    /** Quality buttons that would give the same as "최고 화질" are hidden. */
    fun shows(q: Quality): Boolean {
        val cap = Estimate.cap(q) ?: return true
        return maxRes == null || cap < maxRes
    }
}

/** Guesses, like yt-dlp's own format sorting, which formats each quality button picks. */
object Estimate {
    fun cap(q: Quality): Int? = when (q) {
        Quality.P1080 -> 1080
        Quality.P720 -> 720
        Quality.P480 -> 480
        else -> null
    }

    private val videoOrder = compareByDescending<FormatInfo> { it.res ?: 0 }
        .thenByDescending { it.fps ?: 0.0 }
        .thenByDescending { it.h264 }
        .thenByDescending { it.tbr ?: 0.0 }

    private val audioOrder = compareByDescending<FormatInfo> { it.aac }.thenByDescending { it.tbr ?: 0.0 }

    fun choices(formats: List<FormatInfo>, duration: Double?): Map<Quality, Choice> {
        val out = LinkedHashMap<Quality, Choice>()
        val videos = formats.filter { it.hasVideo }
        val audios = formats.filter { it.hasAudio && !it.hasVideo }
        val bestAudio = audios.sortedWith(audioOrder).firstOrNull()
        for (q in Quality.entries) {
            if (q == Quality.AUDIO) {
                // Without a separate audio stream the sound is cut out of a video, size unknown beforehand.
                if (bestAudio != null) out[q] = Choice(null, bestAudio.bytes(duration))
                else if (videos.any { it.hasAudio }) out[q] = Choice(null, null)
                continue
            }
            if (videos.isEmpty()) continue
            val cap = cap(q)
            val fit = if (cap == null) videos else videos.filter { (it.res ?: 0) <= cap }
            // Nothing small enough: yt-dlp then takes the smallest there is.
            val pick = fit.sortedWith(videoOrder).firstOrNull() ?: videos.minByOrNull { it.res ?: Int.MAX_VALUE } ?: continue
            val v = pick.bytes(duration)
            val total = when {
                pick.hasAudio -> v
                bestAudio == null -> v
                v == null -> null
                else -> bestAudio.bytes(duration)?.let { v + it } ?: v
            }
            out[q] = Choice(pick.res, total)
        }
        return out
    }

    fun maxRes(formats: List<FormatInfo>): Int? = formats.filter { it.hasVideo }.mapNotNull { it.res }.maxOrNull()

    /** "1080p", "4K", "720p60" style label for a resolution. */
    fun resLabel(res: Int?): String? = when {
        res == null -> null
        res >= 2160 -> "4K"
        res >= 1440 -> "1440p"
        else -> "${res}p"
    }
}

/**
 * Version codes of the app's own releases. The release notes carry "versionCode: 105";
 * older release titles only had "영상 다운로더 1.0.8" (version code 8).
 */
object Versions {
    fun parse(text: String?): Int? {
        if (text == null) return null
        Regex("""versionCode[:=]\s*(\d+)""").find(text)?.let { return it.groupValues[1].toIntOrNull() }
        return Regex("""1\.0\.(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}
