package io.github.apostolichiro.videodl.engine

/**
 * What to download. Video always ends up as a real MP4 (H.264 preferred because every phone,
 * gallery and messenger plays it), audio as M4A.
 */
enum class Quality(val label: String) {
    BEST("최고 화질"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    AUDIO("소리만");

    val audioOnly: Boolean get() = this == AUDIO

    fun options(): List<String> = when (this) {
        AUDIO -> listOf(
            "-f", "ba/b",
            "-S", "acodec:m4a",
            "-x", "--audio-format", "m4a",
        )
        else -> {
            val res = when (this) {
                P1080 -> "res:1080"
                P720 -> "res:720"
                P480 -> "res:480"
                else -> "res"
            }
            listOf(
                "-f", "bv*+ba/b",
                // Highest resolution first (capped for the p-options), then H.264 > H.265 > VP9 > AV1, AAC audio.
                "-S", "$res,fps,vcodec:h264,acodec:m4a,ext:mp4:m4a",
                "--merge-output-format", "mp4",
                // Anything that isn't WebM is turned into MP4 without re-encoding; WebM (VP9) already plays everywhere.
                "--remux-video", "webm>webm/mp4",
            )
        }
    }

    companion object {
        fun of(name: String?): Quality = entries.firstOrNull { it.name == name } ?: BEST
    }
}
