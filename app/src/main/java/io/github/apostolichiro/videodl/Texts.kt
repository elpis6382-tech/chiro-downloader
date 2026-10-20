package io.github.apostolichiro.videodl

import io.github.apostolichiro.videodl.engine.Fmt
import io.github.apostolichiro.videodl.engine.Links

/** The words shown for a job, shared by the screens and the notification. */
object Texts {
    fun title(job: Job): String = job.title ?: job.files.firstOrNull()?.name ?: Links.host(job.url)

    /** "24.1MB / 38.9MB" (or just "24.1MB" while the total isn't known). */
    private fun bytes(job: Job): String? {
        val done = job.doneBytes ?: return null
        val total = job.totalBytes
        return if (total != null && total > 0) "${Fmt.size(done)} / ${Fmt.size(total)}" else Fmt.size(done)
    }

    fun status(job: Job): String = when (job.status) {
        Status.QUEUED -> "기다리는 중"
        Status.PREPARING -> when (job.step) {
            "UPDATING" -> "다운로드 엔진 업데이트 중…"
            "STARTING" -> "준비 중…"
            else -> "영상 찾는 중…"
        }
        Status.DOWNLOADING -> buildString {
            if (job.index != null && job.count != null && job.count > 1) append("${job.index}/${job.count} · ")
            when (job.step) {
                "AUDIO" -> append("소리 · ")
                "VIDEO_ONLY" -> append("영상 · ")
            }
            append(bytes(job) ?: job.progress?.let { "${(it * 100).toInt()}%" } ?: "받는 중")
            job.speed?.takeIf { it > 0 }?.let { append(" · " + Fmt.speed(it)) }
            job.etaSec?.takeIf { it > 0 }?.let { append(" · " + Fmt.eta(it)) }
        }
        Status.PROCESSING -> when (job.step) {
            "Merger" -> "영상과 소리를 합치는 중…"
            "ExtractAudio" -> "소리만 뽑는 중…"
            "VideoRemuxer", "VideoConvertor" -> "MP4로 바꾸는 중…"
            "Repair" -> "재생되게 MP4로 고치는 중…"
            else -> if (job.step?.startsWith("Fixup") == true) "MP4로 정리하는 중…" else "마무리하는 중…"
        }
        Status.SAVING -> "저장하는 중…"
        Status.PAUSED -> buildString {
            append(if (job.step == "INTERRUPTED") "앱이 꺼져서 멈췄어요" else "일시정지")
            bytes(job)?.let { append(" · $it") }
        }
        Status.DONE -> buildString {
            val total = job.files.sumOf { it.size }
            if (job.files.size > 1) append("완료 · 파일 ${job.files.size}개 · ${Fmt.size(total)}")
            else append("완료 · ${Fmt.size(total)} · ${job.files.firstOrNull()?.name?.substringAfterLast('.', "")?.uppercase() ?: ""}")
        }
        Status.FAILED -> job.error ?: "못 받았어요."
        Status.CANCELLED -> "취소했어요"
    }
}
